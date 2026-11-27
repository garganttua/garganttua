package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.SqlCondition;
import com.garganttua.dao.sql.SqlField;
import com.garganttua.dao.sql.SqlFragment;

/**
 * Translates an {@link IFilter} tree into a {@code WHERE} expression over the main table {@code t}.
 *
 * <p>
 * It accepts exactly what {@code MongoFilterConverter} accepts — the same operators, the same
 * validation, the same error messages — so a filter that works on one store works on the other and
 * a filter one store refuses, the other refuses too. What it adds is the relational part: a field
 * may live in a child table (compared through {@code EXISTS}), in a flattened POJO or inside a JSONB
 * document; see {@link PgFieldResolver}. A field the model does not know is ABSENT, as a field no
 * document holds is on MongoDB — under every operator and inside every logical operator — and never
 * reaches the SQL text.
 * </p>
 *
 * <p>
 * <b>Three-valued logic under {@code $nor}.</b> In SQL a comparison with NULL is neither true nor
 * false, and {@code NOT (NULL)} is still NULL: a row whose field is missing would be dropped by
 * {@code $nor: [{f: 5}]}, where MongoDB keeps it. Every comparison under a {@code $nor} is therefore
 * coerced to two values with {@code COALESCE(…, FALSE)}. Outside a negation, NULL and FALSE both
 * drop the row, so predicates stay bare there and can use indexes.
 * </p>
 *
 * <p>
 * <b>{@code $text}</b> is delegated to {@link PgTextSearch}: MongoDB's search over every string of
 * the entity, with its restrictions (one {@code $text}, never under {@code $or} or {@code $nor}).
 * Like MongoDB, it ignores the field the filter names. When the domain declares a text index, the
 * search covers that field only — what MongoDB's text index covers — and spells the index expression,
 * so the index serves it.
 * </p>
 */
final class PgFilterTranslator {

