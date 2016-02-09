/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.pnc.buildagent.termserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.termd.core.pty.PtyMaster;
import io.termd.core.pty.Status;
import io.termd.core.pty.TtyBridge;
import io.undertow.server.HttpHandler;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSockets;
import org.jboss.pnc.buildagent.api.ResponseMode;
import org.jboss.pnc.buildagent.api.TaskStatusUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
* @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
* @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
*/
class Term {

  private Logger log = LoggerFactory.getLogger(Term.class);

  final String context;
  private Runnable onDestroy;
  final Set<Consumer<TaskStatusUpdateEvent>> statusUpdateListeners = new HashSet<>();
  private WebSocketTtyConnection webSocketTtyConnection;
  private boolean activeCommand;
  private ScheduledExecutorService executor;
  private Optional<ReadOnlyChannel> appendReadOnlyChannel;

  public Term(String context, Runnable onDestroy, ScheduledExecutorService executor, Optional<ReadOnlyChannel> appendReadOnlyChannel) {
    this.context = context;
    this.onDestroy = onDestroy;
    this.executor = executor;
    this.appendReadOnlyChannel = appendReadOnlyChannel;
  }

  public void addStatusUpdateListener(Consumer<TaskStatusUpdateEvent> statusUpdateListener) {
    statusUpdateListeners.add(statusUpdateListener);
  }

  public void removeStatusUpdateListener(Consumer<TaskStatusUpdateEvent> statusUpdateListener) {
    statusUpdateListeners.remove(statusUpdateListener);
  }

  public Consumer<PtyMaster> onTaskCreated() {
    return (ptyMaster) -> {
      ptyMaster.setChangeHandler((oldStatus, newStatus) -> {
        notifyStatusUpdated(
            new TaskStatusUpdateEvent(
                    "" + ptyMaster.getId(),
                    StatusConverter.fromTermdStatus(oldStatus),
                    StatusConverter.fromTermdStatus(newStatus),
                    context)
        );
      });
    };
  }

  void notifyStatusUpdated(TaskStatusUpdateEvent event) {
    if (event.getNewStatus().isFinal()) {
      activeCommand = false;
      log.debug("Command [context:{} taskId:{}] execution completed with status {}.", event.getContext(), event.getTaskId(), event.getNewStatus());
      writeCompltededToReadonlyChannel(StatusConverter.toTermdStatus(event.getNewStatus()));
      destroyIfInactiveAndDisconnected();
    } else {
      activeCommand = true;
    }
    for (Consumer<TaskStatusUpdateEvent> statusUpdateListener : statusUpdateListeners) {
      log.debug("Notifying listener {} in task {} with new status {}", statusUpdateListener, event.getTaskId(), event.getNewStatus());
      statusUpdateListener.accept(event);
    }
  }

  private void writeCompltededToReadonlyChannel(Status newStatus) {
    String completed = "% # Finished with status: " + newStatus + "\r\n";
    appendReadOnlyChannel.ifPresent(ch -> ch.writeOutput(completed.getBytes(Charset.forName("UTF-8"))));
  }

  private void destroyIfInactiveAndDisconnected() {
    if (!activeCommand && !webSocketTtyConnection.isOpen()) {
      log.debug("Destroying Term as there is no running command and no active connection.");
      onDestroy.run();
    }
  }

  synchronized HttpHandler getWebSocketHandler(ResponseMode responseMode) {
    //TODO unify appendReadonlyChannel and webSocketTtyConnection.addReadonlyChannel
    //TODO should we create new ttyConnection independent of webSockets and attach sockets later on
    WebSocketConnectionCallback onWebSocketConnected = (exchange, webSocketChannel) -> {
      if (webSocketTtyConnection == null) {
        webSocketTtyConnection = new WebSocketTtyConnection(webSocketChannel, responseMode, executor);
        appendReadOnlyChannel.ifPresent(ch -> webSocketTtyConnection.addReadonlyChannel(ch));
        webSocketChannel.addCloseTask((task) -> {webSocketTtyConnection.removeWebSocketChannel(); destroyIfInactiveAndDisconnected();});
        log.info("Creating new TtyBridge for socket with source address {}.", webSocketChannel.getSourceAddress().toString());
        TtyBridge ttyBridge = new TtyBridge(webSocketTtyConnection);
        ttyBridge
            .setProcessListener(onTaskCreated())
            .readline();
        ttyBridge.setProcessStdinListener((commandLine) -> {
          log.debug("New command received: {}", commandLine);
        });
      } else {
        if (webSocketTtyConnection.isOpen()) {
          ReadOnlyChannel readOnlyChannel;
          if (responseMode.equals(ResponseMode.TEXT)) {
            readOnlyChannel = new ReadOnlyWebSocketTextChannel(webSocketChannel);
          } else {
            readOnlyChannel = new ReadOnlyWebSocketChannel(webSocketChannel);
          }
          webSocketTtyConnection.addReadonlyChannel(readOnlyChannel);
          webSocketChannel.addCloseTask((task) -> {webSocketTtyConnection.removeReadonlyChannel(readOnlyChannel); destroyIfInactiveAndDisconnected();});
        } else { //reconnect to existing session if there is no active master connection
          webSocketTtyConnection.setWebSocketChannel(webSocketChannel);
          webSocketChannel.addCloseTask((task) -> {webSocketTtyConnection.removeWebSocketChannel(); destroyIfInactiveAndDisconnected();});
        }
      }
    };
    return new WebSocketProtocolHandshakeHandler(onWebSocketConnected);
  }

  HttpHandler webSocketStatusUpdateHandler() {
    WebSocketConnectionCallback webSocketConnectionCallback = (exchange, webSocketChannel) -> {
      Consumer<TaskStatusUpdateEvent> statusUpdateListener = event -> {
        Map<String, Object> statusUpdate = new HashMap<>();
        statusUpdate.put("action", "status-update");
        statusUpdate.put("event", event);

        ObjectMapper objectMapper = new ObjectMapper();
        try {
          String message = objectMapper.writeValueAsString(statusUpdate);
          WebSockets.sendText(message, webSocketChannel, null);
        } catch (JsonProcessingException e) {
          log.error("Cannot write object to JSON", e);
          String errorMessage = "Cannot write object to JSON: " + e.getMessage();
          WebSockets.sendClose(CloseMessage.UNEXPECTED_ERROR, errorMessage, webSocketChannel, null);
        }
      };
      log.debug("Registering new status update listener {}.", statusUpdateListener);
      addStatusUpdateListener(statusUpdateListener);
      webSocketChannel.addCloseTask((task) -> removeStatusUpdateListener(statusUpdateListener));
    };

    return new WebSocketProtocolHandshakeHandler(webSocketConnectionCallback);
  }
}
