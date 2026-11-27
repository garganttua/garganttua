package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.api.commons.ApiException;

/**
 * The predicate of one comparison on one single-valued operand, with MongoDB's null and type semantics.
 *
 * <p>
 * SQL and MongoDB disagree on missing values, and a filter written for one must not change meaning
 * on the other. In SQL, {@code col <> 5} is NULL — not true — where {@code col} is NULL, so the row
 * is dropped; MongoDB's {@code $ne: 5} DOES match a document without the field. So every negative
 * operator is the negation of its positive form, with NULL counted as "does not match"
 * ({@link SqlFragment#not}): {@code $ne} is NOT {@code $eq}, {@code $nin} is NOT {@code $in}. The ordering
 * operators exclude NULL in both worlds.
 * </p>
 *
 * <p>
 * Values are compared the way MongoDB compares them — only within one type class, numbers exactly,
 * text in binary code-point order — see {@link SqlFilterValues}. A JSON value that is an array
 * matches when the array itself or one of its elements does, as a MongoDB array field does.
 * </p>
 *
 * <p>
 * {@code $regex} is delegated to the dialect ({@link ISqlDialect#regex}): the PCRE pattern is translated to an equivalent
 * PostgreSQL pattern, and matches strings only, as in MongoDB. The geographic operators are delegated
 * to the dialect ({@link ISqlDialect#geoWithin}), which evaluates them on the sphere, as MongoDB's 2dsphere index does.
 * </p>
 */
public final class SqlScalarPredicates {

    /** The alias of one element of a JSON array, inside {@code jsonb_array_elements}. */
    private static final String JSON_ELEMENT_ALIAS = "x";

    private final String domain;
    private final ISqlDialect dialect;

    SqlScalarPredicates(String domain, ISqlDialect dialect) {
        this.domain = domain;
        this.dialect = dialect;
    }

    /**
     * The predicate of a comparison on an operand.
     *
     * @param o the operand
     * @param c the comparison (never {@code $text}, which has no operand)
     * @return the predicate
     * @throws ApiException when the operator does not apply to the operand
     */
    SqlFragment on(SqlOperand o, SqlCondition c) throws ApiException {
        return switch (c.op()) {
            case "$eq" -> c.value() == null ? isNull(o) : positive(o, "=", c.value());
            case "$ne" -> c.value() == null ? isNotNull(o) : SqlFragment.not(positive(o, "=", c.value()));
            case "$gt" -> c.value() == null ? SqlFragment.FALSE : positive(o, ">", c.value());
            case "$lt" -> c.value() == null ? SqlFragment.FALSE : positive(o, "<", c.value());
            case "$gte" -> c.value() == null ? isNull(o) : positive(o, ">=", c.value());
            case "$lte" -> c.value() == null ? isNull(o) : positive(o, "<=", c.value());
            case "$regex" -> regex(o, c);
            case "$empty" -> isNull(o);
            case "$in" -> in(o, c);
            case "$nin" -> SqlFragment.not(in(o, c));
            case "$geoWithin", "$geoWithinSphere" -> dialect.geoWithin(o, c, domain);
            case "$geoIntersects" -> dialect.geoIntersects(o, c, domain);
            case "$near", "$nearSphere" -> dialect.geoNear(o, c, domain);
            default -> throw new ApiException("Unsupported comparison operator: " + c.op());
        };
    }

    /** {@code o IS NULL} — also the "absent" test Mongo's {@code $exists: false} performs. */
    SqlFragment isNull(SqlOperand o) {
        return o.expr(false).then(" IS NULL");
    }

    private SqlFragment isNotNull(SqlOperand o) {
        return o.expr(false).then(" IS NOT NULL");
    }

    /**
     * {@code o <op> value}; on a JSON operand, also true when the JSON value is an array one of whose
     * elements satisfies it — MongoDB's array semantics, applied inside documents.
     */
    private SqlFragment positive(SqlOperand o, String op, Object value) {
        SqlFragment direct = SqlFilterValues.compare(dialect, o, op, value);
        if (!o.isJson()) {
            return direct;
        }
        SqlFragment inElement = SqlFilterValues.compare(dialect, SqlOperand.jsonElement(dialect, JSON_ELEMENT_ALIAS), op, value);
        if (SqlFragment.FALSE.equals(inElement)) {
            return direct;
        }
        SqlFragment elements = dialect.jsonArrayAny(o.json(), JSON_ELEMENT_ALIAS, inElement);
        return SqlFragment.any(List.of(direct, elements));
    }

    private SqlFragment regex(SqlOperand o, SqlCondition c) throws ApiException {
        return dialect.regex(o, c, domain);
    }

    /**
     * Some listed value matches — or, when null is listed, the field is missing. Values of another
     * type class than the operand's are dropped: they can equal nothing.
     */
    private SqlFragment in(SqlOperand o, SqlCondition c) {
        List<SqlFragment> parts = new ArrayList<>();
        if (c.listsNull()) {
            parts.add(isNull(o));
        }
        if (o.isJson()) {
            for (Object value : c.listedValues()) {
                parts.add(positive(o, "=", value));
            }
            return SqlFragment.any(parts);
        }
        List<SqlFragment> binds = new ArrayList<>();
        for (Object value : c.listedValues()) {
            SqlFilterValues.bound(dialect, o, value).ifPresent(b -> binds.add(b.value()));
        }
        if (!binds.isEmpty()) {
            parts.add(o.expr(false).then(" IN (").then(SqlFragment.join(", ", binds)).then(")"));
        }
        return SqlFragment.any(parts);
    }
}
