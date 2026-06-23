package com.garganttua.events.core.context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.events.api.context.ConnectorDef;
import com.garganttua.events.api.context.ConsumerConfigurationDef;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.context.DataflowDef;
import com.garganttua.events.api.context.LockDef;
import com.garganttua.events.api.context.ProducerConfigurationDef;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteExceptionsDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.RouteSyncDef;
import com.garganttua.events.api.context.SubscriptionDef;
import com.garganttua.events.api.context.TopicDef;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.HighAvailabilityMode;
import com.garganttua.events.api.enums.OriginPolicy;
import com.garganttua.events.api.enums.ProcessMode;
import com.garganttua.events.api.enums.PublicationMode;
import com.garganttua.events.api.exceptions.EventsException;

import java.util.HashMap;

public class JsonContextReader {

	private static final ObjectMapper mapper = new ObjectMapper();

	private static final String FIELD_NAME = "name";
	private static final String FIELD_CONFIGURATION = "configuration";
	private static final String FIELD_DESTINATION_POLICY = "destinationPolicy";

	public static ContextDef readFromFile(String path) throws EventsException {
		try {
			JsonNode root = mapper.readTree(new File(path));
			return parseContext(root);
		} catch (IOException e) {
			throw new EventsException("Failed to read context from file: " + path, e);
		}
	}

	public static ContextDef readFromResource(String resource) throws EventsException {
		try (InputStream is = resolveResource(resource)) {
			if (is == null) {
				throw new EventsException("Resource not found: " + resource);
			}
			JsonNode root = mapper.readTree(is);
			return parseContext(root);
		} catch (IOException e) {
			throw new EventsException("Failed to read context from resource: " + resource, e);
		}
	}

	public static ContextDef readFromString(String json) throws EventsException {
		try {
			JsonNode root = mapper.readTree(json);
			return parseContext(root);
		} catch (IOException e) {
			throw new EventsException("Failed to parse context JSON", e);
		}
	}

	/**
	 * Resolves a classpath resource, preferring the thread context class loader (the J2EE/module
	 * deployment loader) and falling back to this class' own loader, then the system loader.
	 *
	 * @param resource the classpath resource path
	 * @return an open stream, or {@code null} if the resource is not found by any loader
	 */
	private static InputStream resolveResource(String resource) {
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		InputStream is = contextLoader != null ? contextLoader.getResourceAsStream(resource) : null;
		if (is == null) {
			is = JsonContextReader.class.getResourceAsStream("/" + resource);
		}
		if (is == null) {
			is = ClassLoader.getSystemResourceAsStream(resource);
		}
		return is;
	}

	private static ContextDef parseContext(JsonNode root) {
		return new ContextDef(
				textOrNull(root, "tenantId"),
				textOrNull(root, "clusterId"),
				parseTopics(root),
				parseDataflows(root),
				parseConnectors(root),
				parseSubscriptions(root),
				parseRoutes(root),
				parseLocks(root));
	}

	private static List<TopicDef> parseTopics(JsonNode root) {
		List<TopicDef> topics = new ArrayList<>();
		if (root.has("topics")) {
			for (JsonNode n : root.get("topics")) {
				topics.add(new TopicDef(n.get("ref").asText()));
			}
		}
		return topics;
	}

	private static List<DataflowDef> parseDataflows(JsonNode root) {
		List<DataflowDef> dataflows = new ArrayList<>();
		if (root.has("dataflows")) {
			for (JsonNode n : root.get("dataflows")) {
				dataflows.add(new DataflowDef(
						textOrNull(n, "uuid"),
						textOrNull(n, FIELD_NAME),
						textOrNull(n, "type"),
						boolOrFalse(n, "garanteeOrder"),
						textOrNull(n, "version"),
						boolOrFalse(n, "encapsulated")));
			}
		}
		return dataflows;
	}

	private static List<ConnectorDef> parseConnectors(JsonNode root) {
		List<ConnectorDef> connectors = new ArrayList<>();
		if (root.has("connectors")) {
			for (JsonNode n : root.get("connectors")) {
				connectors.add(new ConnectorDef(
						textOrNull(n, FIELD_NAME),
						textOrNull(n, "type"),
						textOrNull(n, "version"),
						parseConfiguration(n)));
			}
		}
		return connectors;
	}

	private static Map<String, String> parseConfiguration(JsonNode node) {
		Map<String, String> config = new HashMap<>();
		if (node.has(FIELD_CONFIGURATION)) {
			node.get(FIELD_CONFIGURATION).fields().forEachRemaining(entry ->
					config.put(entry.getKey(), entry.getValue().asText()));
		}
		return config;
	}

