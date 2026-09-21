package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

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
 * <b>{@code $text}</b> has no text index to consult: it matches
 * {@code to_tsvector('simple', <every TEXT column of the main table>)} against
 * {@code plainto_tsquery('simple', value)} — every word of the search must occur (MongoDB matches ANY
 * word), with no stemming and no stop words (MongoDB stems per language), and child tables and JSONB
 * documents are not searched. Like MongoDB, it ignores the field the filter names.
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
        this.elements = new PgElementPredicates(scalars);
    }

    /**
     * Translates a filter.
     *
     * @param filter the filter, possibly null (every row matches)
     * @return the {@code WHERE} expression and its values
     * @throws ApiException when the filter is malformed (an unknown FIELD is not an error: it is absent)
     */
    PgSql translate(IFilter filter) throws ApiException {
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
            case PgField.Opaque o -> opaque(o, condition);
        };
    }

    /**
     * Something without a comparable value: an embedded object, a whole collection of objects, a map,
     * a reference (a DBRef sub-document on MongoDB), or a path no document holds. A scalar never
     * equals an object, so MongoDB answers from presence alone: the "missing value" answer where it
     * is absent (or null), the "different value" answer where it is there.
     */
    private static PgSql opaque(PgField.Opaque o, PgCondition c) throws ApiException {
        return switch (c.op()) {
            case "$empty" -> o.absent();
            case "$eq", "$gte", "$lte" -> c.value() == null ? o.nullish() : PgSql.FALSE;
            case "$ne" -> c.value() == null ? PgSql.not(o.nullish()) : PgSql.TRUE;
            case "$in" -> c.listsNull() ? o.nullish() : PgSql.FALSE;
            case "$nin" -> c.listsNull() ? PgSql.not(o.nullish()) : PgSql.TRUE;
            case "$regex" -> requireValue(c, "$regex filter on field '" + c.field() + "' requires a pattern");
            case "$geoWithin", "$geoWithinSphere" -> requireValue(c, "$geoWithin filter on field '" + c.field()
                    + "' requires a GeoJSON geometry value");
            default -> PgSql.FALSE;
        };
    }

    /** MongoFilterConverter fails on a missing operand whatever the field: so must this. */
    private static PgSql requireValue(PgCondition c, String message) throws ApiException {
        if (c.value() == null) {
            throw new ApiException(message);
        }
        return PgSql.FALSE;
    }

    private PgSql text(PgCondition c) throws ApiException {
        if (c.value() == null) {
            throw new ApiException("$text filter requires a search string");
        }
        List<PgColumn> texts = table.columns().stream()
                .filter(col -> col.kind() == PgColumnKind.SCALAR && PgTypes.TEXT.equals(col.sqlType())).toList();
        if (texts.isEmpty()) {
            throw new ApiException("$text filter on domain '" + table.name()
                    + "': the domain has no text field to search");
        }
        List<String> columns = texts.stream().map(col -> PgQuery.ALIAS + "." + PgNaming.quote(col.name())).toList();
        return PgSql.of("to_tsvector('simple', concat_ws(' ', " + String.join(", ", columns)
                + ")) @@ plainto_tsquery('simple', ?)", PgValues.toJdbc(texts.get(0), c.value().toString()));
    }
}
