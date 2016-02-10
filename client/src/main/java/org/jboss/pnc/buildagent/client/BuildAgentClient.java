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

package org.jboss.pnc.buildagent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.pnc.buildagent.api.ResponseMode;
import org.jboss.pnc.buildagent.api.TaskStatusUpdateEvent;
import org.jboss.pnc.buildagent.common.ObjectWrapper;
import org.jboss.pnc.buildagent.common.Wait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.CloseReason;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * @see "https://github.com/undertow-io/undertow/blob/5bdddf327209a4abf18792e78148863686c26e9b/websockets-jsr/src/test/java/io/undertow/websockets/jsr/test/BinaryEndpointTest.java"
 *
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class BuildAgentClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BuildAgentClient.class);
    private final ResponseMode responseMode;
    private final boolean readOnly;

    Client statusUpdatesClient;
    Client commandExecutingClient;

    public BuildAgentClient(String termSocketBaseUrl, String statusUpdatesSocketBaseUrl,
                            Optional<Consumer<String>> responseDataConsumer,
                            Consumer<TaskStatusUpdateEvent> onStatusUpdate,
                            String commandContext
                        ) throws TimeoutException, InterruptedException {
        this(termSocketBaseUrl, statusUpdatesSocketBaseUrl, responseDataConsumer, onStatusUpdate, commandContext, ResponseMode.BINARY, false);
    }

    /**
     * @deprecated Use commandContext instead of sessionId
     */
    @Deprecated
    public BuildAgentClient(String termSocketBaseUrl, String statusUpdatesSocketBaseUrl,
                            Optional<Consumer<String>> responseDataConsumer,
                            Consumer<TaskStatusUpdateEvent> onStatusUpdate,
                            String context,
                            Optional<String> sessionId) throws TimeoutException, InterruptedException {
        this(termSocketBaseUrl, statusUpdatesSocketBaseUrl, responseDataConsumer, onStatusUpdate, context, ResponseMode.BINARY, false);
    }

    public BuildAgentClient(String termSocketBaseUrl, String statusUpdatesSocketBaseUrl,
            Optional<Consumer<String>> responseDataConsumer,
            Consumer<TaskStatusUpdateEvent> onStatusUpdate,
            String commandContext,
            ResponseMode responseMode,
            boolean readOnly) throws TimeoutException, InterruptedException {

        this.responseMode = responseMode;
        this.readOnly = readOnly;

        Consumer<TaskStatusUpdateEvent> onStatusUpdateInternal = (event) -> {
            onStatusUpdate.accept(event);
        };

        statusUpdatesClient = connectStatusListenerClient(statusUpdatesSocketBaseUrl, onStatusUpdateInternal, commandContext);
        commandExecutingClient = connectCommandExecutingClient(termSocketBaseUrl, responseDataConsumer, commandContext);
    }

    public void executeCommand(String command) {
        log.info("Executing remote command ...");
        RemoteEndpoint.Basic remoteEndpoint = commandExecutingClient.getRemoteEndpoint();
        String data = "{\"action\":\"read\",\"data\":\"" + command + "\\n\"}";
        try {
            remoteEndpoint.sendBinary(ByteBuffer.wrap(data.getBytes()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Client connectStatusListenerClient(String webSocketBaseUrl, Consumer<TaskStatusUpdateEvent> onStatusUpdate, String commandContext) {
        Client client = initializeDefault();
        Consumer<String> responseConsumer = (text) -> {
            log.trace("Decoding response: {}", text);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonObject = null;
            try {
                jsonObject = mapper.readTree(text);
            } catch (IOException e) {
                log.error( "Cannot read JSON string: " + text, e);
            }
            try {
                TaskStatusUpdateEvent taskStatusUpdateEvent = TaskStatusUpdateEvent.fromJson(jsonObject.get("event").toString());
                onStatusUpdate.accept(taskStatusUpdateEvent);
            } catch (IOException e) {
                log.error("Cannot deserialize TaskStatusUpdateEvent.", e);
            }
        };
        client.onStringMessage(responseConsumer);

        client.onClose(closeReason -> {
        });

        try {
            client.connect(webSocketBaseUrl + Client.WEB_SOCKET_LISTENER_PATH + "/" + commandContext);
        } catch (Exception e) {
            throw new AssertionError("Failed to connect to remote client.", e);
        }
        return client;
    }

    private Client connectCommandExecutingClient(String webSocketBaseUrl, Optional<Consumer<String>> responseDataConsumer, String commandContext) throws InterruptedException, TimeoutException {
        ObjectWrapper<Boolean> connected = new ObjectWrapper<>(false);

        Client client = initializeDefault();

        if (ResponseMode.TEXT.equals(responseMode)) {
            registerTextResponseConsumer(responseDataConsumer, connected, client);
        } else {
            registerBinaryResponseConsumer(responseDataConsumer, connected, client);
        }

        client.onClose(closeReason -> {
            log.info("Client received close {}.", closeReason.toString());
        });

        String appendReadOnly = readOnly ? "/ro" : "";

        String webSocketPath;
        if (ResponseMode.TEXT.equals(responseMode)) {
            webSocketPath = webSocketBaseUrl + Client.WEB_SOCKET_TERMINAL_TEXT_PATH;
        } else {
            webSocketPath = webSocketBaseUrl + Client.WEB_SOCKET_TERMINAL_PATH;
        }

        if (commandContext != null && !commandContext.equals("")) {
            commandContext = "/" + commandContext;
        }

        try {
            client.connect(webSocketPath + commandContext + appendReadOnly);
        } catch (Exception e) {
            throw new AssertionError("Failed to connect to remote client.", e);
        }
        Wait.forCondition(() -> connected.get(), 10, ChronoUnit.SECONDS, "Client was not connected within given timeout.");
        return client;
    }

    private void registerBinaryResponseConsumer(Optional<Consumer<String>> responseDataConsumer, ObjectWrapper<Boolean> connected, Client client) {
        Consumer<byte[]> responseConsumer = (bytes) -> {
            String responseData = new String(bytes);
            if ("% ".equals(responseData)) { //TODO use events
                log.info("Received command line 'ready'(%) marker.");
                connected.set(true);
            } else {
                responseDataConsumer.ifPresent((rdc) -> rdc.accept(responseData));;
            }
        };
        client.onBinaryMessage(responseConsumer);
    }

    private void registerTextResponseConsumer(Optional<Consumer<String>> responseDataConsumer, ObjectWrapper<Boolean> connected, Client client) {
        Consumer<String> responseConsumer = (string) -> {
            if ("% ".equals(string)) { //TODO use events
                log.info("Received command line 'ready'(%) marker.");
                connected.set(true);
            } else {
                responseDataConsumer.ifPresent((rdc) -> rdc.accept(string));;
            }
        };
        client.onStringMessage(responseConsumer);
    }

    private static Client initializeDefault() {
        Client client = new Client();

        Consumer<Session> onOpen = (session) -> {
            log.info("Client connection opened.");
        };

        Consumer<CloseReason> onClose = (closeReason) -> {
            log.info("Client connection closed. " + closeReason);
        };

        client.onOpen(onOpen);
        client.onClose(onClose);

        return client;
    }

    @Override
    public void close() throws IOException {
        try {
            commandExecutingClient.close();
            statusUpdatesClient.close();
        } catch (Exception e) {
            log.error("Cannot close client.", e);
        }
    }
}