	private static List<SubscriptionDef> parseSubscriptions(JsonNode root) {
		List<SubscriptionDef> subscriptions = new ArrayList<>();
		if (root.has("subscriptions")) {
			for (JsonNode n : root.get("subscriptions")) {
				subscriptions.add(new SubscriptionDef(
						textOrNull(n, "id"),
						textOrNull(n, "dataflow"),
						textOrNull(n, "topic"),
						textOrNull(n, "connector"),
						n.has("publicationMode") ? PublicationMode.valueOf(n.get("publicationMode").asText()) : PublicationMode.ON_CHANGE,
						parseConsumerConfig(n),
						parseProducerConfig(n),
						null));
			}
		}
		return subscriptions;
	}

	private static ConsumerConfigurationDef parseConsumerConfig(JsonNode n) {
		if (!n.has("consumerConfiguration")) {
			return null;
		}
		JsonNode cc = n.get("consumerConfiguration");
		return new ConsumerConfigurationDef(
				cc.has("processMode") ? ProcessMode.valueOf(cc.get("processMode").asText()) : ProcessMode.EVERYBODY,
				cc.has("originPolicy") ? OriginPolicy.valueOf(cc.get("originPolicy").asText()) : OriginPolicy.FROM_ANY,
				cc.has(FIELD_DESTINATION_POLICY) ? DestinationPolicy.valueOf(cc.get(FIELD_DESTINATION_POLICY).asText()) : DestinationPolicy.TO_ANY,
				cc.has("highAvailabilityMode") ? HighAvailabilityMode.valueOf(cc.get("highAvailabilityMode").asText()) : null);
	}

	private static ProducerConfigurationDef parseProducerConfig(JsonNode n) {
		if (!n.has("producerConfiguration")) {
			return null;
		}
		JsonNode pc = n.get("producerConfiguration");
		return new ProducerConfigurationDef(
				pc.has(FIELD_DESTINATION_POLICY) ? DestinationPolicy.valueOf(pc.get(FIELD_DESTINATION_POLICY).asText()) : DestinationPolicy.TO_ANY,
				textOrNull(pc, "destinationUuid"));
	}

	private static List<RouteDef> parseRoutes(JsonNode root) {
		List<RouteDef> routes = new ArrayList<>();
		if (root.has("routes")) {
			for (JsonNode n : root.get("routes")) {
				routes.add(new RouteDef(
						textOrNull(n, "uuid"),
						textOrNull(n, "from"),
						textOrNull(n, "to"),
						parseStages(n),
						parseRouteExceptions(n),
						parseRouteSync(n)));
			}
		}
		return routes;
	}

	private static List<RouteStageDef> parseStages(JsonNode n) {
		List<RouteStageDef> stages = new ArrayList<>();
		if (n.has("stages")) {
			for (JsonNode s : n.get("stages")) {
				stages.add(new RouteStageDef(
						textOrNull(s, FIELD_NAME),
						textOrNull(s, "expression"),
						textOrNull(s, "condition"),
						textOrNull(s, "catch"),
						textOrNull(s, "catchDownstream")));
			}
		}
		return stages;
	}

	private static RouteExceptionsDef parseRouteExceptions(JsonNode n) {
		if (!n.has("exceptions")) {
			return null;
		}
		JsonNode ex = n.get("exceptions");
		return new RouteExceptionsDef(
				textOrNull(ex, "to"),
				textOrNull(ex, "cast"),
				textOrNull(ex, "label"));
	}

	private static RouteSyncDef parseRouteSync(JsonNode n) {
		if (!n.has("synchronization")) {
			return null;
		}
		JsonNode sy = n.get("synchronization");
		return new RouteSyncDef(
				textOrNull(sy, "lock"),
				textOrNull(sy, "lockObject"));
	}

	private static List<LockDef> parseLocks(JsonNode root) {
		List<LockDef> locks = new ArrayList<>();
		if (root.has("locks")) {
			for (JsonNode n : root.get("locks")) {
				locks.add(new LockDef(
						textOrNull(n, FIELD_NAME),
						textOrNull(n, "type"),
						textOrNull(n, "version"),
						parseConfiguration(n)));
			}
		}
		return locks;
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
	}

	private static boolean boolOrFalse(JsonNode node, String field) {
		return node.has(field) && node.get(field).asBoolean();
	}
}
