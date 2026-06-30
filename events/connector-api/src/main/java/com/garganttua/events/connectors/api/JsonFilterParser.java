package com.garganttua.events.connectors.api;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.observability.Logger;

/**
 * Parses the JSON shape an api-core {@code Filter} serialises to into a connector-local
 * {@link JsonFilter} tree, so a declarative {@code filter} config key can drive
 * {@link ApiEventFilter} without an api-core dependency on the connector's main classpath.
 *
 * <p>The recognised shape is exactly the one produced by
 * {@code objectMapper.writeValueAsString(myFilter)} on an api-core {@code Filter}: each node is a
 * JSON object with an optional {@code name} (the {@code $}-prefixed operator), an optional
 * {@code value}, and an optional {@code literals} array of sub-filters. For example
 * {@code Filter.in("operation","create","update")} serialises to a {@code $field} node whose single
 * {@code $in} child carries the value-only literals.</p>
 *
 * <p>The parser is <b>defensive</b>: a {@code null}, blank, syntactically invalid or non-object JSON
 * yields {@code null} (meaning "no filter / pass everything") rather than throwing, so it can never
 * abort the connector path.</p>
 */
public final class JsonFilterParser {

	private static final Logger LOG = Logger.getLogger(JsonFilterParser.class);

	private static final String FIELD_NAME = "name";
	private static final String FIELD_VALUE = "value";
	private static final String FIELD_LITERALS = "literals";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private JsonFilterParser() {
	}

	/**
	 * Parses a JSON filter string into a {@link JsonFilter} tree.
	 *
	 * @param json the JSON filter (the shape api-core {@code Filter} serialises to), may be
	 *             {@code null}/blank/malformed
	 * @return the parsed filter tree, or {@code null} when {@code json} is absent or cannot be parsed
	 */
	public static IFilter parse(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return parse(MAPPER.readTree(json));
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			LOG.warn("Ignoring malformed api connector filter JSON: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Parses an already-decoded JSON node into a {@link JsonFilter} tree.
	 *
	 * @param node the JSON node, may be {@code null} or non-object
	 * @return the parsed filter tree, or {@code null} when {@code node} is not a filter object
	 */
	public static IFilter parse(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		String name = node.hasNonNull(FIELD_NAME) ? node.get(FIELD_NAME).asText() : null;
		Object value = readValue(node.get(FIELD_VALUE));
		List<IFilter> children = readLiterals(node.get(FIELD_LITERALS));
		if (name == null && value == null && children.isEmpty()) {
			return null;
		}
		return new JsonFilter(name, value, children);
	}

	private static List<IFilter> readLiterals(JsonNode literals) {
		List<IFilter> children = new ArrayList<>();
		if (literals != null && literals.isArray()) {
			for (JsonNode child : literals) {
				IFilter parsed = parseChild(child);
				if (parsed != null) {
					children.add(parsed);
				}
			}
		}
		return children;
	}

	/**
	 * Parses a sub-filter, preserving the value-only {@code $in}/{@code $nin} member literals (a
	 * node with no {@code name} but a {@code value}), which the root-level {@link #parse(JsonNode)}
	 * would otherwise discard as empty.
	 */
	private static IFilter parseChild(JsonNode child) {
		if (child == null || !child.isObject()) {
			return null;
		}
		String name = child.hasNonNull(FIELD_NAME) ? child.get(FIELD_NAME).asText() : null;
		Object value = readValue(child.get(FIELD_VALUE));
		List<IFilter> grandChildren = readLiterals(child.get(FIELD_LITERALS));
		return new JsonFilter(name, value, grandChildren);
	}

	private static Object readValue(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return null;
		}
		if (value.isTextual()) {
			return value.asText();
		}
		if (value.isBoolean()) {
			return value.asBoolean();
		}
		if (value.isNumber()) {
			return value.numberValue();
		}
		// Objects/arrays (e.g. GeoJSON) are not understood by the event evaluator; keep their text
		// form so comparison stays well-defined rather than throwing.
		return value.toString();
	}
}
