package com.garganttua.dao.postgresql;

import com.garganttua.dao.sql.SqlValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.geojson.Point;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.SqlCondition;
import com.garganttua.dao.sql.SqlGeoFilters;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlOperand;
import com.garganttua.dao.sql.SqlQuery;

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

    private final String domain;

    PgGeoPredicates(String domain) {
        this.domain = domain;
    }

    SqlFragment within(SqlOperand o, SqlCondition c) throws ApiException {
        return SqlFragment.of("ST_CoveredBy(").then(field(o, c)).then(", ").then(shape(o, c.value(), c)).then(")");
    }

    SqlFragment intersects(SqlOperand o, SqlCondition c) throws ApiException {
        return SqlFragment.of("ST_Intersects(").then(field(o, c)).then(", ").then(shape(o, c.value(), c)).then(")");
    }

    SqlFragment near(SqlOperand o, SqlCondition c) throws ApiException {
        GeoNear near = SqlGeoFilters.nearValue(c);
        SqlFragment field = field(o, c);
        List<SqlFragment> parts = new ArrayList<>();
        parts.add(o.expr(false).then(" IS NOT NULL"));
        if (near.maxDistance() != null) {
            parts.add(SqlFragment.of("ST_DWithin(").then(field).then(", ").then(shape(o, near.geometry(), c))
                    .then(SqlFragment.of(", ?)", near.maxDistance())));
        }
        if (near.minDistance() != null && near.minDistance() > 0) {
            parts.add(SqlFragment.of("ST_Distance(").then(field).then(", ").then(shape(o, near.geometry(), c))
                    .then(SqlFragment.of(") >= ?", near.minDistance())));
        }
        return SqlFragment.join(" AND ", parts).wrap("(", ")");
    }

    static Optional<String> nearOrder(SqlTable table, IFilter filter) throws ApiException {
        Optional<IFilter> clause = SqlGeoFilters.nearClause(filter);
        if (clause.isEmpty()) {
            return Optional.empty();
        }
        SqlColumn column = SqlGeoFilters.nearColumn(table, clause.get());
        Point point = SqlGeoFilters.nearPoint(clause.get());
        String target = "'SRID=4326;POINT(" + coordinate(point.getCoordinates().getLongitude()) + " "
                + coordinate(point.getCoordinates().getLatitude()) + ")'::geography";
        return Optional.of("(" + SqlQuery.ALIAS + "." + SqlNaming.quote(column.name()) + GEOGRAPHY + " <-> " + target
                + " ASC");
    }

    private static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.15g", SqlGeoFilters.coordinate(value));
    }

    private SqlFragment field(SqlOperand o, SqlCondition c) throws ApiException {
        SqlGeoFilters.requireGeometry(o, c, this.domain);
        return o.expr(false).wrap("(", GEOGRAPHY);
    }

    private static SqlFragment shape(SqlOperand o, Object geometry, SqlCondition c) throws ApiException {
        SqlGeoFilters.requireShape(geometry, c);
        SqlColumn column = o.bindColumn(false);
        return SqlFragment.of("(" + SqlValues.placeholder(PgDialect.INSTANCE, column) + GEOGRAPHY,
                SqlValues.toJdbc(PgDialect.INSTANCE, column, geometry));
    }
}
