package com.garganttua.events.connectors.websocket;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * WebSocket consumer for one channel path, in either topology.
 *
 * <p><b>Server mode</b> registers the message handler against the connector's
 * {@link WebSocketRegistry} for its path; inbound frames are delivered by the embedded server
 * thread. <b>Client mode</b> attaches the handler to a shared {@link WebSocketClientEndpoint} and
 * opens the connection if needed.</p>
 *
 * <p>In both modes {@link #start} honours the blocking {@link IConsumer} contract by parking until
 * {@link #stop}; frame delivery is push-based via callbacks, so no polling occurs.</p>
 */
final class WebSocketConsumer implements IConsumer {

    private static final long PARK_MILLIS = 100;

    private final WebSocketRegistry registry;
    private final String path;
    private final WebSocketClientEndpoint client;
    private final long connectTimeoutMs;
    private volatile boolean running;

    // EI_EXPOSE_REP2: registry is a shared, connector-managed resource handed off by reference.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "registry is a shared, connector-managed resource handed off by reference")
    WebSocketConsumer(WebSocketRegistry registry, String path) {
        this.registry = registry;
        this.path = path;
        this.client = null;
        this.connectTimeoutMs = 0;
    }

    // EI_EXPOSE_REP2: client is a shared, connector-managed socket handed off by reference.
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "client is a shared, connector-managed socket handed off by reference")
    WebSocketConsumer(WebSocketClientEndpoint client, long connectTimeoutMs) {
        this.registry = null;
        this.path = null;
        this.client = client;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void start(Consumer<byte[]> messageHandler) throws ConnectorException {
        this.running = true;
        if (registry != null) {
            registry.registerHandler(path, messageHandler);
        } else {
            client.onFrame(messageHandler);
            connectClientIfNeeded();
        }
        // Park until stop() — frames are delivered by the server/client callbacks.
        while (running) {
            try {
                Thread.sleep(PARK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
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
        this.running = false;
        if (registry != null) {
            registry.unregisterHandler(path);
        }
    }
}
