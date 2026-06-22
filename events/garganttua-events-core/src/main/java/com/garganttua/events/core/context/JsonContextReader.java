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

	public static ContextDef readFromFile(String path) throws EventsException {
		try {
			JsonNode root = mapper.readTree(new File(path));
			return parseContext(root);
		} catch (IOException e) {
			throw new EventsException("Failed to read context from file: " + path, e);
		}
	}

	public static ContextDef readFromResource(String resource) throws EventsException {
		try (InputStream is = JsonContextReader.class.getClassLoader().getResourceAsStream(resource)) {
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

	private static ContextDef parseContext(JsonNode root) {
		String tenantId = textOrNull(root, "tenantId");
		String clusterId = textOrNull(root, "clusterId");

		List<TopicDef> topics = new ArrayList<>();
		if (root.has("topics")) {
			for (JsonNode n : root.get("topics")) {
				topics.add(new TopicDef(n.get("ref").asText()));
			}
		}

		List<DataflowDef> dataflows = new ArrayList<>();
		if (root.has("dataflows")) {
			for (JsonNode n : root.get("dataflows")) {
				dataflows.add(new DataflowDef(
						textOrNull(n, "uuid"),
						textOrNull(n, "name"),
						textOrNull(n, "type"),
						boolOrFalse(n, "garanteeOrder"),
						textOrNull(n, "version"),
						boolOrFalse(n, "encapsulated")));
			}
		}

		List<ConnectorDef> connectors = new ArrayList<>();
		if (root.has("connectors")) {
			for (JsonNode n : root.get("connectors")) {
				Map<String, String> config = new HashMap<>();
				if (n.has("configuration")) {
					n.get("configuration").fields().forEachRemaining(entry ->
							config.put(entry.getKey(), entry.getValue().asText()));
				}
				connectors.add(new ConnectorDef(
						textOrNull(n, "name"),
						textOrNull(n, "type"),
						textOrNull(n, "version"),
						config));
			}
		}

		List<SubscriptionDef> subscriptions = new ArrayList<>();
		if (root.has("subscriptions")) {
			for (JsonNode n : root.get("subscriptions")) {
				ConsumerConfigurationDef consumerConfig = null;
				if (n.has("consumerConfiguration")) {
					JsonNode cc = n.get("consumerConfiguration");
					consumerConfig = new ConsumerConfigurationDef(
							cc.has("processMode") ? ProcessMode.valueOf(cc.get("processMode").asText()) : ProcessMode.EVERYBODY,
							cc.has("originPolicy") ? OriginPolicy.valueOf(cc.get("originPolicy").asText()) : OriginPolicy.FROM_ANY,
							cc.has("destinationPolicy") ? DestinationPolicy.valueOf(cc.get("destinationPolicy").asText()) : DestinationPolicy.TO_ANY,
							cc.has("highAvailabilityMode") ? HighAvailabilityMode.valueOf(cc.get("highAvailabilityMode").asText()) : null);
				}

				ProducerConfigurationDef producerConfig = null;
				if (n.has("producerConfiguration")) {
					JsonNode pc = n.get("producerConfiguration");
					producerConfig = new ProducerConfigurationDef(
							pc.has("destinationPolicy") ? DestinationPolicy.valueOf(pc.get("destinationPolicy").asText()) : DestinationPolicy.TO_ANY,
							textOrNull(pc, "destinationUuid"));
				}

				subscriptions.add(new SubscriptionDef(
						textOrNull(n, "id"),
						textOrNull(n, "dataflow"),
						textOrNull(n, "topic"),
						textOrNull(n, "connector"),
						n.has("publicationMode") ? PublicationMode.valueOf(n.get("publicationMode").asText()) : PublicationMode.ON_CHANGE,
						consumerConfig,
						producerConfig,
						null));
			}
		}

		List<RouteDef> routes = new ArrayList<>();
		if (root.has("routes")) {
			for (JsonNode n : root.get("routes")) {
				List<RouteStageDef> stages = new ArrayList<>();
				if (n.has("stages")) {
					for (JsonNode s : n.get("stages")) {
						stages.add(new RouteStageDef(
								textOrNull(s, "name"),
								textOrNull(s, "expression"),
								textOrNull(s, "condition"),
								textOrNull(s, "catch"),
								textOrNull(s, "catchDownstream")));
					}
				}

				RouteExceptionsDef exceptions = null;
				if (n.has("exceptions")) {
					JsonNode ex = n.get("exceptions");
					exceptions = new RouteExceptionsDef(
							textOrNull(ex, "to"),
							textOrNull(ex, "cast"),
							textOrNull(ex, "label"));
				}

				RouteSyncDef sync = null;
				if (n.has("synchronization")) {
					JsonNode sy = n.get("synchronization");
					sync = new RouteSyncDef(
							textOrNull(sy, "lock"),
							textOrNull(sy, "lockObject"));
				}

				routes.add(new RouteDef(
						textOrNull(n, "uuid"),
						textOrNull(n, "from"),
						textOrNull(n, "to"),
						stages,
						exceptions,
						sync));
			}
		}

		List<LockDef> locks = new ArrayList<>();
		if (root.has("locks")) {
			for (JsonNode n : root.get("locks")) {
				Map<String, String> config = new HashMap<>();
				if (n.has("configuration")) {
					n.get("configuration").fields().forEachRemaining(entry ->
							config.put(entry.getKey(), entry.getValue().asText()));
				}
				locks.add(new LockDef(
						textOrNull(n, "name"),
						textOrNull(n, "type"),
						textOrNull(n, "version"),
						config));
			}
		}

		return new ContextDef(tenantId, clusterId, topics, dataflows, connectors,
				subscriptions, routes, locks);
	}

	private static String textOrNull(JsonNode node, String field) {
		return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
	}

	private static boolean boolOrFalse(JsonNode node, String field) {
		return node.has(field) && node.get(field).asBoolean();
	}
}
