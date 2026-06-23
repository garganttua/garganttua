package com.garganttua.events.connectors.observability;

import java.util.Map;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Connector that observes the garganttua observability firehose
 * ({@link com.garganttua.core.observability.GlobalObservers}) and forwards every
 * {@link com.garganttua.core.observability.ObservableEvent} into the events pipeline — with
 * <b>zero application wiring</b>: the consumer self-registers, no observable has to be exposed.
 *
 * <p>Configuration keys:</p>
 * <ul>
 *   <li>{@code name} — connector name (default {@code observability}).</li>
 *   <li>{@code events} — optional comma list among {@code start,end,error,log} selecting which
 *       event types to forward (default: all).</li>
 *   <li>{@code sourcePattern} — optional glob matched against {@code event.source()}
 *       (default: any).</li>
 * </ul>
 *
 * <p>This connector is <b>read-only</b>: {@link #createProducer(SubscriptionDef, DataflowDef)}
 * returns a producer whose {@code publish} throws.</p>
 */
@Connector(type = "observability")
@Reflected
public class ObservabilityConnector extends AbstractLifecycle implements IConnector {

	private static final Logger LOG = Logger.getLogger(ObservabilityConnector.class);

	private String name = "observability";
	private String events;
	private String sourcePattern;

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.name = configuration.getOrDefault("name", "observability");
		this.events = configuration.get("events");
		this.sourcePattern = configuration.get("sourcePattern");
		LOG.debug("Configured observability connector {}", this.name);
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		EventFilter filter = EventFilter.of(this.events, this.sourcePattern);
		return new ObservabilityConsumer(filter);
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		return new ReadOnlyProducer();
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
