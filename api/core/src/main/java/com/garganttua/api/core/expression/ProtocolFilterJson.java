package com.garganttua.api.core.expression;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.geojson.GeoJsonObject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.core.filter.Filter;

/**
 * Parses a Mongo-like JSON filter into an {@link IFilter}. Extracted from {@link ProtocolExpressions}
 * to keep that expression holder under the file-size gate. Supports the FULL operator set:
 * <ul>
 *   <li>a field with a scalar → {@code $eq}: {@code {"name":"Alice"}};</li>
 *   <li>a field with an operator object → those operators (AND-combined):
 *       {@code {"age":{"$gte":18,"$lt":65}}}. Operators: {@code $eq,$ne,$gt,$gte,$lt,$lte,
 *       $in,$nin,$regex,$text,$empty,$geoWithin,$geoWithinSphere};</li>
 *   <li>a field with an array → {@code $in} shorthand: {@code {"role":["admin","user"]}};</li>
 *   <li>several fields at one level → AND; explicit logical groups {@code $and/$or/$nor}
 *       take arrays of sub-filters: {@code {"$or":[{"a":1},{"b":2}]}};</li>
 *   <li>geospatial: the operator value is a GeoJSON geometry.</li>
 * </ul>
 * Scalars keep their JSON type. A malformed JSON (or invalid GeoJSON) raises an
 * {@link ApiException}; an unknown operator is skipped.
 */
final class ProtocolFilterJson {

	private static final ObjectMapper FILTER_JSON = new ObjectMapper();

	private ProtocolFilterJson() {
	}

	static IFilter parse(String json) {
		JsonNode root;
		try {
			root = FILTER_JSON.readTree(json);
		} catch (Exception e) {
			throw new ApiException("Invalid JSON filter: " + e.getMessage(), e);
		}
		return jsonNodeToFilter(root);
	}

	private static Filter jsonNodeToFilter(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isArray()) {
			// A top-level array is an implicit AND of its sub-filters.
			List<Filter> subs = new ArrayList<>();
			for (JsonNode element : node) {
				Filter f = jsonNodeToFilter(element);
				if (f != null) subs.add(f);
			}
			return combine(subs);
		}
		if (!node.isObject()) {
			return null;
		}
		List<Filter> clauses = new ArrayList<>();
		Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String key = entry.getKey();
			JsonNode value = entry.getValue();
			switch (key) {
				case "$and": clauses.add(Filter.and(jsonFilterArray(value))); break;
				case "$or":  clauses.add(Filter.or(jsonFilterArray(value)));  break;
				case "$nor": clauses.add(Filter.nor(jsonFilterArray(value))); break;
				default: {
					Filter fieldFilter = jsonFieldClause(key, value);
					if (fieldFilter != null) clauses.add(fieldFilter);
				}
			}
		}
		return combine(clauses);
	}

	private static Filter[] jsonFilterArray(JsonNode arrayNode) {
		List<Filter> subs = new ArrayList<>();
		if (arrayNode != null && arrayNode.isArray()) {
			for (JsonNode element : arrayNode) {
				Filter f = jsonNodeToFilter(element);
				if (f != null) subs.add(f);
			}
		}
		return subs.toArray(new Filter[0]);
	}

	private static Filter jsonFieldClause(String field, JsonNode value) {
		if (value.isObject()) {
			List<Filter> ops = new ArrayList<>();
			Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
			while (entries.hasNext()) {
				Map.Entry<String, JsonNode> e = entries.next();
				Filter op = jsonOperatorClause(field, e.getKey(), e.getValue());
				if (op != null) ops.add(op);
			}
			return combine(ops);
		}
		if (value.isArray()) {
			return Filter.in(field, jsonScalarArray(value)); // shorthand: array → $in
		}
		return Filter.eq(field, jsonScalar(value));
	}

	private static Filter jsonOperatorClause(String field, String op, JsonNode value) {
		switch (op) {
			case "$eq":             return Filter.eq(field, jsonScalar(value));
			case "$ne":             return Filter.ne(field, jsonScalar(value));
			case "$gt":             return Filter.gt(field, jsonScalar(value));
			case "$gte":            return Filter.gte(field, jsonScalar(value));
			case "$lt":             return Filter.lt(field, jsonScalar(value));
			case "$lte":            return Filter.lte(field, jsonScalar(value));
			case "$in":             return Filter.in(field, jsonScalarArray(value));
			case "$nin":            return Filter.nin(field, jsonScalarArray(value));
			case "$regex":          return value.isTextual() ? Filter.regex(field, value.asText()) : null;
			case "$text":           return value.isTextual() ? Filter.text(field, value.asText()) : null;
			case "$empty":          return Filter.empty(field);
			case "$geoWithin":       return Filter.geolocWithin(field, jsonGeometry(value));
			case "$geoWithinSphere": return Filter.geolocWithinSphere(field, jsonGeometry(value));
			case "$geoIntersects":   return Filter.geolocIntersects(field, jsonGeometry(unwrapGeometry(value)));
			case "$near":            return Filter.geolocNear(field, jsonNear(value));
			case "$nearSphere":      return Filter.geolocNearSphere(field, jsonNear(value));
			default:                return null; // unknown operator — skip
		}
	}

	/** MongoDB writes a geometry operand as <code>{$geometry: &lt;GeoJSON&gt;}</code>; the bare GeoJSON is accepted too. */
	private static JsonNode unwrapGeometry(JsonNode value) {
		return value != null && value.has("$geometry") ? value.get("$geometry") : value;
	}

	/** <code>{$geometry: &lt;Point&gt;, $maxDistance: m, $minDistance: m}</code>, distances in metres. */
	private static GeoNear jsonNear(JsonNode value) {
		JsonNode geometry = unwrapGeometry(value);
		if (geometry == null || geometry.isNull()) {
			throw new ApiException("Invalid $near filter: it requires a GeoJSON point ($geometry)");
		}
		try {
			return new GeoNear(jsonGeometry(geometry), distance(value, "$maxDistance"), distance(value, "$minDistance"));
		} catch (IllegalArgumentException e) {
			throw new ApiException("Invalid $near filter: " + e.getMessage(), e);
		}
	}

	private static Double distance(JsonNode value, String key) {
		if (value == null || !value.has(key) || value.get(key).isNull()) {
			return null;
		}
		if (!value.get(key).isNumber()) {
			throw new ApiException("Invalid $near filter: " + key + " must be a number of metres, got " + value.get(key));
		}
		return value.get(key).doubleValue();
	}

	private static GeoJsonObject jsonGeometry(JsonNode node) {
		try {
			return FILTER_JSON.treeToValue(node, GeoJsonObject.class);
		} catch (Exception e) {
			throw new ApiException("Invalid GeoJSON geometry in filter: " + e.getMessage(), e);
		}
	}

	/** A JSON scalar keeps its native type — boolean / long / double / string (null tolerated). */
	private static Object jsonScalar(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isIntegralNumber()) {
			return node.longValue();
		}
		if (node.isNumber()) {
			return node.doubleValue();
		}
		return node.asText();
	}

	private static Object[] jsonScalarArray(JsonNode node) {
		List<Object> values = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode element : node) {
				values.add(jsonScalar(element));
			}
		} else if (node != null && !node.isNull()) {
			values.add(jsonScalar(node));
		}
		return values.toArray();
	}

	private static Filter combine(List<Filter> clauses) {
		if (clauses.isEmpty()) {
			return null;
		}
		return clauses.size() == 1 ? clauses.get(0) : Filter.and(clauses.toArray(new Filter[0]));
	}
}
