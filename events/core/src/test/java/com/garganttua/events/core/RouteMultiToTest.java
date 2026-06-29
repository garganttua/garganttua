package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.Message;
import com.garganttua.events.api.OutboundTarget;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.core.context.JsonContextReader;
import com.garganttua.events.core.dsl.RouteBuilder;
import com.garganttua.events.expressions.EventExpressions;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit coverage for the multi-{@code .to()} fan-out building blocks: the JSON-tolerant
 * {@link RouteDef#to()} list, the additive {@code RouteBuilder.to(...)} DSL, and the multi-target
 * {@link EventExpressions#produce(Exchange, List, String, String)} broadcast (encapsulating per
 * target, isolating per-target publish failures).
 */
@DisplayName("Route multi-.to() fan-out — model, DSL and produce broadcast")
class RouteMultiToTest {

	/** Fake producer recording every published payload; can be made to fail. */
	private static final class CaptureProducer implements IProducer {
		final List<byte[]> published = new ArrayList<>();
		private final boolean fail;

		CaptureProducer(boolean fail) {
			this.fail = fail;
		}

		@Override
		public void publish(byte[] value) throws ConnectorException {
			if (fail) {
				throw new ConnectorException("boom");
			}
			published.add(value);
		}

		@Override
		public void stop() {
			// no-op
		}
	}

	@Nested
	@DisplayName("RouteDef JSON (string-or-array tolerant)")
	class JsonTolerance {

		@Test
		@DisplayName("\"to\":\"sub\" parses to a one-element list")
		void singleStringToList() throws Exception {
			RouteDef route = readSingleRoute("{\"uuid\":\"r\",\"from\":\"in\",\"to\":\"sub\"}");
			assertEquals(List.of("sub"), route.to());
		}

		@Test
		@DisplayName("\"to\":[\"a\",\"b\"] parses to the two-element list")
		void arrayToList() throws Exception {
			RouteDef route = readSingleRoute("{\"uuid\":\"r\",\"from\":\"in\",\"to\":[\"a\",\"b\"]}");
			assertEquals(List.of("a", "b"), route.to());
		}

		@Test
		@DisplayName("an absent \"to\" yields an empty (never null) list")
		void absentToEmpty() throws Exception {
			RouteDef route = readSingleRoute("{\"uuid\":\"r\",\"from\":\"in\"}");
			assertTrue(route.to().isEmpty(), "absent to → empty list");
		}

		private RouteDef readSingleRoute(String routeJson) throws Exception {
			String ctx = "{\"tenantId\":\"t\",\"clusterId\":\"c\",\"routes\":[" + routeJson + "]}";
			return JsonContextReader.readFromString(ctx).routes().get(0);
		}
	}

	@Nested
	@DisplayName("RouteDef canonical constructor")
	class CanonicalCtor {

		@Test
		@DisplayName("a null to is normalised to an empty list")
		void nullToEmpty() {
			RouteDef route = new RouteDef("r", "in", null, List.of(), null, null);
			assertTrue(route.to().isEmpty(), "null to → empty list (no null guard needed downstream)");
		}
	}

	@Nested
	@DisplayName("RouteBuilder.to() is additive")
	class AdditiveDsl {

		@Test
		@DisplayName("to(a).to(b) accumulates into [a, b]")
		void accumulates() throws Exception {
			RouteDef route = new RouteBuilder("r", "in").to("a").to("b").build();
			assertEquals(List.of("a", "b"), route.to());
		}

		@Test
		@DisplayName("a single to(a) yields [a]")
		void single() throws Exception {
			RouteDef route = new RouteBuilder("r", "in").to("a").build();
			assertEquals(List.of("a"), route.to());
		}
	}

	@Nested
	@DisplayName("produce(Exchange, List<OutboundTarget>, ...) broadcast")
	class ProduceBroadcast {

		@Test
		@DisplayName("publishes to every target; encapsulated gets an envelope, raw gets the value")
		void broadcastsEncapsulatingPerTarget() throws Exception {
			byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
			Exchange exchange = Exchange.create("conn", "topic", "df", raw);

			CaptureProducer plain = new CaptureProducer(false);
			CaptureProducer encap = new CaptureProducer(false);
			OutboundTarget plainTarget = new OutboundTarget(plain, false, "t1", "1", "df1", "c1", "s1");
			OutboundTarget encapTarget = new OutboundTarget(encap, true, "t2", "1", "df2", "c2", "s2");

			EventExpressions.produce(exchange, List.of(plainTarget, encapTarget), "asset", "cluster");

			assertEquals(1, plain.published.size(), "plain target published once");
			assertArrayEquals(raw, plain.published.get(0), "non-encapsulated target gets the raw value");

			assertEquals(1, encap.published.size(), "encapsulated target published once");
			Message envelope = new ObjectMapper().readValue(encap.published.get(0), Message.class);
			assertArrayEquals(raw, envelope.value(), "encapsulated target gets the envelope-wrapped value");
			assertFalse(java.util.Arrays.equals(raw, encap.published.get(0)),
					"the encapsulated bytes are the serialized envelope, not the raw payload");
		}

		@Test
		@DisplayName("a failing target does not stop publication to the others")
		void oneFailureDoesNotAbortOthers() throws Exception {
			Exchange exchange = Exchange.create("conn", "topic", "df",
					"x".getBytes(StandardCharsets.UTF_8));
			CaptureProducer failing = new CaptureProducer(true);
			CaptureProducer healthy = new CaptureProducer(false);

			EventExpressions.produce(exchange,
					List.of(new OutboundTarget(failing, false, "t1", "1", "df1", "c1", "s1"),
							new OutboundTarget(healthy, false, "t2", "1", "df2", "c2", "s2")),
					"asset", "cluster");

			assertEquals(1, healthy.published.size(),
					"the healthy target still receives the exchange despite the other failing");
		}

		@Test
		@DisplayName("an empty target list publishes nothing and returns the exchange")
		void emptyTargets() {
			Exchange exchange = Exchange.create("conn", "topic", "df",
					"x".getBytes(StandardCharsets.UTF_8));
			Exchange result = EventExpressions.produce(exchange, new ArrayList<>(), "asset", "cluster");
			assertEquals(exchange, result, "produce returns the same exchange");
		}
	}

	@Nested
	@DisplayName("AtomicInteger sanity (call count helper)")
	class CallCount {

		@Test
		@DisplayName("a counter increments once per publish")
		void counts() throws Exception {
			AtomicInteger calls = new AtomicInteger();
			IProducer counting = new IProducer() {
				@Override
				public void publish(byte[] value) {
					calls.incrementAndGet();
				}

				@Override
				public void stop() {
					// no-op
				}
			};
			EventExpressions.produce(
					Exchange.create("c", "t", "d", "v".getBytes(StandardCharsets.UTF_8)),
					List.of(new OutboundTarget(counting, false, "t", "1", "df", "c", "s"),
							new OutboundTarget(counting, false, "t", "1", "df", "c", "s2")),
					"a", "cl");
			assertEquals(2, calls.get(), "one publish per target");
		}
	}
}
