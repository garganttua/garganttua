package com.garganttua.events.connectors.websocket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.garganttua.core.observability.Logger;

/**
 * Embedded {@link WebSocketServer} that bridges the org.java-websocket server callbacks into the
 * connector's {@link WebSocketRegistry}.
 *
 * <p>Connections are tracked per channel path (the HTTP resource descriptor of the handshake,
 * minus any query string), and inbound text/binary frames are routed to the consumer handler
 * registered for that path. Both frame types are delivered downstream as {@code byte[]}.</p>
 */
final class EventsWebSocketServer extends WebSocketServer {

    private static final Logger log = Logger.getLogger(EventsWebSocketServer.class);

    private final WebSocketRegistry registry;

    // EI_EXPOSE_REP2: the registry is a shared resource owned and lifecycle-managed by the
    // connector; it is deliberately handed off (not copied) so server, consumers and producers
    // share it.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "registry is a shared, connector-managed resource handed off by reference")
    EventsWebSocketServer(InetSocketAddress address, WebSocketRegistry registry) {
        super(address);
        this.registry = registry;
    }

    private static String path(WebSocket conn) {
        String descriptor = conn.getResourceDescriptor();
        if (descriptor == null) {
            return "/";
        }
        int query = descriptor.indexOf('?');
        return query >= 0 ? descriptor.substring(0, query) : descriptor;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        registry.addSession(path(conn), conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        registry.removeSession(path(conn), conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        registry.dispatch(path(conn), message.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        registry.dispatch(path(conn), bytes);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.warn("WebSocket server error", ex);
    }

    @Override
    public void onStart() {
        log.debug("WebSocket server started");
    }
}
