package org.jboss.pnc.buildagent.server.httpinvoker;

import io.undertow.util.CopyOnWriteMap;

import java.util.Map;
import java.util.Optional;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class SessionRegistry {
    private Map<String, CommandSession> sessions = new CopyOnWriteMap<>();

    public void put(CommandSession commandSession) {
        sessions.put(commandSession.getSessionId(), commandSession);
    }

    public Optional<CommandSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
