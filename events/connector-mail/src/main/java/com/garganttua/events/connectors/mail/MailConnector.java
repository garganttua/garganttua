package com.garganttua.events.connectors.mail;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.IReconfigurableConnector;
import com.garganttua.events.api.IConsumer;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.core.observability.Logger;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.connectors.annotations.Connector;

@Connector(type = "mail")
@Reflected
public class MailConnector extends AbstractLifecycle implements IReconfigurableConnector {

	private static final Logger log = Logger.getLogger(MailConnector.class);

	@Override
	public IReflection reflection() {
		return IClass.getReflection();
	}

	private String name;

	@Override
	public String getName() {
		return this.name;
	}
	/**
	 * The CURRENT configuration, swapped as a whole by {@link #reconfigure}. Producers read it at each mail, so a
	 * reconfiguration reaches the producers already created without restarting the engine.
	 */
	private final AtomicReference<MailSettings> settings = new AtomicReference<>(MailSettings.of(Map.of()));

	@Override
	public void configure(Map<String, String> configuration, ConnectorContext ctx) {
		this.name = configuration.getOrDefault("name", "mail");
		this.settings.set(MailSettings.of(configuration));
	}

	/**
	 * Merges {@code configuration} into the current one — same keys as {@link #configure} (host, port, auth,
	 * starttls, ssl, username, password, from, to, object, body, contentType) — and swaps it atomically. The
	 * producers already created use it from their next mail on; a mail being sent finishes with the previous one.
	 * An invalid result (a port that is not a number in 1..65535) is refused and the current configuration kept.
	 */
	@Override
	public void reconfigure(Map<String, String> configuration) throws ConnectorException {
		if (configuration == null) {
			throw new ConnectorException("Mail connector " + this.name + ": no configuration given");
		}
		try {
			this.settings.updateAndGet(current -> current.merge(configuration));
		} catch (IllegalArgumentException e) {
			throw new ConnectorException("Mail connector " + this.name + " not reconfigured: " + e.getMessage(), e);
		}
		log.info("Mail connector {} reconfigured (keys: {})", this.name, configuration.keySet());
	}

	@Override
	public IConsumer createConsumer(SubscriptionDef sub, DataflowDef df) {
		// Mail connector does not support consumers
		throw new UnsupportedOperationException("Mail connector does not support consumers");
	}

	@Override
	public IProducer createProducer(SubscriptionDef sub, DataflowDef df) {
		MailSender sender = new MailSender(this.settings::get);
		return new MailProducer(sender);
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
