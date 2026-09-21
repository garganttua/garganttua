package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;

/**
 * Comparisons on a collection field, following MongoDB's array semantics over child tables — at any
 * depth.
 *
 * <p>
 * MongoDB applies a comparison on an array field to its ELEMENTS: {@code {tags: "a"}} matches when
 * SOME element is {@code "a"}, and the negative operators negate that whole statement —
 * {@code {tags: {$ne: "a"}}} matches when NO element is {@code "a"}, not when some element differs.
 * So a positive operator becomes {@code EXISTS (a child row matching)}, and {@code $ne} / {@code $nin}
 * become {@code NOT} of their positive counterpart ({@code $eq} / {@code $in}). A path crossing several
 * arrays ({@code orders.lines.sku}) is the same rule applied at every level: SOME order has SOME line
 * whose sku matches — one nested {@code EXISTS} per array ({@link PgHop#some}) — and {@code $ne} is
 * still the negation of the whole statement: NO order has a line whose sku matches.
 * </p>
 *
 * <p>
 * <b>Missing is not empty.</b> MongoDB keeps a null collection (absent from the document) apart from
 * an empty one ({@code []}, present). A comparison it satisfies with a missing value
 * ({@code $eq: null}, {@code $in} listing null) matches an absent collection, or an element that is
 * null — never an empty array; along a deeper path, an absent collection at ANY level on SOME branch
 * ({@link PgField.Element#missing}). {@code $empty} ({@code $exists: false}) on the collection itself
 * asks that it is absent on EVERY branch; on a path inside the elements it asks "no element has it",
 * which an empty array satisfies — MongoDB's own asymmetry, reproduced as is.
 * </p>
 *
 * <p>
 * A LIST compared with {@code $eq} is an exact, ordered array match, as in MongoDB:
 * {@code {tags: ["x", "y"]}} matches {@code ["x", "y"]} only, and {@code {tags: []}} the empty array.
 * When the elements are themselves arrays ({@code List<List<String>>}), MongoDB compares a value with
 * each element as a whole — a list matches an equal element, a scalar matches none (MongoDB does not
 * look inside nested arrays).
 * </p>
 */
final class PgElementPredicates {

    private static final String EQ = "$eq";
    private static final String IN = "$in";

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
            case "$ne" -> PgSql.not(positive(e, c.as(EQ)));
            case "$nin" -> PgSql.not(positive(e, c.as(IN)));
            case "$empty" -> empty(e);
            default -> positive(e, c);
        };
    }

    /**
     * {@code $exists: false}: the collection (the map entry) is absent on every branch, or no element
     * holds the path.
     */
    private PgSql empty(PgField.Element e) {
        boolean mapValue = e.child().kind() == PgChildKind.MAP && e.operand().column().fieldPath().isEmpty();
        if (e.whole() || mapValue) {
            return PgHop.noneHolds(e.hops());
        }
        return PgSql.not(PgHop.some(e.hops(), e.operand().expr(false).then(" IS NOT NULL"), false));
    }

    /** Some element matches — or, for a comparison a missing value satisfies, the path is missing. */
    private PgSql positive(PgField.Element e, PgCondition c) throws ApiException {
        if (e.items() != null) {
            return arraysOfArrays(e, c);
        }
        if (e.whole() && isList(c.value()) && EQ.equals(c.op())) {
            return PgHop.some(e.parents(), wholeArray(e.last(), e.operand(), (Collection<?>) c.value()), false);
        }
        if (e.whole() && IN.equals(c.op()) && c.values().stream().anyMatch(PgElementPredicates::isList)) {
            return inWithArrays(e, c);
        }
        return PgHop.some(e.hops(), scalars.on(e.operand(), c), c.matchesMissing());
    }

    /** {@code $in} listing whole arrays: each array is an exact match, the other values element matches. */
    private PgSql inWithArrays(PgField.Element e, PgCondition c) throws ApiException {
        List<PgSql> parts = new ArrayList<>();
        List<Object> scalarsListed = new ArrayList<>();
        for (Object value : c.values()) {
            if (isList(value)) {
                parts.add(PgHop.some(e.parents(), wholeArray(e.last(), e.operand(), (Collection<?>) value), false));
            } else {
                scalarsListed.add(value);
            }
        }
        if (!scalarsListed.isEmpty()) {
            parts.add(positive(e, new PgCondition(IN, null, scalarsListed, c.field())));
        }
        return PgSql.any(parts);
    }

    /**
     * A collection whose elements are arrays: a listed array matches an equal element, a missing value
     * matches an absent collection or a null element, and a scalar matches nothing.
     */
    private PgSql arraysOfArrays(PgField.Element e, PgCondition c) throws ApiException {
        List<Object> compared = switch (c.op()) {
            case EQ -> c.value() == null ? List.of() : List.of(c.value());
            case IN -> c.listedValues();
            default -> {
                // Validated like any comparison (a $regex without pattern still fails), then: no match.
                scalars.on(e.operand(), c);
                yield List.of();
            }
        };
        List<PgSql> parts = new ArrayList<>();
        if (c.matchesMissing()) {
            parts.add(PgHop.some(e.hops(), e.items().absent(), true));
        }
        for (Object value : compared) {
            if (isList(value)) {
                Collection<?> list = (Collection<?>) value;
                parts.add(PgHop.some(e.hops(), wholeArray(e.items(), e.operand(), list), false));
                if (list.isEmpty()) {
                    // [] also equals the collection itself when it is empty.
                    parts.add(PgHop.some(e.parents(), wholeArray(e.last(), e.operand(), list), false));
                }
            }
        }
        return PgSql.any(parts);
    }

    /**
     * The collection of {@code hop} is present and equals the list, element by element and in order: as
     * many rows as the list has elements, and at each position {@code _ord} a row equal to the listed
     * value. A predicate over the row HOLDING the collection.
     */
    private PgSql wholeArray(PgHop hop, PgOperand operand, Collection<?> list) throws ApiException {
        List<PgSql> parts = new ArrayList<>();
        parts.add(PgSql.not(hop.absent()));
        parts.add(hop.rows().wrap("(SELECT count(*) FROM (", ") AS n)").then(PgSql.of(" = ?", list.size())));
        int ord = 0;
        for (Object value : list) {
            PgSql equal = scalars.on(operand, new PgCondition(EQ, value, List.of(), ""));
            PgSql at = PgSql.of(hop.ref(PgChildTable.ORD) + " = ?", ord);
            ord++;
            parts.add(hop.rows(PgSql.all(List.of(at, equal))).wrap("EXISTS (", ")"));
        }
        return PgSql.all(parts);
    }

    private static boolean isList(Object value) {
        return value instanceof Collection<?>;
    }
}
