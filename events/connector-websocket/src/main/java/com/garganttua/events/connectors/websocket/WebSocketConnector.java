package com.garganttua.events.connectors.websocket;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Bidirectional WebSocket connector (consumer + producer) with pub/sub semantics: each
 * {@code topic} maps to a WebSocket channel path.
 *
 * <p>Two topologies are selected by the {@code mode} configuration key:</p>
 * <ul>
 *   <li><b>server</b> — hosts an embedded {@link EventsWebSocketServer} on {@code ws.host:ws.port};
 *       clients connect in, and producing to a topic fans the frame out to every session on that
 *       channel path. Targeting one client stays in pub/sub by giving it its own channel
 *       (e.g. {@code /user/{id}}).</li>
 *   <li><b>client</b> — opens an outbound connection to {@code ws.url + topic}; one shared socket
 *       per topic carries both consumed and produced frames.</li>
 * </ul>
 *
 * <p>Frames are sent as UTF-8 text by default ({@code frameFormat=text}) or binary
 * ({@code frameFormat=binary}); inbound text and binary frames are both delivered as
 * {@code byte[]}.</p>
 *
 * <p>Configuration keys: {@code mode} (server|client, default server), {@code ws.host}
 * (default 0.0.0.0), {@code ws.port} (required in server mode), {@code ws.url} (base URL in client
 * mode), {@code frameFormat} (text|binary, default text), {@code connectTimeoutMs} (client connect
 * timeout, default 5000), {@code name} (connector name, default websocket).</p>
 */
@Connector(type = "websocket")
@Reflected
public class WebSocketConnector extends AbstractLifecycle implements IConnector {

    private static final Logger log = Logger.getLogger(WebSocketConnector.class);

    @Override
    public IReflection reflection() {
        return IClass.getReflection();
    }

    private String name = "websocket";
    private String mode = "server";
    private String host = "0.0.0.0";
    private int port = -1;
    private String url;
    private boolean textFrames = true;
    private long connectTimeoutMs = 5000;

    // Server mode: one embedded server feeding the shared registry.
    private final WebSocketRegistry registry = new WebSocketRegistry();
    private EventsWebSocketServer server;

    // Client mode: one shared outbound socket per topic (consumer + producer share it).
    private final Map<String, WebSocketClientEndpoint> clients = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void configure(Map<String, String> configuration, ConnectorContext ctx) {
        this.name = configuration.getOrDefault("name", "websocket");
        this.mode = configuration.getOrDefault("mode", "server");
        this.host = configuration.getOrDefault("ws.host", "0.0.0.0");
        if (configuration.containsKey("ws.port")) {
            this.port = Integer.parseInt(configuration.get("ws.port"));
        }
        this.url = configuration.get("ws.url");
        this.textFrames = !"binary".equalsIgnoreCase(configuration.getOrDefault("frameFormat", "text"));
        if (configuration.containsKey("connectTimeoutMs")) {
            this.connectTimeoutMs = Long.parseLong(configuration.get("connectTimeoutMs"));
        }
    }

    private boolean serverMode() {
        return "server".equalsIgnoreCase(this.mode);
    }

    private static String normalizePath(String topic) {
        if (topic == null || topic.isEmpty()) {
            return "/";
        }
        return topic.startsWith("/") ? topic : "/" + topic;
    }

    private WebSocketClientEndpoint getOrCreateClient(String topic) {
        return clients.computeIfAbsent(topic, t -> {
            try {
                return new WebSocketClientEndpoint(new URI(this.url + normalizePath(t)));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid WebSocket URI for topic " + t, e);
            }
        });
    }

    @Override
    public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
        if (serverMode()) {
            return new WebSocketConsumer(registry, normalizePath(sub.topic()));
        }
        return new WebSocketConsumer(getOrCreateClient(sub.topic()), connectTimeoutMs);
    }

    @Override
    public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
        if (serverMode()) {
            return new WebSocketProducer(registry, normalizePath(sub.topic()), textFrames);
        }
        return new WebSocketProducer(getOrCreateClient(sub.topic()), textFrames, connectTimeoutMs);
    }

    @Override
    protected ILifecycle doInit() throws LifecycleException {
        return this;
    }

    @Override
    protected ILifecycle doStart() throws LifecycleException {
        if (serverMode()) {
            if (port < 0) {
                throw new LifecycleException("WebSocket server mode requires 'ws.port'");
            }
            this.server = new EventsWebSocketServer(new InetSocketAddress(host, port), registry);
            this.server.start();
            log.info("WebSocket connector '{}' listening on {}:{}", name, host, port);
        }
        return this;
    }

    @Override
    protected ILifecycle doFlush() throws LifecycleException {
        clients.clear();
        return this;
    }

    @Override
    protected ILifecycle doStop() throws LifecycleException {
        if (server != null) {
            try {
                server.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (WebSocketClientEndpoint client : clients.values()) {
            client.close();
        }
        return this;
    }
}
