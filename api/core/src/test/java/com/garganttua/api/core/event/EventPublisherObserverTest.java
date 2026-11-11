package com.garganttua.api.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.StartEvent;

/** What reaches a publisher declared on a domain with {@code .events(...)}. */
@DisplayName("EventPublisherObserver — a domain's business events, and only those")
class EventPublisherObserverTest {

	private static EndEvent end(String source, IEvent payload) {
		return new EndEvent(UUID.randomUUID(), Instant.now(), source, Duration.ZERO, 0, payload);
	}

	@Test
	@DisplayName("its domain's ends and errors are forwarded; another domain's, and events without an IEvent, are not")
	void ownDomainOnly() {
		List<IEvent> received = new ArrayList<>();
		EventPublisherObserver observer = new EventPublisherObserver(received::add, "users");
		Event own = new Event();
		Event nested = new Event();
		Event failed = new Event();

		observer.onEvent(end("api:operation:users:users-read-all-users", own));
		observer.onEvent(end("api:operation:sessiontokens:sessiontokens-read-all-sessiontokens", nested));
		observer.onEvent(new ErrorEvent(UUID.randomUUID(), Instant.now(), "api:operation:users:users-create-one-user",
				Duration.ZERO, new IllegalStateException("x"), failed));
		observer.onEvent(new StartEvent(UUID.randomUUID(), Instant.now(), "api:operation:users:users-read-all-users", null));
		observer.onEvent(end("api:operation:usersarchive:usersarchive-read-all-usersarchives", nested));

		assertEquals(List.of(own, failed), received, "a prefix of another domain's name is not the domain");
	}

	@Test
	@DisplayName("without a domain, every business event of the registry is forwarded, as before")
	void anyDomain() {
		List<IEvent> received = new ArrayList<>();
		EventPublisherObserver observer = new EventPublisherObserver(received::add);
		Event nested = new Event();

		observer.onEvent(end("api:operation:sessiontokens:sessiontokens-read-all-sessiontokens", nested));

		assertEquals(List.of(nested), received);
	}
}
