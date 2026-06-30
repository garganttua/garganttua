package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.reflection.IClass;
import com.garganttua.events.api.ConnectorContext;
import com.garganttua.events.api.exceptions.ConnectorException;

/**
 * Oracle for the declarative {@code filter} config key on the api connector.
 *
 * <p>It proves a per-domain connector can carry its own {@link IFilter} purely via config: a JSON
 * filter (the exact shape an api-core {@code Filter} serialises to) is parsed by the connector-local
 * {@link JsonFilter}/{@link JsonFilterParser} and evaluated by {@link ApiEventFilter} identically to
 * the original {@code Filter} — round-trip parity — and end to end through the firehose.</p>
 */
class ApiConnectorFilterConfigTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@BeforeAll
	static void installReflection() {
		TestReflection.install();
	}

	private static String serialize(Filter filter) throws Exception {
		return MAPPER.writeValueAsString(filter);
	}

	private static IEvent operationEvent(String tenantId, OperationDefinition operation) {
		FakeEvent event = new FakeEvent();
		event.setTenantId(tenantId);
		event.setCode(OperationResponseCode.OK);
		event.setOperation(operation);
		return event;
	}

	private static OperationDefinition createOp() {
		return OperationDefinition.createOneWithStandardSecurity("contacts", IClass.getClass(FakeEvent.class));
	}

	private static OperationDefinition deleteOp() {
		return OperationDefinition.deleteOneWithStandardSecurity("contacts", IClass.getClass(FakeEvent.class));
	}

	@Nested
	@DisplayName("round-trip parity: serialised Filter JSON parses to an equivalent JsonFilter")
	class RoundTripParity {

		@Test
		@DisplayName("matches() agrees on the original Filter and the parsed JsonFilter (create vs delete)")
		void parsedFilterEvaluatesIdenticallyToTheOriginalFilter() throws Exception {
			Filter original = Filter.in("operation", "create", "update");
			String json = serialize(original);
			IFilter parsed = JsonFilterParser.parse(json);
			assertNotNull(parsed, "the serialised Filter JSON must parse: " + json);

			IEvent create = operationEvent("create-tenant", createOp());
			IEvent delete = operationEvent("delete-tenant", deleteOp());

			// Parity on a forwarded create event...
			assertTrue(ApiEventFilter.matches(original, create), "original keeps create");
			assertEquals(ApiEventFilter.matches(original, create), ApiEventFilter.matches(parsed, create),
					"create: parsed must agree with original");
			// ...and on a dropped delete event.
			assertFalse(ApiEventFilter.matches(original, delete), "original drops delete");
			assertEquals(ApiEventFilter.matches(original, delete), ApiEventFilter.matches(parsed, delete),
					"delete: parsed must agree with original");
		}

		@Test
		@DisplayName("the JSON the user pastes carries name/value/literals — the Filter shape")
		void serialisedShapeIsNameValueLiterals() throws Exception {
			String json = serialize(Filter.eq("operation", "create"));
			assertTrue(json.contains("\"name\""), "shape should carry name: " + json);
			assertTrue(json.contains("\"value\""), "shape should carry value: " + json);
			assertTrue(json.contains("\"literals\""), "shape should carry literals: " + json);
		}
	}

	@Nested
	@DisplayName("nested boolean filters parse and evaluate")
	class NestedBoolean {

		@Test
		@DisplayName("$and / $or filter JSON parses and evaluates correctly")
		void nestedAndOrEvaluates() throws Exception {
			// (operation == create) OR (tenantId == keep-me) — both branches must survive the round trip.
			Filter original = Filter.or(Filter.eq("operation", "create"), Filter.eq("tenantId", "keep-me"));
			IFilter parsed = JsonFilterParser.parse(serialize(original));
			assertNotNull(parsed);

			IEvent create = operationEvent("other-tenant", createOp());
			IEvent keptByTenant = operationEvent("keep-me", deleteOp());
			IEvent neither = operationEvent("nope", deleteOp());

			assertEquals(ApiEventFilter.matches(original, create), ApiEventFilter.matches(parsed, create));
			assertEquals(ApiEventFilter.matches(original, keptByTenant), ApiEventFilter.matches(parsed, keptByTenant));
			assertEquals(ApiEventFilter.matches(original, neither), ApiEventFilter.matches(parsed, neither));

			assertTrue(ApiEventFilter.matches(parsed, create), "create branch matches");
			assertTrue(ApiEventFilter.matches(parsed, keptByTenant), "tenant branch matches");
			assertFalse(ApiEventFilter.matches(parsed, neither), "neither branch matches → dropped");
		}
	}

	@Nested
	@DisplayName("malformed / absent filter config")
	class MalformedConfig {

		@Test
		@DisplayName("malformed filter JSON is ignored: configure() does not throw, pass-all applies")
		void malformedFilterIsIgnored() throws Exception {
			ApiEventsConnector connector = new ApiEventsConnector();
			// Must not throw.
			connector.configure(Map.of("filter", "{ this is not json"), ctx());
			assertForwardsBoth(connector, "malformed JSON → no filtering");
		}

		@Test
		@DisplayName("blank/empty JSON object yields no filter (pass-all)")
		void emptyObjectIsPassAll() throws Exception {
			assertNull(JsonFilterParser.parse("{}"), "empty object is not a filter");
			assertNull(JsonFilterParser.parse("   "), "blank is not a filter");
			assertNull(JsonFilterParser.parse((String) null), "null is not a filter");
		}
	}

	@Nested
	@DisplayName("configure(filter=...) end to end + precedence")
	class ConfigureEndToEnd {

		@Test
		@DisplayName("config filter forwards a create and drops a delete via the firehose")
		void configFilterFiltersEndToEnd() throws Exception {
			String json = serialize(Filter.in("operation", "create", "update", "readAll"));
			ApiEventsConnector connector = new ApiEventsConnector();
			connector.configure(Map.of("filter", json), ctx());

			RunningConsumer running = startConsumer(connector);

			fire("delete-tenant", deleteOp(), "api:operation:contacts:delete-one-contact");
			fire("create-tenant", createOp(), "api:operation:contacts:create-one-contact");

			assertTrue(running.gotMessage.await(5, TimeUnit.SECONDS), "the create event should be forwarded");
			String forwarded = new String(running.received.get(), StandardCharsets.UTF_8);
			assertTrue(forwarded.contains("create-tenant"), "must forward create: " + forwarded);
			assertFalse(forwarded.contains("delete-tenant"), "must drop delete: " + forwarded);

			running.stop();
		}

		@Test
		@DisplayName("programmatic filter wins over the config filter key")
		void programmaticFilterTakesPrecedence() throws Exception {
			// Programmatic filter keeps only deletes; the config filter (keeps creates) must be ignored.
			Filter programmatic = Filter.in("operation", "deleteOne");
			String configJson = serialize(Filter.in("operation", "create"));

			ApiEventsConnector connector = new ApiEventsConnector().filter(programmatic);
			connector.configure(Map.of("filter", configJson), ctx());

			RunningConsumer running = startConsumer(connector);

			// The create event the CONFIG filter would keep must be dropped (programmatic wins)...
			fire("create-tenant", createOp(), "api:operation:contacts:create-one-contact");
			// ...and the delete event the PROGRAMMATIC filter keeps must be forwarded.
			fire("delete-tenant", deleteOp(), "api:operation:contacts:delete-one-contact");

			assertTrue(running.gotMessage.await(5, TimeUnit.SECONDS), "the delete event should be forwarded");
			String forwarded = new String(running.received.get(), StandardCharsets.UTF_8);
			assertTrue(forwarded.contains("delete-tenant"), "programmatic filter must forward delete: " + forwarded);
			assertFalse(forwarded.contains("create-tenant"), "programmatic filter must drop create: " + forwarded);

			running.stop();
		}
	}

	// --- helpers -------------------------------------------------------------------------------

	private static ConnectorContext ctx() {
		return new ConnectorContext("asset", "tenant", "cluster");
	}

	private static RunningConsumer startConsumer(ApiEventsConnector connector) throws InterruptedException {
		var consumer = connector.createConsumer(null, null);
		AtomicReference<byte[]> received = new AtomicReference<>();
		CountDownLatch gotMessage = new CountDownLatch(1);
		Thread worker = new Thread(() -> {
			try {
				consumer.start(bytes -> {
					received.compareAndSet(null, bytes);
					gotMessage.countDown();
				});
			} catch (ConnectorException e) {
				fail(e);
			}
		});
		worker.setDaemon(true);
		worker.start();
		// brief settle so the worker's GlobalObservers.addObserver(...) has run
		TimeUnit.MILLISECONDS.sleep(100);
		return new RunningConsumer(consumer, worker, received, gotMessage);
	}

	private static void fire(String tenantId, OperationDefinition operation, String source) {
		GlobalObservers.fire(new EndEvent(UUID.randomUUID(), Instant.now(), source,
				Duration.ofMillis(2), 0, operationEvent(tenantId, operation)));
	}

	private void assertForwardsBoth(ApiEventsConnector connector, String why) throws Exception {
		RunningConsumer running = startConsumer(connector);
		fire("delete-tenant", deleteOp(), "api:operation:contacts:delete-one-contact");
		assertTrue(running.gotMessage.await(5, TimeUnit.SECONDS),
				why + " — a delete event should pass when unfiltered");
		running.stop();
	}

	/** A started consumer plus the harness needed to observe and stop it. */
	private static final class RunningConsumer {
		private final com.garganttua.events.api.IConsumer consumer;
		private final Thread worker;
		private final AtomicReference<byte[]> received;
		private final CountDownLatch gotMessage;

		RunningConsumer(com.garganttua.events.api.IConsumer consumer, Thread worker,
				AtomicReference<byte[]> received, CountDownLatch gotMessage) {
			this.consumer = consumer;
			this.worker = worker;
			this.received = received;
			this.gotMessage = gotMessage;
		}

		void stop() throws Exception {
			this.consumer.stop();
			this.worker.join(5_000);
		}
	}
}
