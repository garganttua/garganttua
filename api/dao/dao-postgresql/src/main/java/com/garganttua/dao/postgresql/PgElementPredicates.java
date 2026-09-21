package com.garganttua.dao.postgresql;

import java.util.List;

import com.garganttua.api.commons.ApiException;
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
 * A null or empty collection is stored as zero child rows (the DAO's documented divergence from
 * MongoDB, which keeps null and {@code []} apart). A comparison MongoDB satisfies with a missing value
 * ({@code $eq: null}, {@code $in} listing null) therefore also matches an owner that has no matching
 * row at all, and {@code $empty} on the collection itself means "no rows".
 * </p>
 */
final class PgElementPredicates {

    private final String domain;
    private final PgScalarPredicates scalars;

    PgElementPredicates(String domain, PgScalarPredicates scalars) {
        this.domain = domain;
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
        if (e.operand() == null) {
            return presenceOnly(e, c);
        }
        return switch (c.op()) {
            case "$ne" -> positive(e, c.as("$eq")).wrap("NOT (", ")");
            case "$nin" -> positive(e, c.as("$in")).wrap("NOT (", ")");
            case "$empty" -> e.whole() ? noRow(e, null) : noRow(e, e.operand().expr(false).then(" IS NOT NULL"));
            default -> positive(e, c);
        };
    }

    /** Some element matches — or, for a comparison a missing value satisfies, no element exists. */
    private PgSql positive(PgField.Element e, PgCondition c) throws ApiException {
        PgSql exists = subquery(e, scalars.on(e.operand(), c)).wrap("EXISTS (", ")");
        if (!c.matchesMissing()) {
            return exists;
        }
        return PgSql.join(" OR ", List.of(exists, noRow(e, null))).wrap("(", ")");
    }

    /** Elements that are objects (POJO collections, maps, a map entry of POJOs): presence only. */
    private PgSql presenceOnly(PgField.Element e, PgCondition c) throws ApiException {
        boolean askAbsent = "$empty".equals(c.op()) || "$eq".equals(c.op()) && c.value() == null;
        if (askAbsent) {
            return noRow(e, null);
        }
        if ("$ne".equals(c.op()) && c.value() == null) {
            return noRow(e, null).wrap("NOT (", ")");
        }
        throw new ApiException("Field '" + c.field() + "' of domain '" + domain + "' holds objects:"
                + " only $empty, $eq null and $ne null apply to it; compare one of their fields ('"
                + c.field() + ".<field>') instead");
    }

    /** {@code NOT EXISTS} a row in scope, optionally further narrowed. */
    private PgSql noRow(PgField.Element e, PgSql narrowing) {
        return subquery(e, narrowing).wrap("NOT EXISTS (", ")");
    }

    private static PgSql subquery(PgField.Element e, PgSql predicate) {
        PgSql select = PgSql.of("SELECT 1 FROM " + PgNaming.quote(e.child().name()) + " "
                + PgFieldResolver.CHILD_ALIAS + " WHERE ").then(e.scope());
        return predicate == null ? select : select.then(" AND (").then(predicate).then(")");
    }
}