    private static final Set<String> COMPARISONS = Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$regex",
            "$empty", "$in", "$nin", "$text", "$geoWithin", "$geoWithinSphere", "$geoIntersects", "$near",
            "$nearSphere");

    private final SqlTable table;
    private final PgFieldResolver resolver;
    private final PgScalarPredicates scalars;
    private final PgElementPredicates elements;
    private final Optional<SqlColumn> textColumn;

    PgFilterTranslator(SqlTable table) {
        this(table, Optional.empty());
    }

    /**
     * @param table      the domain's relational shape
     * @param textColumn the column a declared text index covers, which {@code $text} then searches
     *                   alone; empty to search every string of the entity
     */
    PgFilterTranslator(SqlTable table, Optional<SqlColumn> textColumn) {
        this.table = table;
        this.textColumn = textColumn == null ? Optional.empty() : textColumn;
        this.resolver = new PgFieldResolver(table);
        this.scalars = new PgScalarPredicates(table.name());
        this.elements = new PgElementPredicates(scalars);
    }

    /**
     * Translates a filter.
     *
     * @param filter the filter, possibly null (every row matches)
     * @return the {@code WHERE} expression and its values
     * @throws ApiException when the filter is malformed (an unknown FIELD is not an error: it is absent)
     */
    SqlFragment translate(IFilter filter) throws ApiException {
        PgTextSearch.validate(filter);
        PgGeoPredicates.nearOrder(table, filter);
        return translate(filter, false);
    }

    /**
     * The nearest-first sort key a {@code $near} asks for, when the filter holds one.
     *
     * @param filter the whole filter, possibly null
     * @return the {@code ORDER BY} key, or empty
     * @throws ApiException when the {@code $near} breaks one of MongoDB's rules
     */
    Optional<String> nearOrder(IFilter filter) throws ApiException {
        return PgGeoPredicates.nearOrder(table, filter);
    }

    private SqlFragment translate(IFilter filter, boolean negated) throws ApiException {
        if (filter == null || filter.getName() == null) {
            return SqlFragment.TRUE;
        }
        return switch (filter.getName()) {
            case "$and" -> logical(filter, "$and", negated);
            case "$or" -> logical(filter, "$or", negated);
            case "$nor" -> logical(filter, "$nor", true);
            case "$field" -> field(filter, negated);
            default -> throw new ApiException("Unsupported filter operator: " + filter.getName());
        };
    }

    private SqlFragment logical(IFilter filter, String operator, boolean negated) throws ApiException {
        List<IFilter> subs = filter.getFilters();
        if (subs == null || subs.size() < 2) {
            throw new ApiException("Logical operator " + operator + " requires at least 2 sub-filters");
        }
        List<SqlFragment> parts = new ArrayList<>();
        for (IFilter sub : subs) {
            parts.add(translate(sub, negated));
        }
        SqlFragment joined = SqlFragment.join("$and".equals(operator) ? " AND " : " OR ", parts).wrap("(", ")");
        return "$nor".equals(operator) ? joined.wrap("NOT ", "") : joined;
    }

    private SqlFragment field(IFilter filter, boolean negated) throws ApiException {
        if (!(filter.getValue() instanceof String fieldName)) {
            throw new ApiException("$field filter requires a field name as value");
        }
        List<IFilter> subs = filter.getFilters();
        if (subs == null || subs.size() != 1) {
            throw new ApiException("$field filter requires exactly 1 comparison sub-filter");
        }
        SqlCondition condition = condition(fieldName, subs.get(0));
        SqlFragment predicate = "$text".equals(condition.op()) ? text(condition) : compare(condition);
        return negated ? predicate.wrap("COALESCE((", "), FALSE)") : predicate;
    }

    /** Lifts one comparison out of the tree, with MongoFilterConverter's validation. */
    private static SqlCondition condition(String fieldName, IFilter comparison) throws ApiException {
        String op = comparison.getName();
        if (op == null || !COMPARISONS.contains(op)) {
            throw new ApiException("Unsupported comparison operator: " + op);
        }
        List<Object> values = new ArrayList<>();
        if ("$in".equals(op) || "$nin".equals(op)) {
            List<IFilter> listed = comparison.getFilters();
            if (listed == null || listed.isEmpty()) {
                throw new ApiException(op + " operator requires at least 1 value");
            }
            for (IFilter value : listed) {
                values.add(value.getValue());
            }
        }
        return new SqlCondition(op, comparison.getValue(), values, fieldName);
    }

    private SqlFragment compare(SqlCondition condition) throws ApiException {
        SqlField field = resolver.resolve(condition.field());
        return switch (field) {
            case SqlField.Scalar s -> scalars.on(s.operand(), condition);
            case SqlField.Element e -> elements.on(e, condition);
            case SqlField.Opaque o -> opaque(o, condition);
        };
    }

    /**
     * Something without a comparable value: an embedded object, a whole collection of objects, a map,
     * a reference (a DBRef sub-document on MongoDB), or a path no document holds. A scalar never
     * equals an object, so MongoDB answers from presence alone: the "missing value" answer where it
     * is absent (or null), the "different value" answer where it is there.
     */
    private static SqlFragment opaque(SqlField.Opaque o, SqlCondition c) throws ApiException {
        return switch (c.op()) {
            case "$empty" -> o.absent();
            case "$eq", "$gte", "$lte" -> c.value() == null ? o.nullish() : SqlFragment.FALSE;
            case "$ne" -> c.value() == null ? SqlFragment.not(o.nullish()) : SqlFragment.TRUE;
            case "$in" -> c.listsNull() ? o.nullish() : SqlFragment.FALSE;
            case "$nin" -> c.listsNull() ? SqlFragment.not(o.nullish()) : SqlFragment.TRUE;
            case "$regex" -> requireValue(c, "$regex filter on field '" + c.field() + "' requires a pattern");
            case "$geoWithin", "$geoWithinSphere", "$geoIntersects", "$near", "$nearSphere" -> requireValue(c,
                    c.op() + " filter on field '" + c.field() + "' requires a GeoJSON geometry value");
            default -> SqlFragment.FALSE;
        };
    }

    /** MongoFilterConverter fails on a missing operand whatever the field: so must this. */
    private static SqlFragment requireValue(SqlCondition c, String message) throws ApiException {
        if (c.value() == null) {
            throw new ApiException(message);
        }
        return SqlFragment.FALSE;
    }

    private SqlFragment text(SqlCondition c) throws ApiException {
        return textColumn.isPresent() ? PgTextSearch.predicateOnIndexed(textColumn.get(), c.value())
                : PgTextSearch.predicate(table, c.value());
    }
}
