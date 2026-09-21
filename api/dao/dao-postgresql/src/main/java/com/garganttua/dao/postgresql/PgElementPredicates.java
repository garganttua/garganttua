package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * Comparisons on a collection field, following MongoDB's array semantics over a child table.
 *
 * <p>
 * MongoDB applies a comparison on an array field to its ELEMENTS: {@code {tags: "a"}} matches when
 * SOME element is {@code "a"}, and the negative operators negate that whole statement —
 * {@code {tags: {$ne: "a"}}} matches when NO element is {@code "a"}, not when some element differs.
 * So a positive operator becomes {@code EXISTS (a child row matching)}, and {@code $ne} / {@code $nin}
 * become {@code NOT} of their positive counterpart ({@code $eq} / {@code $in}).
 * </p>
 *
 * <p>
 * <b>Missing is not empty.</b> MongoDB keeps a null collection (absent from the document) apart from
 * an empty one ({@code []}, present). A comparison it satisfies with a missing value
 * ({@code $eq: null}, {@code $in} listing null) matches an absent collection, or an element that is
 * null — never an empty array. The absent case is the owner's presence bit being NULL
 * ({@link PgField.Element#missing}). {@code $empty} ({@code $exists: false}) on the collection itself
 * asks the same bit; on a path inside the elements it asks "no element has it", which an empty array
 * satisfies — MongoDB's own asymmetry, reproduced as is.
 * </p>
 *
 * <p>
 * A LIST compared with {@code $eq} is an exact, ordered array match, as in MongoDB:
 * {@code {tags: ["x", "y"]}} matches {@code ["x", "y"]} only, and {@code {tags: []}} the empty array.
 * </p>
 */
final class PgElementPredicates {

    private final PgScalarPredicates scalars;

    PgElementPredicates(PgScalarPredicates scalars) {
        this.scalars = scalars;
    }

    /**
     * The predicate of a comparison on something inside a collection.
     *
     * @param e the resolved element
     * @param c the comparison
     * @return the predicate — always true or false, never NULL
     * @throws ApiException when the operator does not apply
     */
    PgSql on(PgField.Element e, PgCondition c) throws ApiException {
        return switch (c.op()) {
            case "$ne" -> PgSql.not(positive(e, c.as("$eq")));
            case "$nin" -> PgSql.not(positive(e, c.as("$in")));
            case "$empty" -> empty(e);
            default -> positive(e, c);
        };
    }

    /** {@code $exists: false}: the collection is absent, or no element (no map entry) holds the path. */
    private PgSql empty(PgField.Element e) {
        if (e.whole()) {
            return e.missing();
        }
        boolean mapValue = e.child().kind() == PgChildKind.MAP && e.operand().column().fieldPath().isEmpty();
        return mapValue ? e.missing() : noRow(e, e.operand().expr(false).then(" IS NOT NULL"));
    }

    /** Some element matches — or, for a comparison a missing value satisfies, the path is missing. */
    private PgSql positive(PgField.Element e, PgCondition c) throws ApiException {
        if (e.whole() && isList(c.value()) && "$eq".equals(c.op())) {
            return wholeArray(e, (Collection<?>) c.value());
        }
        if (e.whole() && "$in".equals(c.op()) && c.values().stream().anyMatch(PgElementPredicates::isList)) {
            return inWithArrays(e, c);
        }
        PgSql predicate = scalars.on(e.operand(), c);
        PgSql exists = PgSql.FALSE.equals(predicate) ? PgSql.FALSE : subquery(e, predicate).wrap("EXISTS (", ")");
        return c.matchesMissing() ? PgSql.any(List.of(exists, e.missing())) : exists;
    }

    /** {@code $in} listing whole arrays: each array is an exact match, the other values element matches. */
    private PgSql inWithArrays(PgField.Element e, PgCondition c) throws ApiException {
        List<PgSql> parts = new ArrayList<>();
        List<Object> scalarsListed = new ArrayList<>();
        for (Object value : c.values()) {
            if (isList(value)) {
                parts.add(wholeArray(e, (Collection<?>) value));
            } else {
                scalarsListed.add(value);
            }
        }
        if (!scalarsListed.isEmpty()) {
            parts.add(positive(e, new PgCondition("$in", null, scalarsListed, c.field())));
        }
        return PgSql.any(parts);
    }

    /**
     * The collection is present and equals the list, element by element and in order: as many rows
     * as the list has elements, and at each position {@code _ord} a row equal to the listed value.
     */
    private PgSql wholeArray(PgField.Element e, Collection<?> list) throws ApiException {
        List<PgSql> parts = new ArrayList<>();
        parts.add(PgSql.not(e.missing()));
        parts.add(subquery(e, null).wrap("(SELECT count(*) FROM (", ") AS n)")
                .then(PgSql.of(" = ?", list.size())));
        int ord = 0;
        for (Object value : list) {
            PgSql equal = scalars.on(e.operand(), new PgCondition("$eq", value, List.of(), ""));
            PgSql at = PgSql.of(PgFieldResolver.CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.ORD) + " = ?", ord++);
            parts.add(subquery(e, PgSql.all(List.of(at, equal))).wrap("EXISTS (", ")"));
        }
        return PgSql.all(parts);
    }

    /** {@code NOT EXISTS} a row in scope, optionally further narrowed. */
    private static PgSql noRow(PgField.Element e, PgSql narrowing) {
        return subquery(e, narrowing).wrap("NOT EXISTS (", ")");
    }

    private static PgSql subquery(PgField.Element e, PgSql predicate) {
        PgSql select = PgFieldResolver.rows(e.child(), e.scope());
        return predicate == null ? select : select.then(" AND (").then(predicate).then(")");
    }

    private static boolean isList(Object value) {
        return value instanceof Collection<?>;
    }
}
