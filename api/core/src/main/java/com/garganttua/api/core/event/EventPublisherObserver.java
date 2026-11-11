package com.garganttua.api.core.event;

import java.util.Objects;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservableEvent;

/**
 * Adapts an {@link IEventPublisher} onto the observability stream: it is an
 * {@link IObserver} that, whenever an {@link ObservableEvent} carries an
 * {@link IEvent} payload, forwards it to {@link IEventPublisher#publishEvent}.
 * <p>
 * {@code Domain} subscribes one of these per {@code .events(...)} registration
 * onto its own observable registry at init time, so domain business events flow
 * out through the same fan-out as telemetry — observability is the transport,
 * the {@code IEvent} is the cargo. Observable events without an {@code IEvent}
 * payload (Start markers, workflow {@code stage:*}/{@code script:*} timing) are
 * ignored.
 * <p>
 * Only the operations of ITS domain are forwarded — source
 * {@code api:operation:<domain>:…}. An operation of another domain nested in one of
 * this domain's (a token verification, a use case calling another domain) does not
 * reach this registry today, because the workflow under way holds its own session;
 * the filter makes that a guarantee rather than a consequence of the engines'
 * session layout.
 */
public final class EventPublisherObserver implements IObserver<ObservableEvent> {

	private final IEventPublisher publisher;
	/** {@code api:operation:<domain>:} — the sources of this domain's operations; empty forwards every domain. */
	private final String sourcePrefix;

	/**
	 * A publisher of every business event its registry receives, whatever the domain.
	 *
	 * @param publisher the publisher
	 */
	public EventPublisherObserver(IEventPublisher publisher) {
		this(publisher, null);
	}

	/**
	 * A publisher of one domain's business events.
	 *
	 * @param publisher  the publisher
	 * @param domainName the domain whose operations it receives; null for every domain
	 */
	public EventPublisherObserver(IEventPublisher publisher, String domainName) {
		this.publisher = Objects.requireNonNull(publisher, "Event publisher cannot be null");
		this.sourcePrefix = domainName == null ? "" : "api:operation:" + domainName + ":";
	}

	/** The wrapped publisher — exposed for identity assertions/diagnostics. */
	public IEventPublisher getPublisher() {
		return this.publisher;
	}

	@Override
	public void onEvent(ObservableEvent event) {
		if (event != null && event.payload() instanceof IEvent businessEvent
				&& (this.sourcePrefix.isEmpty() || (event.source() != null && event.source().startsWith(this.sourcePrefix)))) {
			this.publisher.publishEvent(businessEvent);
		}
	}
}
