package com.garganttua.dao.mongodb;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.geojson.GeoJsonObject;
import org.geojson.Point;

import com.garganttua.api.binding.geojson.GeoJsonCodec;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.mongodb.client.model.Filters;

/**
 * Translates the api's {@link IFilter} tree into a MongoDB query. Filter values are converted with
 * {@link MongoBsonValues} — the same conversion the writer applies — so a {@code BigDecimal},
 * {@code BigInteger} or {@code UUID} is compared in the form it was stored in.
 *
 * <p>
 * PMD note: org.bson.Document is the MongoDB driver's predicate type (the GeoJSON {type,coordinates}
 * handed to Filters.geoWithin), surfaced deliberately rather than via a Map interface.
 * </p>
 */
@SuppressWarnings({ "PMD.LooseCoupling" })
public class MongoFilterConverter {

	/** The operators whose value is a GeoJSON geometry (or a $near), passed to the driver as is. */
	private static final java.util.Set<String> GEO_OPERATORS = java.util.Set.of("$geoWithin", "$geoWithinSphere",
			"$geoIntersects", "$near", "$nearSphere");

	/**
	 * The radius MongoDB measures spherical distances with, in metres — what turns a {@code $near}
	 * distance into the radians of a {@code $centerSphere}.
	 */
	static final double EARTH_RADIUS_METRES = 6_378_100d;

	private static final String FIELD_OPERATOR = "$field";
	private static final String ON_FIELD = " filter on field '";

	/**
	 * Translates a filter for a read.
	 *
	 * @param filter the filter, possibly null (everything matches)
	 * @return the MongoDB query
	 * @throws ApiException when the filter is malformed or uses an unsupported operator
	 */
	public static Bson convert(IFilter filter) throws ApiException {
		return convert(filter, false);
	}

	/**
	 * Translates a filter for a COUNT. MongoDB refuses {@code $near} in {@code countDocuments} (it runs
	 * as an aggregation {@code $match}), yet a paginated read counts with its own filter: a
	 * {@code $near} is counted as the ring it selects — the documents with the field, within
	 * {@code $maxDistance} ({@code $geoWithin} / {@code $centerSphere}, radius in radians at
	 * MongoDB's own earth radius), and not within {@code $minDistance}. The count is the size of the
	 * read; only the ORDER, which a count does not have, is left out.
	 *
	 * @param filter the filter, possibly null
	 * @return the MongoDB query
	 * @throws ApiException when the filter is malformed or uses an unsupported operator
	 */
	public static Bson convertForCount(IFilter filter) throws ApiException {
		return convert(filter, true);
	}

	/**
	 * Whether a filter holds a {@code $near} / {@code $nearSphere} — whose results MongoDB returns
	 * nearest first, an order a read must then not override.
	 *
	 * @param filter the filter, possibly null
	 * @return {@code true} when a {@code $near} is present anywhere in the tree
	 */
	public static boolean hasNear(IFilter filter) {
		if (filter == null || filter.getName() == null) {
			return false;
		}
		if (FIELD_OPERATOR.equals(filter.getName())) {
			List<IFilter> subs = filter.getFilters();
			return subs != null && subs.size() == 1 && subs.get(0) != null
					&& ("$near".equals(subs.get(0).getName()) || "$nearSphere".equals(subs.get(0).getName()));
		}
		List<IFilter> subs = filter.getFilters();
		return subs != null && subs.stream().anyMatch(MongoFilterConverter::hasNear);
	}

	private static Bson convert(IFilter filter, boolean forCount) throws ApiException {
		if (filter == null) {
			return new Document();
		}

		String name = filter.getName();
		if (name == null) {
			return new Document();
		}

		return switch (name) {
			case "$and" -> convertLogical(filter, "$and", forCount);
			case "$or" -> convertLogical(filter, "$or", forCount);
			case "$nor" -> convertLogical(filter, "$nor", forCount);
			case "$field" -> convertField(filter, forCount);
			default -> throw new ApiException("Unsupported filter operator: " + name);
		};
	}

	private static Bson convertLogical(IFilter filter, String operator, boolean forCount) throws ApiException {
		List<IFilter> subs = filter.getFilters();
		if (subs == null || subs.size() < 2) {
			throw new ApiException("Logical operator " + operator + " requires at least 2 sub-filters");
		}

		Bson[] converted = new Bson[subs.size()];
		for (int i = 0; i < subs.size(); i++) {
			converted[i] = convert(subs.get(i), forCount);
		}

		return switch (operator) {
			case "$and" -> Filters.and(converted);
			case "$or" -> Filters.or(converted);
			case "$nor" -> Filters.nor(converted);
			default -> throw new ApiException("Unknown logical operator: " + operator);
		};
	}

