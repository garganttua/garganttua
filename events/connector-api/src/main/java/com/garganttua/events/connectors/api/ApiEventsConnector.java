package com.garganttua.events.connectors.api;

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
 * ({@link com.garganttua.core.observability.GlobalObservers}) and forwards api business
 * {@link com.garganttua.api.commons.event.IEvent}s into the events pipeline — with <b>zero
 * application wiring</b>: the consumer self-registers, no {@code Domain} has to be exposed.
 *
 * <p>Configuration keys:</p>
 * <ul>
 *   <li>{@code name} — connector name (default {@code api-events}).</li>
 *   <li>{@code domain} — optional domain filter; only events whose source domain segment
 *       ({@code api:operation:<domain>:<op>}) equals this value are forwarded (default: all
 *       domains). This is the discriminator that lets two dataflows react to the same operation on
 *       different domains (e.g. {@code create} on {@code contacts} vs {@code newsletters}).</li>
 *   <li>{@code operations} — optional operation-key filter; only events whose source operation
 *       segment matches this value are forwarded (default: all operations).</li>
 * </ul>
 *
 * <p>For finer discrimination (technical verb, business operation, use case) the forwarded JSON
 * payload is self-describing — {@link ApiEventCodec} emits {@code domain}, {@code businessOperation}
 * and {@code useCase} fields, so a dataflow transform stage can route on them too.</p>
 *
 * <p>This connector is <b>read-only</b>: {@link #createProducer(SubscriptionDef, DataflowDef)}
 * returns a producer whose {@code publish} throws.</p>
 */
@Connector(type = "api")
@Reflected
public class ApiEventsConnector extends AbstractLifecycle implements IConnector {

	private static final Logger LOG = Logger.getLogger(ApiEventsConnector.class);

	private String name = "api-events";
	private String operations;
	private String domain;

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
		this.name = configuration.getOrDefault("name", "api-events");
		this.operations = configuration.get("operations");
		this.domain = configuration.get("domain");
		LOG.debug("Configured api events connector {} (domain={}, operations={})",
				this.name, this.domain, this.operations);
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		return new ApiEventsConsumer(this.operations, this.domain);
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
