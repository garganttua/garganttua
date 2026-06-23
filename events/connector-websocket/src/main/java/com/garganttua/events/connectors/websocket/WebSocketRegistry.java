package com.garganttua.events.connectors.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.java_websocket.WebSocket;

import com.garganttua.core.observability.Logger;

/**
 * Server-mode shared state for the {@link WebSocketConnector}: the live sessions and the consumer
 * message handlers, both keyed by channel path.
 *
 * <p>A single {@link EventsWebSocketServer} feeds this registry. Consumers register one handler per
 * path ({@link #registerHandler}); inbound frames are routed to it ({@link #dispatch}). Producers
 * fan a frame out to every live session on a path ({@link #broadcast}) — the pub/sub contract:
 * produce-to-topic = broadcast to all sessions of that channel. Shared by reference between the
 * connector, its consumers and its producers.</p>
 */
final class WebSocketRegistry {

    private static final Logger log = Logger.getLogger(WebSocketRegistry.class);

    private final Map<String, Set<WebSocket>> sessionsByPath = new ConcurrentHashMap<>();
    private final Map<String, Consumer<byte[]>> handlersByPath = new ConcurrentHashMap<>();

    void addSession(String path, WebSocket conn) {
        sessionsByPath.computeIfAbsent(path, p -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    void removeSession(String path, WebSocket conn) {
        Set<WebSocket> sessions = sessionsByPath.get(path);
        if (sessions != null) {
            sessions.remove(conn);
        }
    }

    void registerHandler(String path, Consumer<byte[]> handler) {
        handlersByPath.put(path, handler);
    }

    void unregisterHandler(String path) {
        handlersByPath.remove(path);
    }

    /** Routes an inbound frame to the consumer handler registered for {@code path}, if any. */
    void dispatch(String path, byte[] payload) {
        Consumer<byte[]> handler = handlersByPath.get(path);
        if (handler != null) {
            handler.accept(payload);
        } else {
            log.debug("No consumer handler for path {} - frame dropped", path);
        }
    }

    /** Fans a frame out to every open session subscribed on {@code path}. */
    void broadcast(String path, byte[] payload, boolean textFrames) {
        Set<WebSocket> sessions = sessionsByPath.get(path);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No sessions on path {} - nothing to broadcast", path);
            return;
        }
        for (WebSocket conn : sessions) {
            if (conn.isOpen()) {
                if (textFrames) {
                    conn.send(new String(payload, StandardCharsets.UTF_8));
                } else {
                    conn.send(payload);
                }
            }
        }
    }
}
