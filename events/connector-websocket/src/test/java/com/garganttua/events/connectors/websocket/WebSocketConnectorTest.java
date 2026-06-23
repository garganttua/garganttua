package com.garganttua.events.connectors.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Round-trip integration test for the server-mode {@link WebSocketConnector}: boots the embedded
 * server, connects a real WebSocket client, and asserts both directions — an inbound frame reaches
 * the consumer handler, and a produced frame is broadcast back to the connected client.
 */
class WebSocketConnectorTest {

    private static final String PATH = "/orders";
    private static final long TIMEOUT_MS = 2000;

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SubscriptionDef subscription() {
        return new SubscriptionDef("sub-id", "df", PATH, "websocket", null, null, null, null);
    }

    private static DataflowDef dataflow() {
        return new DataflowDef("df-uuid", "df", "type", false, "1", false);
    }

    @Test
    @DisplayName("server mode: consumes an inbound frame and broadcasts a produced frame back")
    void serverModeRoundTrip() throws Exception {
        int port = freePort();
        WebSocketConnector connector = new WebSocketConnector();
        connector.configure(Map.of("mode", "server", "ws.port", String.valueOf(port)),
                new ConnectorContext("asset", "tenant", "cluster"));
        connector.onInit().onStart();

        BlockingQueue<byte[]> consumed = new LinkedBlockingQueue<>();
        IConsumer consumer = connector.createConsumer(subscription(), dataflow());
        Thread consumerThread = new Thread(() -> {
            try {
                consumer.start(consumed::add);
            } catch (Exception ignored) {
                // start() returns when stop() flips the running flag.
            }
        }, "ws-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        BlockingQueue<String> clientReceived = new LinkedBlockingQueue<>();
        WebSocketClient client = new WebSocketClient(new URI("ws://localhost:" + port + PATH)) {
            @Override public void onOpen(ServerHandshake handshake) { /* no-op */ }
            @Override public void onMessage(String message) { clientReceived.add(message); }
            @Override public void onClose(int code, String reason, boolean remote) { /* no-op */ }
            @Override public void onError(Exception ex) { /* no-op */ }
        };
        assertTrue(client.connectBlocking(TIMEOUT_MS, TimeUnit.MILLISECONDS), "client should connect");

        try {
            // Inbound: client -> connector consumer handler. Re-send until the consumer thread has
            // registered its handler (registration races with the client connect).
            byte[] inbound = null;
            for (int attempt = 0; attempt < 20 && inbound == null; attempt++) {
                client.send("hello");
                inbound = consumed.poll(100, TimeUnit.MILLISECONDS);
            }
            assertNotNull(inbound, "consumer should receive the inbound frame");
            assertEquals("hello", new String(inbound, StandardCharsets.UTF_8));

            // Outbound: producer broadcast -> connected client. The session is registered (we just
            // received a frame through it), so a single publish suffices.
            IProducer producer = connector.createProducer(subscription(), dataflow());
            producer.publish("world".getBytes(StandardCharsets.UTF_8));
            String outbound = clientReceived.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            assertEquals("world", outbound, "client should receive the broadcast frame");

            producer.stop();
        } finally {
            consumer.stop();
            consumerThread.interrupt();
            client.closeBlocking();
            connector.onStop();
        }
    }
}
