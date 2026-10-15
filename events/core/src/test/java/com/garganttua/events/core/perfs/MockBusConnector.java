package com.garganttua.events.core.perfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * A bus that costs (almost) nothing, so that what a performance test measures belongs to the engine.
 *
 * <p>
 * Topics are entries of a map. A consumer started on a topic hands its message handler to the test,
 * which feeds the route by calling it — with a sequential dataflow the engine runs the whole route on
 * that call, so its duration IS the route's. A producer on a topic counts what it receives and,
 * when asked to, keeps it; nothing is copied, serialised or queued.
 * </p>
 *
 * <p>
 * The state is static because the engine instantiates connectors itself, from their class; each test
 * calls {@link #reset()} first.
 * </p>
 */
@Connector(type = "mockbus", version = "1.0")
public final class MockBusConnector extends AbstractLifecycle implements IConnector {

	private static final Map<String, Consumer<byte[]>> SUBSCRIBERS = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> PUBLISHED = new ConcurrentHashMap<>();
	private static final Map<String, List<byte[]>> KEPT = new ConcurrentHashMap<>();
	private static volatile boolean keep;

	public MockBusConnector() {
		// public no-arg ctor for the reflective registry path
	}

	/** Forgets every subscriber and every count; {@code keepPayloads} makes producers keep what they get. */
	static void reset(boolean keepPayloads) {
		SUBSCRIBERS.clear();
		PUBLISHED.clear();
		KEPT.clear();
		keep = keepPayloads;
	}

	static void reset() {
		reset(false);
	}

	/** The handler the engine registered on {@code topic}, once its consumer has started. */
	static Optional<Consumer<byte[]>> subscriber(String topic) {
		return Optional.ofNullable(SUBSCRIBERS.get(topic));
	}

	/** How many messages were published on {@code topic}. */
	static long published(String topic) {
		AtomicLong count = PUBLISHED.get(topic);
		return count == null ? 0 : count.get();
	}

	/** What was published on {@code topic}, in arrival order — only when payloads are kept. */
	static List<byte[]> kept(String topic) {
		List<byte[]> kept = KEPT.getOrDefault(topic, List.of());
		synchronized (kept) {
			return List.copyOf(kept);
		}
	}

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	@Override
	public String getName() {
		return "mockbus";
	}

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		// nothing to configure
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		String topic = sub.topic();
		return new IConsumer() {
			@Override
			public void start(Consumer<byte[]> messageHandler) {
				SUBSCRIBERS.put(topic, messageHandler);
			}

			@Override
			public void stop() {
				SUBSCRIBERS.remove(topic);
			}
		};
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		String topic = sub.topic();
		AtomicLong count = PUBLISHED.computeIfAbsent(topic, t -> new AtomicLong());
		List<byte[]> kept = KEPT.computeIfAbsent(topic, t -> Collections.synchronizedList(new ArrayList<>()));
		return new IProducer() {
			@Override
			public void publish(byte[] value) {
				if (keep) {
					kept.add(value);
				}
				count.incrementAndGet();
			}

			@Override
			public void stop() {
				// nothing to release
			}
		};
	}

	@Override
	protected ILifecycle doInit() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStart() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doFlush() throws LifecycleException {
		return this;
	}

	@Override
	protected ILifecycle doStop() throws LifecycleException {
		return this;
	}
}
