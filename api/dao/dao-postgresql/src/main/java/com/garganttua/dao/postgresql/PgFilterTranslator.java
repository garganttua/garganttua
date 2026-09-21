package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Translates an {@link IFilter} tree into a {@code WHERE} expression over the main table {@code t}.
 *
 * <p>
 * It accepts exactly what {@code MongoFilterConverter} accepts — the same operators, the same
 * validation, the same error messages — so a filter that works on one store works on the other and
 * a filter one store refuses, the other refuses too. What it adds is the relational part: a field
 * may live in a child table (compared through {@code EXISTS}), in a flattened POJO or inside a JSONB
 * document; see {@link PgFieldResolver}.
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
 * Like MongoDB, it ignores the field the filter names.
 * </p>
 */
final class PgFilterTranslator {

    private static final Set<String> COMPARISONS = Set.of("$eq", "$ne", "$gt", "$gte", "$lt", "$lte", "$regex",
            "$empty", "$in", "$nin", "$text", "$geoWithin", "$geoWithinSphere");

    private final PgTable table;
    private final PgFieldResolver resolver;
    private final PgScalarPredicates scalars;
    private final PgElementPredicates elements;

    PgFilterTranslator(PgTable table) {
        this.table = table;
        this.resolver = new PgFieldResolver(table);
        this.scalars = new PgScalarPredicates(table.name());
        this.elements = new PgElementPredicates(table.name(), scalars);
    }

    /**
     * Translates a filter.
     *
     * @param filter the filter, possibly null (every row matches)
     * @return the {@code WHERE} expression and its values
     * @throws ApiException when the filter is malformed or names an unknown field
     */
    PgSql translate(IFilter filter) throws ApiException {
        PgTextSearch.validate(filter);
        return translate(filter, false);
    }

    private PgSql translate(IFilter filter, boolean negated) throws ApiException {
        if (filter == null || filter.getName() == null) {
            return PgSql.TRUE;
        }
        return switch (filter.getName()) {
            case "$and" -> logical(filter, "$and", negated);
            case "$or" -> logical(filter, "$or", negated);
            case "$nor" -> logical(filter, "$nor", true);
            case "$field" -> field(filter, negated);
            default -> throw new ApiException("Unsupported filter operator: " + filter.getName());
        };
    }

    private PgSql logical(IFilter filter, String operator, boolean negated) throws ApiException {
        List<IFilter> subs = filter.getFilters();
        if (subs == null || subs.size() < 2) {
            throw new ApiException("Logical operator " + operator + " requires at least 2 sub-filters");
        }
        List<PgSql> parts = new ArrayList<>();
        for (IFilter sub : subs) {
            parts.add(translate(sub, negated));
        }
        PgSql joined = PgSql.join("$and".equals(operator) ? " AND " : " OR ", parts).wrap("(", ")");
        return "$nor".equals(operator) ? joined.wrap("NOT ", "") : joined;
    }

    private PgSql field(IFilter filter, boolean negated) throws ApiException {
        if (!(filter.getValue() instanceof String fieldName)) {
            throw new ApiException("$field filter requires a field name as value");
        }
        List<IFilter> subs = filter.getFilters();
        if (subs == null || subs.size() != 1) {
            throw new ApiException("$field filter requires exactly 1 comparison sub-filter");
        }
        PgCondition condition = condition(fieldName, subs.get(0));
        PgSql predicate = "$text".equals(condition.op()) ? text(condition) : compare(condition);
        return negated ? predicate.wrap("COALESCE((", "), FALSE)") : predicate;
    }

    /** Lifts one comparison out of the tree, with MongoFilterConverter's validation. */
    private static PgCondition condition(String fieldName, IFilter comparison) throws ApiException {
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
        return new PgCondition(op, comparison.getValue(), values, fieldName);
    }

    private PgSql compare(PgCondition condition) throws ApiException {
        PgField field = resolver.resolve(condition.field());
        return switch (field) {
            case PgField.Scalar s -> scalars.on(s.operand(), condition);
            case PgField.Element e -> elements.on(e, condition);
            case PgField.Pojo p -> pojo(p, condition);
        };
    }

    /**
     * An embedded POJO has no single value to compare. It is absent — read back as null — exactly
     * when every one of its flattened columns is NULL, so presence is all a filter can ask of it.
     */
    private PgSql pojo(PgField.Pojo pojo, PgCondition c) throws ApiException {
        List<PgSql> nulls = new ArrayList<>();
        for (PgColumn column : pojo.columns()) {
            nulls.add(scalars.isNull(PgOperand.of(PgQuery.ALIAS, column)));
        }
        PgSql absent = PgSql.join(" AND ", nulls).wrap("(", ")");
        boolean askAbsent = "$empty".equals(c.op()) || "$eq".equals(c.op()) && c.value() == null;
        if (askAbsent) {
            return absent;
        }
        if ("$ne".equals(c.op()) && c.value() == null) {
            return absent.wrap("NOT ", "");
        }
        throw new ApiException("Field '" + c.field() + "' of domain '" + table.name() + "' is an embedded object:"
                + " only $empty, $eq null and $ne null apply to it; compare one of its fields ('"
                + c.field() + ".<field>') instead");
    }

    private PgSql text(PgCondition c) throws ApiException {
        return PgTextSearch.predicate(table, c.value());
    }
}
