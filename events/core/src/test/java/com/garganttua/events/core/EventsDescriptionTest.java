package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;

/**
 * Tests the route description rendering ({@link Events#describeRoute}/{@link Events#describeRoutes})
 * and the bootstrap summary contribution ({@link Events#getSummaryCategory}/{@link Events#getSummaryItems})
 * of a hand-built {@link Events} engine.
 */
@DisplayName("Events route description and bootstrap summary")
class EventsDescriptionTest {

	private static Events buildEngine() {
		TopicDef inTopic = new TopicDef("orders.in");
		TopicDef outTopic = new TopicDef("orders.out");
		ConnectorDef kafka = new ConnectorDef("kafka1", "kafka", "1.0", Map.of());
		DataflowDef orders = new DataflowDef("df-uuid-1", "orders", "kafka", true, "1", false);
		SubscriptionDef in = new SubscriptionDef("in", "df-uuid-1", "orders.in", "kafka1",
				null, null, null, null);
		SubscriptionDef out = new SubscriptionDef("out", "df-uuid-1", "orders.out", "kafka1",
				null, null, null, null);
		RouteStageDef stage = new RouteStageDef("filter", "filter_in()", null, null, null);
		RouteDef route = new RouteDef("r1", "in", "out", List.of(stage), null, null);
		ContextDef context = new ContextDef("default", "main",
				List.of(inTopic, outTopic), List.of(orders), List.of(kafka),
				List.of(in, out), List.of(route), null);
		return new Events("asset-1", List.of(context), Map.of(), null, null);
	}

	@Nested
	@DisplayName("describeRoute")
	class DescribeRoute {

		@Test
		@DisplayName("renders topic, connector type:version, dataflow and stages")
		void rendersRouteDetails() {
			String rendered = buildEngine().describeRoute("r1");

			assertTrue(rendered.contains("orders.in"), "should contain the from topic ref");
			assertTrue(rendered.contains("orders.out"), "should contain the to topic ref");
			assertTrue(rendered.contains("kafka:1.0"), "should contain connector type:version");
			assertTrue(rendered.contains("'orders'"), "should contain dataflow name");
			assertTrue(rendered.contains("filter_in()"), "should contain the stage expression");
			assertTrue(rendered.contains("tenant=default"), "should contain the cluster tenant");
			assertTrue(rendered.contains("cluster=main"), "should contain the cluster id");
		}

		@Test
		@DisplayName("returns a not-found message for an unknown route")
		void notFound() {
			String rendered = buildEngine().describeRoute("missing");
			assertTrue(rendered.contains("not found"), "should signal not found");
			assertTrue(rendered.contains("missing"), "should echo the requested uuid");
		}

		@Test
		@DisplayName("renders <unresolved: ...> for a dangling reference")
		void unresolvedReference() {
			SubscriptionDef in = new SubscriptionDef("in", "ghost-df", "ghost.topic", "ghost-conn",
					null, null, null, null);
			RouteDef route = new RouteDef("r2", "in", null, List.of(), null, null);
			ContextDef context = new ContextDef("t", "c", List.of(), List.of(), List.of(),
					List.of(in), List.of(route), null);
			Events engine = new Events("a", List.of(context), Map.of(), null, null);

			String rendered = engine.describeRoute("r2");
			assertTrue(rendered.contains("<unresolved: ghost.topic>"), "topic unresolved");
			assertTrue(rendered.contains("<unresolved: ghost-conn>"), "connector unresolved");
			assertTrue(rendered.contains("<unresolved: ghost-df>"), "dataflow unresolved");
		}
	}

	@Nested
	@DisplayName("describeRoutes")
	class DescribeRoutes {

		@Test
		@DisplayName("lists every configured route")
		void listsRoutes() {
			String rendered = buildEngine().describeRoutes();
			assertTrue(rendered.contains("Route r1"), "should list route r1");
		}

		@Test
		@DisplayName("returns a message when no route is configured")
		void noRoutes() {
			ContextDef context = new ContextDef("t", "c", List.of(), List.of(), List.of(),
					List.of(), List.of(), null);
			Events engine = new Events("a", List.of(context), Map.of(), null, null);
			assertEquals("No routes configured", engine.describeRoutes());
		}
	}

	@Nested
	@DisplayName("bootstrap summary")
	class Summary {

		@Test
		@DisplayName("category is 'Events'")
		void category() {
			assertEquals("Events", buildEngine().getSummaryCategory());
		}

		@Test
		@DisplayName("items carry asset and per-type totals summed across contexts")
		void items() {
			Map<String, String> items = buildEngine().getSummaryItems();
			assertEquals("asset-1", items.get("Asset"));
			assertEquals("1", items.get("Clusters"));
			assertEquals("1", items.get("Routes"));
			assertEquals("1", items.get("Connectors"));
			assertEquals("2", items.get("Topics"));
			assertEquals("1", items.get("Dataflows"));
			assertEquals("2", items.get("Subscriptions"));
		}

		@Test
		@DisplayName("item order is stable")
		void stableOrder() {
			List<String> keys = List.copyOf(buildEngine().getSummaryItems().keySet());
			assertEquals(List.of("Asset", "Clusters", "Routes", "Connectors",
					"Topics", "Dataflows", "Subscriptions"), keys);
			assertFalse(keys.isEmpty());
		}
	}
}
