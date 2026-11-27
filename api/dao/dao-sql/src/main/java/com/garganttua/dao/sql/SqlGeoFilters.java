package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.geojson.LngLatAlt;
import org.geojson.Point;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * What MongoDB demands of a geographic filter, checked the same way by every SQL DAO before it spells the predicate:
 * a geometry field, a GeoJSON value, a point for {@code $near}, and at most one {@code $near}, at the top level.
 */
public final class SqlGeoFilters {

    private static final String OP_NEAR = "$near";
    private static final String OP_NEAR_SPHERE = "$nearSphere";
    private static final String FIELD_OPERATOR = "$field";
    private static final String ON_FIELD = " filter on field '";
    private static final int MAX_NEARS = 1;

    private SqlGeoFilters() {
    }

    /**
     * The one {@code $near} clause of a filter, if any.
     *
     * @param filter the whole filter
     * @return its {@code $field} clause holding the {@code $near}
     * @throws ApiException when it holds two, or one under {@code $or} / {@code $nor}, as MongoDB refuses
     */
    public static Optional<IFilter> nearClause(IFilter filter) throws ApiException {
        List<IFilter> nears = new ArrayList<>();
        collect(filter, null, nears);
        if (nears.size() > MAX_NEARS) {
            throw new ApiException("A filter may hold only one $near: MongoDB refuses a second one "
                    + "('Too many geoNear expressions')");
        }
        return nears.stream().findFirst();
    }

    /**
     * The geometry column a {@code $near} clause sorts on.
     *
     * @param table  the table
     * @param clause the clause, from {@link #nearClause}
     * @return the column
     * @throws ApiException when the field is not a geometry of the entity itself
     */
    public static SqlColumn nearColumn(SqlTable table, IFilter clause) throws ApiException {
        String field = String.valueOf(clause.getValue());
        return table.column(field).filter(c -> c.kind() == SqlColumnKind.GEOMETRY)
                .orElseThrow(() -> new ApiException("$near on field '" + field + "' of domain '" + table.name()
                        + "': the field is not a geometry of the entity itself; only a GeoJSON field can be "
                        + "searched by distance"));
    }

    /**
     * The point of a {@code $near} clause.
     *
     * @param clause the clause, from {@link #nearClause}
     * @return the point
     * @throws ApiException when it is no point, or has a non-finite coordinate
     */
    public static Point nearPoint(IFilter clause) throws ApiException {
        return point(clause.getFilters().get(0).getValue(), String.valueOf(clause.getValue()));
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

    /**
     * A finite coordinate.
     *
     * @param value the coordinate
     * @return it
     * @throws ApiException when it is NaN or infinite
     */
    public static double coordinate(double value) throws ApiException {
        if (!Double.isFinite(value)) {
            throw new ApiException("$near point has a non-finite coordinate: " + value);
        }
        return value;
    }

    /**
     * The value of a {@code $near} condition.
     *
     * @param c the condition
     * @return its point and distances
     * @throws ApiException when it is not a {@link GeoNear} around a point
     */
    public static GeoNear nearValue(SqlCondition c) throws ApiException {
        if (!(c.value() instanceof GeoNear near)) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a point and distances "
                    + "({$geometry: <Point>, $maxDistance: <metres>})");
        }
        point(near.geometry(), c.field());
        return near;
    }

    private static Point point(Object value, String field) throws ApiException {
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

    /**
     * Checks the operand of a geographic condition.
     *
     * @param o      the operand
     * @param c      the condition
     * @param domain the domain, for messages
     * @throws ApiException when the condition has no value, or the field is not a geometry
     */
    public static void requireGeometry(SqlOperand o, SqlCondition c, String domain) throws ApiException {
        if (c.value() == null) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a GeoJSON geometry value");
        }
        if (o.isJsonPath() || o.column().kind() != SqlColumnKind.GEOMETRY) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' of domain '" + domain
                    + "': the field is not a geometry (it is " + o.column().sqlType()
                    + "); only a GeoJSON field can be tested geographically");
        }
    }

    /**
     * Checks the geometry a condition compares with.
     *
     * @param geometry the geometry
     * @param c        the condition
     * @throws ApiException when there is none
     */
    public static void requireShape(Object geometry, SqlCondition c) throws ApiException {
        if (geometry == null) {
            throw new ApiException(c.op() + ON_FIELD + c.field() + "' requires a GeoJSON geometry value");
        }
    }
}
