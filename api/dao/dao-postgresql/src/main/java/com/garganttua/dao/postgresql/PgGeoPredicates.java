package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.geojson.LngLatAlt;
import org.geojson.Point;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The geographic operators, evaluated on the SPHERE — as a MongoDB {@code 2dsphere} index evaluates
 * them — by casting both sides to PostGIS {@code geography}.
 *
 * <ul>
 * <li>{@code $geoWithin} / {@code $geoWithinSphere}: {@code ST_CoveredBy(field, shape)}. Up to
 * ALPHA28 this was {@code ST_Within} on the planar geometry, in degrees: for a large polygon (a region,
 * a department) the two stores could return different rows. Both spellings are now the spherical
 * test, as on MongoDB, where {@code Sphere} is an alias.</li>
 * <li>{@code $geoIntersects}: {@code ST_Intersects(field, shape)}.</li>
 * <li>{@code $near} / {@code $nearSphere}: the field set, within {@code $maxDistance} metres
 * ({@code ST_DWithin}) and at least {@code $minDistance} metres away; a read that names no sort is
 * ordered nearest first ({@link #nearOrder}), as MongoDB orders {@code $near}.</li>
 * </ul>
 *
 * <p>
 * Every predicate is written on {@code (<field>)::geography}: that is the expression a declared
 * {@code IndexKind.geo} index is built on, so the index serves them. Distances are those of PostGIS
 * {@code geography} (the WGS 84 spheroid); MongoDB measures on a sphere — the two agree within a few
 * tenths of a percent.
 * </p>
 */
final class PgGeoPredicates {

    private static final String GEOGRAPHY = ")::geography";
    private static final String OP_NEAR = "$near";
    private static final String OP_NEAR_SPHERE = "$nearSphere";
    private static final String FIELD_OPERATOR = "$field";
    private static final String ON_FIELD = " filter on field '";
    /** MongoDB allows one $near per filter. */
    private static final int MAX_NEARS = 1;

    private final String domain;

    PgGeoPredicates(String domain) {
        this.domain = domain;
    }

    /** {@code $geoWithin} / {@code $geoWithinSphere}: the field lies entirely in the shape, on the sphere. */
    PgSql within(PgOperand o, PgCondition c) throws ApiException {
        return PgSql.of("ST_CoveredBy(").then(field(o, c)).then(", ").then(shape(o, c.value(), c)).then(")");
    }

    /** {@code $geoIntersects}: the field and the shape share a point, on the sphere. */
    PgSql intersects(PgOperand o, PgCondition c) throws ApiException {
        return PgSql.of("ST_Intersects(").then(field(o, c)).then(", ").then(shape(o, c.value(), c)).then(")");
    }

    /** {@code $near} / {@code $nearSphere}: the field set, inside the ring of distances. */
    PgSql near(PgOperand o, PgCondition c) throws ApiException {
        GeoNear near = nearValue(c);
        PgSql field = field(o, c);
        List<PgSql> parts = new ArrayList<>();
        parts.add(o.expr(false).then(" IS NOT NULL"));
        if (near.maxDistance() != null) {
            parts.add(PgSql.of("ST_DWithin(").then(field).then(", ").then(shape(o, near.geometry(), c))
                    .then(PgSql.of(", ?)", near.maxDistance())));
        }
        if (near.minDistance() != null && near.minDistance() > 0) {
            parts.add(PgSql.of("ST_Distance(").then(field).then(", ").then(shape(o, near.geometry(), c))
                    .then(PgSql.of(") >= ?", near.minDistance())));
        }
        return PgSql.join(" AND ", parts).wrap("(", ")");
    }

    /**
     * The sort key of a {@code $near}: nearest first, by the KNN distance operator, which a geo index
     * on the field can serve in order.
     *
     * <p>
     * MongoDB refuses a {@code $near} under {@code $or} or {@code $nor}, and a second one: so does this,
     * with MongoDB's reasons. It also refuses a {@code $near} on a field held in a collection — the
     * nearest ELEMENT of an entity is not an order PostgreSQL can be asked for by an index.
     * </p>
     *
     * @param table  the domain's model
     * @param filter the whole filter
     * @return the {@code ORDER BY} key, or empty when the filter holds no {@code $near}
     * @throws ApiException when the filter breaks one of MongoDB's {@code $near} rules
     */
    static Optional<String> nearOrder(PgTable table, IFilter filter) throws ApiException {
        List<IFilter> nears = new ArrayList<>();
        collect(filter, null, nears);
        if (nears.isEmpty()) {
            return Optional.empty();
        }
        if (nears.size() > MAX_NEARS) {
            throw new ApiException("A filter may hold only one $near: MongoDB refuses a second one "
                    + "('Too many geoNear expressions')");
        }
        IFilter clause = nears.get(0);
        String field = String.valueOf(clause.getValue());
        PgColumn column = table.column(field).filter(c -> c.kind() == PgColumnKind.GEOMETRY)
                .orElseThrow(() -> new ApiException("$near on field '" + field + "' of domain '" + table.name()
                        + "': the field is not a geometry of the entity itself; only a GeoJSON field, stored as a "
                        + "PostGIS geometry, can be searched by distance"));
        Point point = point(clause.getFilters().get(0).getValue(), field);
        String target = "'SRID=4326;POINT(" + coordinate(point.getCoordinates().getLongitude()) + " "
                + coordinate(point.getCoordinates().getLatitude()) + ")'::geography";
        return Optional.of("(" + PgQuery.ALIAS + "." + PgNaming.quote(column.name()) + GEOGRAPHY + " <-> " + target
                + " ASC");
    }

    private static void collect(IFilter filter, String under, List<IFilter> nears) throws ApiException {
        if (filter == null || filter.getName() == null) {
            return;
        }
        if (FIELD_OPERATOR.equals(filter.getName())) {
            List<IFilter> subs = filter.getFilters();
            String op = subs == null || subs.size() != 1 || subs.get(0) == null ? null : subs.get(0).getName();
            if (OP_NEAR.equals(op) || OP_NEAR_SPHERE.equals(op)) {
                if (under != null) {
                    throw new ApiException("$near cannot be placed under " + under
                            + ": MongoDB refuses it ('geoNear must be top-level expr'); search with $near at the top "
                            + "level, or inside $and");
                }
                nears.add(filter);
            }
            return;
        }
        String next = "$or".equals(filter.getName()) || "$nor".equals(filter.getName()) ? filter.getName() : under;
        for (IFilter sub : filter.getFilters() == null ? List.<IFilter>of() : filter.getFilters()) {
            collect(sub, next, nears);
        }
    }

    /** A double as SQL: finite, so its text is a plain number — never anything else in the statement. */
    private static String coordinate(double value) {
        if (!Double.isFinite(value)) {
            throw new ApiException("$near point has a non-finite coordinate: " + value);
        }
        return String.format(Locale.ROOT, "%.15g", value);
    }

    private GeoNear nearValue(PgCondition c) {
        if (!(c.value() instanceof GeoNear near)) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a point and distances "
                    + "({$geometry: <Point>, $maxDistance: <metres>})");
        }
        point(near.geometry(), c.field());
        return near;
    }

    private static Point point(Object value, String field) {
        Object geometry = value instanceof GeoNear near ? near.geometry() : value;
        if (!(geometry instanceof Point point) || point.getCoordinates() == null) {
            throw new ApiException("$near filter on field '" + field + "' requires a GeoJSON Point, got "
                    + (geometry == null ? "nothing" : geometry.getClass().getSimpleName()));
        }
        LngLatAlt coordinates = point.getCoordinates();
        coordinate(coordinates.getLongitude());
        coordinate(coordinates.getLatitude());
        return point;
    }

    /** The field as a geography — after checking that it IS a geometry. */
    private PgSql field(PgOperand o, PgCondition c) throws ApiException {
        if (c.value() == null) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a GeoJSON geometry value");
        }
        if (o.isJsonPath() || o.column().kind() != PgColumnKind.GEOMETRY) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' of domain '" + this.domain
                    + "': the field is not a geometry (it is " + o.column().sqlType()
                    + "); only a GeoJSON field, stored as a PostGIS geometry, can be tested geographically");
        }
        return o.expr(false).wrap("(", GEOGRAPHY);
    }

    /** A bound GeoJSON shape, as a geography. */
    private static PgSql shape(PgOperand o, Object geometry, PgCondition c) throws ApiException {
        PgColumn column = o.bindColumn(false);
        if (geometry == null) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a GeoJSON geometry value");
        }
        return PgSql.of("(" + PgValues.placeholder(column) + GEOGRAPHY, PgValues.toJdbc(column, geometry));
    }
}
