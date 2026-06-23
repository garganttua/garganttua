package com.garganttua.events.connectors.websocket;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * WebSocket producer for one channel path, in either topology.
 *
 * <p><b>Server mode</b> broadcasts each frame to every live session on its path via the connector's
 * {@link WebSocketRegistry} (pub/sub fan-out). <b>Client mode</b> sends the frame on the shared
 * outbound {@link WebSocketClientEndpoint}, opening it on first use. Frames are sent as UTF-8 text
 * or binary according to the connector's {@code frameFormat}.</p>
 */
final class WebSocketProducer implements IProducer {

    private final WebSocketRegistry registry;
    private final String path;
    private final WebSocketClientEndpoint client;
    private final boolean textFrames;
    private final long connectTimeoutMs;

    // EI_EXPOSE_REP2: registry is a shared, connector-managed resource handed off by reference.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "registry is a shared, connector-managed resource handed off by reference")
    WebSocketProducer(WebSocketRegistry registry, String path, boolean textFrames) {
        this.registry = registry;
        this.path = path;
        this.client = null;
        this.textFrames = textFrames;
        this.connectTimeoutMs = 0;
    }

    // EI_EXPOSE_REP2: client is a shared, connector-managed socket handed off by reference.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "client is a shared, connector-managed socket handed off by reference")
    WebSocketProducer(WebSocketClientEndpoint client, boolean textFrames, long connectTimeoutMs) {
        this.registry = null;
        this.path = null;
        this.client = client;
        this.textFrames = textFrames;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void publish(byte[] value) throws ConnectorException {
        if (registry != null) {
            registry.broadcast(path, value, textFrames);
            return;
        }
        connectClientIfNeeded();
        if (textFrames) {
            client.send(new String(value, StandardCharsets.UTF_8));
        } else {
            client.send(value);
        }
    }

    private void connectClientIfNeeded() throws ConnectorException {
        try {
            if (!client.isOpen()) {
                client.connectBlocking(connectTimeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException(e);
        }
    }

    @Override
    public void stop() throws ConnectorException {
        // Sockets are owned and closed by the connector.
    }
}
