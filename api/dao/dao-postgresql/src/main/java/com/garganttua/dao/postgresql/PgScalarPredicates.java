package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * The predicate of one comparison on one single-valued operand, with MongoDB's null and type semantics.
 *
 * <p>
 * SQL and MongoDB disagree on missing values, and a filter written for one must not change meaning
 * on the other. In SQL, {@code col <> 5} is NULL — not true — where {@code col} is NULL, so the row
 * is dropped; MongoDB's {@code $ne: 5} DOES match a document without the field. So every negative
 * operator is the negation of its positive form, with NULL counted as "does not match"
 * ({@link PgSql#not}): {@code $ne} is NOT {@code $eq}, {@code $nin} is NOT {@code $in}. The ordering
 * operators exclude NULL in both worlds.
 * </p>
 *
 * <p>
 * Values are compared the way MongoDB compares them — only within one type class, numbers exactly,
 * text in binary code-point order — see {@link PgFilterValues}. A JSON value that is an array
 * matches when the array itself or one of its elements does, as a MongoDB array field does.
 * </p>
 *
 * <p>
 * {@code $regex} uses PostgreSQL's POSIX {@code ~}, not PCRE: the common syntax (anchors, classes,
 * quantifiers, alternation, {@code \d}, {@code \w}, {@code \s}) behaves the same, but possessive
 * quantifiers, named groups and recursion do not exist, and embedded options such as {@code (?i)}
 * are only accepted at the very start of the pattern. {@code $geoWithin}/{@code $geoWithinSphere} use PostGIS
 * {@code ST_Within} in SRID 4326 — a planar test in degrees, where MongoDB's 2dsphere index tests
 * on the sphere; results differ only for shapes large enough for the curvature to matter.
 * </p>
 */
final class PgScalarPredicates {

    /** The alias of one element of a JSON array, inside {@code jsonb_array_elements}. */
    private static final String JSON_ELEMENT_ALIAS = "x";

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
     * @throws ApiException when the operator does not apply to the operand
     */
    PgSql on(PgOperand o, PgCondition c) throws ApiException {
        return switch (c.op()) {
            case "$eq" -> c.value() == null ? isNull(o) : positive(o, "=", c.value());
            case "$ne" -> c.value() == null ? isNotNull(o) : PgSql.not(positive(o, "=", c.value()));
            case "$gt" -> c.value() == null ? PgSql.FALSE : positive(o, ">", c.value());
            case "$lt" -> c.value() == null ? PgSql.FALSE : positive(o, "<", c.value());
            case "$gte" -> c.value() == null ? isNull(o) : positive(o, ">=", c.value());
            case "$lte" -> c.value() == null ? isNull(o) : positive(o, "<=", c.value());
            case "$regex" -> regex(o, c);
            case "$empty" -> isNull(o);
            case "$in" -> in(o, c);
            case "$nin" -> PgSql.not(in(o, c));
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

    /**
     * {@code o <op> value}; on a JSON operand, also true when the JSON value is an array one of whose
     * elements satisfies it — MongoDB's array semantics, applied inside documents.
     */
    private PgSql positive(PgOperand o, String op, Object value) {
        PgSql direct = PgFilterValues.compare(o, op, value);
        if (!o.isJson()) {
            return direct;
        }
        PgSql inElement = PgFilterValues.compare(PgOperand.jsonElement(JSON_ELEMENT_ALIAS), op, value);
        if (PgSql.FALSE.equals(inElement)) {
            return direct;
        }
        PgSql json = o.json();
        PgSql elements = json.wrap("EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(",
                ") = 'array' THEN ").then(json).then(" END) AS " + JSON_ELEMENT_ALIAS + "(v) WHERE ")
                .then(inElement).then(")");
        return PgSql.any(List.of(direct, elements));
    }

    private PgSql regex(PgOperand o, PgCondition c) throws ApiException {
        if (c.value() == null) {
            throw new ApiException("$regex filter on field '" + c.field() + "' requires a pattern");
        }
        if (!o.isText()) {
            throw new ApiException("$regex filter on field '" + c.field() + "' of domain '" + domain
                    + "': the field is " + o.column().sqlType() + ", and a regular expression only matches text");
        }
        return o.expr(false).then(" ~ ").then(bind(o, false, c.value().toString(), c.field()));
    }

    /**
     * Some listed value matches — or, when null is listed, the field is missing. Values of another
     * type class than the operand's are dropped: they can equal nothing.
     */
    private PgSql in(PgOperand o, PgCondition c) {
        List<PgSql> parts = new ArrayList<>();
        if (c.listsNull()) {
            parts.add(isNull(o));
        }
        if (o.isJson()) {
            for (Object value : c.listedValues()) {
                parts.add(positive(o, "=", value));
            }
            return PgSql.any(parts);
        }
        List<PgSql> binds = new ArrayList<>();
        for (Object value : c.listedValues()) {
            PgFilterValues.bound(o, value).ifPresent(b -> binds.add(b.value()));
        }
        if (!binds.isEmpty()) {
            parts.add(o.expr(false).then(" IN (").then(PgSql.join(", ", binds)).then(")"));
        }
        return PgSql.any(parts);
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
