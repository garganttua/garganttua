package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * The predicate of one comparison on one single-valued operand, with MongoDB's null semantics.
 *
 * <p>
 * SQL and MongoDB disagree on missing values, and a filter written for one must not change meaning
 * on the other. In SQL, {@code col <> 5} is NULL — not true — where {@code col} is NULL, so the row
 * is dropped; MongoDB's {@code $ne: 5} DOES match a document without the field. Hence
 * {@code IS DISTINCT FROM} for {@code $ne}, an explicit {@code IS NULL OR} for {@code $nin}, and
 * {@code IS NULL} for {@code $eq: null}. The ordering operators exclude NULL in both worlds.
 * </p>
 *
 * <p>
 * {@code $regex} is delegated to {@link PgRegex}: the PCRE pattern is translated to an equivalent
 * PostgreSQL pattern, and matches strings only, as in MongoDB. {@code $geoWithin}/{@code $geoWithinSphere} use PostGIS
 * {@code ST_Within} in SRID 4326 — a planar test in degrees, where MongoDB's 2dsphere index tests
 * on the sphere; results differ only for shapes large enough for the curvature to matter.
 * </p>
 */
final class PgScalarPredicates {

    private final String domain;

    PgScalarPredicates(String domain) {
        this.domain = domain;
    }

    /**
     * The predicate of a comparison on an operand.
     *
     * @param o the operand
     * @param c the comparison (never {@code $text}, which has no operand)
     * @return the predicate
     * @throws ApiException when the operator does not apply to the operand, or a value does not fit
     */
    PgSql on(PgOperand o, PgCondition c) throws ApiException {
        return switch (c.op()) {
            case "$eq" -> c.value() == null ? isNull(o) : compare(o, " = ", c);
            case "$ne" -> c.value() == null ? isNotNull(o) : compare(o, " IS DISTINCT FROM ", c);
            case "$gt" -> c.value() == null ? PgSql.FALSE : compare(o, " > ", c);
            case "$lt" -> c.value() == null ? PgSql.FALSE : compare(o, " < ", c);
            case "$gte" -> c.value() == null ? isNull(o) : compare(o, " >= ", c);
            case "$lte" -> c.value() == null ? isNull(o) : compare(o, " <= ", c);
            case "$regex" -> regex(o, c);
            case "$empty" -> isNull(o);
            case "$in" -> in(o, c);
            case "$nin" -> notIn(o, c);
            case "$geoWithin", "$geoWithinSphere" -> geoWithin(o, c);
            default -> throw new ApiException("Unsupported comparison operator: " + c.op());
        };
    }

    /** {@code o IS NULL} — also the "absent" test Mongo's {@code $exists: false} performs. */
    PgSql isNull(PgOperand o) {
        return o.expr(false).then(" IS NULL");
    }

    private PgSql isNotNull(PgOperand o) {
        return o.expr(false).then(" IS NOT NULL");
    }

    private PgSql compare(PgOperand o, String operator, PgCondition c) throws ApiException {
        boolean numeric = o.isJsonPath() && c.value() instanceof Number;
        return o.expr(numeric).then(operator).then(bind(o, numeric, c.value(), c.field()));
    }

    private PgSql regex(PgOperand o, PgCondition c) throws ApiException {
        return PgRegex.on(o, c, domain);
    }

    private PgSql in(PgOperand o, PgCondition c) throws ApiException {
        List<Object> listed = c.listedValues();
        if (listed.isEmpty()) {
            return isNull(o);
        }
        PgSql in = inList(o, listed, " IN (", c.field());
        return c.listsNull() ? PgSql.join(" OR ", List.of(isNull(o), in)).wrap("(", ")") : in;
    }

    /** Mongo {@code $nin} matches a missing field — unless null is itself one of the excluded values. */
    private PgSql notIn(PgOperand o, PgCondition c) throws ApiException {
        List<Object> listed = c.listedValues();
        if (listed.isEmpty()) {
            return isNotNull(o);
        }
        PgSql notIn = inList(o, listed, " NOT IN (", c.field());
        return c.listsNull()
                ? PgSql.join(" AND ", List.of(isNotNull(o), notIn)).wrap("(", ")")
                : PgSql.join(" OR ", List.of(isNull(o), notIn)).wrap("(", ")");
    }

    private PgSql inList(PgOperand o, List<Object> listed, String keyword, String field) throws ApiException {
        boolean numeric = o.isJsonPath() && listed.stream().allMatch(Number.class::isInstance);
        List<PgSql> binds = new ArrayList<>();
        for (Object value : listed) {
            binds.add(bind(o, numeric, value, field));
        }
        return o.expr(numeric).then(keyword).then(PgSql.join(", ", binds)).then(")");
    }

    private PgSql geoWithin(PgOperand o, PgCondition c) throws ApiException {
        if (c.value() == null) {
            throw new ApiException("$geoWithin filter on field '" + c.field() + "' requires a GeoJSON geometry value");
        }
        if (o.isJsonPath() || o.column().kind() != PgColumnKind.GEOMETRY) {
            throw new ApiException("$geoWithin filter on field '" + c.field() + "' of domain '" + domain
                    + "': the field is not a geometry (it is " + o.column().sqlType()
                    + "); only a GeoJSON field, stored as a PostGIS geometry, can be tested for containment");
        }
        return o.expr(false).wrap("ST_Within(", ", ").then(bind(o, false, c.value(), c.field())).then(")");
    }

    /** One bound value: the placeholder and the value, both from {@link PgValues}. */
    private PgSql bind(PgOperand o, boolean numeric, Object value, String field) throws ApiException {
        PgColumn column = o.bindColumn(numeric);
        if ((value instanceof Collection<?> || value instanceof Object[]) && column.kind() != PgColumnKind.JSONB) {
            throw new ApiException("Field '" + field + "' of domain '" + domain + "' is compared with a list of"
                    + " values; compare one value at a time, or use $in to match any of several");
        }
        return PgSql.of(PgValues.placeholder(column), PgValues.toJdbc(column, value));
    }
}
