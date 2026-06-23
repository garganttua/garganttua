package com.garganttua.events.connectors.observability;

import java.util.Map;

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
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;

/**
 * Connector that observes a garganttua {@link com.garganttua.core.observability.IObservable} and
 * forwards its {@link com.garganttua.core.observability.ObservableEvent}s into the events pipeline.
 *
 * <p>Configuration keys:</p>
 * <ul>
 *   <li>{@code name} — connector name (default {@code observability}).</li>
 *   <li>{@code source} — the {@link ObservableSources} registry key of the observable to observe
 *       (required at consumer start).</li>
 *   <li>{@code events} — optional comma list among {@code start,end,error,log} selecting which
 *       event types to forward (default: all).</li>
 *   <li>{@code sourcePattern} — optional glob matched against {@code event.source()}
 *       (default: any).</li>
 * </ul>
 *
 * <p>This connector is <b>read-only</b>: {@link #createProducer(SubscriptionDef, DataflowDef)}
 * returns a producer whose {@code publish} throws.</p>
 */
@Reflected
public class ObservabilityConnector extends AbstractLifecycle implements IConnector {

	private static final Logger LOG = Logger.getLogger(ObservabilityConnector.class);

	private String name = "observability";
	private String source;
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
		this.source = configuration.get("source");
		this.events = configuration.get("events");
		this.sourcePattern = configuration.get("sourcePattern");
		LOG.debug("Configured observability connector {} on source {}", this.name, this.source);
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		EventFilter filter = EventFilter.of(this.events, this.sourcePattern);
		return new ObservabilityConsumer(this.source, filter);
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
