package com.garganttua.events.connectors.api;

import java.util.Map;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.api.commons.filter.IFilter;
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
 *   <li>{@code filter} — optional declarative {@link IFilter} as JSON (the exact shape api-core
 *       {@code Filter} serialises to: {@code name}/{@code value}/{@code literals}). When present and
 *       non-blank it is parsed by {@link JsonFilterParser} and applied per business event, so each
 *       per-domain connector can declare its own filter in the topology config with no application
 *       code and no api-core dependency on the main classpath. The programmatic
 *       {@link #filter(IFilter)} setter, if already called, <b>takes precedence</b> over this key; a
 *       malformed {@code filter} JSON is logged at warn and ignored (pass-all), never aborts
 *       {@code configure}.</li>
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
 * <p><b>Programmatic {@link IFilter} filtering.</b> Beyond the string {@code domain}/{@code operations}
 * config keys, an application can build a rich {@link IFilter} with the api-core {@code Filter}
 * factories (e.g. {@code Filter.in("operation","create","update","readAll")}) and set it on the
 * connector <i>instance</i> via {@link #filter(IFilter)} before registering that instance with the
 * events DSL {@code connector(IConnector)} direct-object path. The engine then uses the pre-configured
 * instance, so the filter is honoured per business event ({@link ApiEventFilter}). The filter is
 * <b>additional and optional</b>: a {@code null} filter preserves the existing all-pass behaviour, and
 * the {@code domain}/{@code operations} source filtering keeps working alongside it.</p>
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
	private IFilter eventFilter;
	private boolean programmaticFilterSet;

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	/**
	 * Sets the optional application-built {@link IFilter} this connector applies to every forwarded
	 * business event, on top of the {@code domain}/{@code operations} source filtering.
	 *
	 * <p>An explicit (even {@code null}) programmatic filter <b>takes precedence</b> over a
	 * {@code filter} config key: once this setter is called, {@link #configure} will not override the
	 * filter from config.</p>
	 *
	 * @param filter the filter tree to apply, or {@code null} to forward all matching events
	 * @return this connector, for fluent chaining
	 */
	public ApiEventsConnector filter(IFilter filter) {
		// Defensive copy: the filter tree is mutable, so we snapshot it to stay immune to
		// post-registration mutation by the application.
		this.eventFilter = ApiEventFilter.snapshot(filter);
		this.programmaticFilterSet = true;
		return this;
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
		configureFilter(configuration.get("filter"));
		LOG.debug("Configured api events connector {} (domain={}, operations={})",
				this.name, this.domain, this.operations);
	}

	/**
	 * Applies the declarative {@code filter} config key. A programmatic {@link #filter(IFilter)}
	 * wins, so config is ignored once the setter has been called. A malformed or empty JSON yields a
	 * {@code null} filter (pass-all) and never throws.
	 *
	 * @param json the raw {@code filter} config value, may be {@code null}/blank/malformed
	 */
	private void configureFilter(String json) {
		if (this.programmaticFilterSet) {
			LOG.debug("Programmatic filter set on api events connector {}; ignoring config filter key",
					this.name);
			return;
		}
		if (json == null || json.isBlank()) {
			return;
		}
		this.eventFilter = ApiEventFilter.snapshot(JsonFilterParser.parse(json));
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		return new ApiEventsConsumer(this.operations, this.domain, this.eventFilter);
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