	private static Bson convertField(IFilter filter, boolean forCount) throws ApiException {
		String fieldName = (String) filter.getValue();
		if (fieldName == null) {
			throw new ApiException("$field filter requires a field name as value");
		}

		List<IFilter> subs = filter.getFilters();
		if (subs == null || subs.size() != 1) {
			throw new ApiException("$field filter requires exactly 1 comparison sub-filter");
		}

		IFilter comparison = subs.get(0);
		String op = comparison.getName();
		Object raw = comparison.getValue();
		// Compare in the shape the writer stored: big numbers as Decimal128, UUIDs as strings.
		Object value = GEO_OPERATORS.contains(op) ? raw : MongoBsonValues.toBson(raw, fieldName);

		return switch (op) {
			case "$eq" -> Filters.eq(fieldName, value);
			case "$ne" -> Filters.ne(fieldName, value);
			case "$gt" -> Filters.gt(fieldName, value);
			case "$gte" -> Filters.gte(fieldName, value);
			case "$lt" -> Filters.lt(fieldName, value);
			case "$lte" -> Filters.lte(fieldName, value);
			case "$regex" -> Filters.regex(fieldName, value.toString());
			case "$empty" -> Filters.exists(fieldName, false);
			case "$in" -> convertIn(fieldName, comparison);
			case "$nin" -> convertNin(fieldName, comparison);
			case "$text" -> Filters.text(value.toString());
			// Geospatial: $geoWithin and $geoWithinSphere both resolve to a GeoJSON
			// $geoWithin/$geometry query — a 2dsphere index already evaluates it on the
			// sphere, so 'Sphere' is an alias here. Requires a 2dsphere index on the field.
			case "$geoWithin", "$geoWithinSphere" -> convertGeoWithin(fieldName, value);
			case "$geoIntersects" -> Filters.geoIntersects(fieldName, geoJson(fieldName, op, value));
			// $near and $nearSphere are the same query on a 2dsphere index: distances in metres, on the
			// sphere, nearest first. Like every geo query, they need a 2dsphere index on the field.
			case "$near", "$nearSphere" -> forCount ? countNear(fieldName, op, value) : convertNear(fieldName, op, value);
			default -> throw new ApiException("Unsupported comparison operator: " + op);
		};
	}

	/**
	 * Turns an {@code org.geojson} geometry (the filter's value) into a MongoDB
	 * {@code {<field>: {$geoWithin: {$geometry: {type,coordinates}}}}} predicate, the geometry written
	 * by {@link GeoJsonCodec} — the same form the writer stores.
	 */
	private static Bson convertGeoWithin(String fieldName, Object geometry) throws ApiException {
		// Filters.geoWithin(field, Bson) already wraps the geometry in $geometry: pass the bare GeoJSON.
		return Filters.geoWithin(fieldName, geoJson(fieldName, "$geoWithin", geometry));
	}

	private static Bson convertNear(String fieldName, String op, Object value) throws ApiException {
		GeoNear near = near(fieldName, op, value);
		return Filters.nearSphere(fieldName, geoJson(fieldName, op, near.geometry()), near.maxDistance(),
				near.minDistance());
	}

	/** {@code $near} for a count: the ring it selects, without the order (see {@link #convertForCount}). */
	private static Bson countNear(String fieldName, String op, Object value) throws ApiException {
		GeoNear near = near(fieldName, op, value);
		if (!(near.geometry() instanceof Point point) || point.getCoordinates() == null) {
			throw new ApiException(op + ON_FIELD + fieldName + "' requires a GeoJSON Point");
		}
		double lng = point.getCoordinates().getLongitude();
		double lat = point.getCoordinates().getLatitude();
		List<Bson> ring = new ArrayList<>();
		ring.add(Filters.exists(fieldName));
		ring.add(Filters.ne(fieldName, null));
		if (near.maxDistance() != null) {
			ring.add(Filters.geoWithinCenterSphere(fieldName, lng, lat, near.maxDistance() / EARTH_RADIUS_METRES));
		}
		if (near.minDistance() != null && near.minDistance() > 0) {
			ring.add(Filters.nor(Filters.geoWithinCenterSphere(fieldName, lng, lat,
					near.minDistance() / EARTH_RADIUS_METRES)));
		}
		return Filters.and(ring);
	}

	private static GeoNear near(String fieldName, String op, Object value) throws ApiException {
		if (!(value instanceof GeoNear near)) {
			throw new ApiException(op + ON_FIELD + fieldName + "' requires a point and distances "
					+ "({$geometry: <Point>, $maxDistance: <metres>})");
		}
		return near;
	}

	/** A GeoJSON geometry as the bare {type, coordinates} document the driver wraps in $geometry. */
	private static Document geoJson(String fieldName, String op, Object geometry) throws ApiException {
		if (geometry == null) {
			throw new ApiException(op + ON_FIELD + fieldName + "' requires a GeoJSON geometry value");
		}
		if (!(geometry instanceof GeoJsonObject geo)) {
			throw new ApiException(op + ON_FIELD + fieldName + "' requires an org.geojson geometry, got "
					+ geometry.getClass().getName());
		}
		try {
			return new Document(GeoJsonCodec.toMap(geo));
		} catch (IllegalArgumentException e) {
			throw new ApiException(op + ON_FIELD + fieldName + "': " + e.getMessage(), e);
		}
	}

	private static Bson convertIn(String fieldName, IFilter comparison) throws ApiException {
		List<IFilter> values = comparison.getFilters();
		if (values == null || values.isEmpty()) {
			throw new ApiException("$in operator requires at least 1 value");
		}
		return Filters.in(fieldName, bsonValues(fieldName, values));
	}

	private static Bson convertNin(String fieldName, IFilter comparison) throws ApiException {
		List<IFilter> values = comparison.getFilters();
		if (values == null || values.isEmpty()) {
			throw new ApiException("$nin operator requires at least 1 value");
		}
		return Filters.nin(fieldName, bsonValues(fieldName, values));
	}

	/** The listed values of an {@code $in} / {@code $nin}, each in the shape the writer stores it. */
	private static List<Object> bsonValues(String fieldName, List<IFilter> values) throws ApiException {
		List<Object> converted = new ArrayList<>(values.size());
		for (IFilter value : values) {
			converted.add(MongoBsonValues.toBson(value.getValue(), fieldName));
		}
		return converted;
	}
}
