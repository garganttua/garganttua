package com.garganttua.events.connectors.websocket;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.garganttua.core.observability.Logger;

/**
 * Client-mode socket: a single outbound WebSocket connection to one channel URL, shared between the
 * consumer and the producer of the same topic so a single bidirectional socket carries both
 * directions.
 *
 * <p>Inbound text/binary frames are forwarded as {@code byte[]} to the registered consumer handler
 * ({@link #onFrame}); the producer sends outbound frames on the same connection.</p>
 */
final class WebSocketClientEndpoint extends WebSocketClient {

    private static final Logger log = Logger.getLogger(WebSocketClientEndpoint.class);

    private volatile Consumer<byte[]> handler;

    WebSocketClientEndpoint(URI serverUri) {
        super(serverUri);
    }

    /** Registers (or replaces) the handler invoked for every inbound frame. */
    void onFrame(Consumer<byte[]> handler) {
        this.handler = handler;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        log.debug("WebSocket client connected to {}", getURI());
    }

    @Override
    public void onMessage(String message) {
        Consumer<byte[]> current = this.handler;
        if (current != null) {
            current.accept(message.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        Consumer<byte[]> current = this.handler;
        if (current != null) {
            byte[] payload = new byte[bytes.remaining()];
            bytes.get(payload);
            current.accept(payload);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.debug("WebSocket client closed: {} {}", code, reason);
    }

    @Override
    public void onError(Exception ex) {
        log.warn("WebSocket client error", ex);
    }
}
