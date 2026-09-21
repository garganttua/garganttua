package com.garganttua.dao.postgresql;

import java.util.List;

import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * One step of a field path through the child tables: the rows of one collection — or of one map
 * entry — under the row that holds it.
 *
 * <p>
 * A path such as {@code orders.lines.sku} crosses TWO arrays; MongoDB matches it when SOME order has
 * SOME line whose sku matches. Relationally that is one {@code EXISTS} per array, each correlated to
 * the row of the level above: {@code EXISTS (SELECT 1 FROM orders c WHERE c._owner = t.id AND EXISTS
 * (SELECT 1 FROM lines c2 WHERE c2._parent = c._id AND <leaf>))}. A path is therefore a list of hops,
 * and every predicate on it is built by {@link #some} — one rule for every depth, the single-level
 * case included.
 * </p>
 *
 * @param child  the child table
 * @param alias  the alias of its rows in the generated subquery
 * @param scope  the condition tying its rows to the row holding them — the root row for a top-level
 *               table ({@code _owner}), the parent element for a nested one ({@code _parent}) —
 *               narrowed to one key for a map entry
 * @param absent the predicate, over the HOLDING row, "this collection (this map entry) is missing
 *               there": a null or absent collection, a missing key — never an empty collection
 * @param keyed  whether this hop reached one map entry through its key ({@code stock.apple}), rather
 *               than every element of a collection
 */
record PgHop(PgChildTable child, String alias, PgSql scope, PgSql absent, boolean keyed) {

    /** {@return {@code SELECT 1 FROM <child> <alias> WHERE <scope>}} */
    PgSql rows() {
        return PgSql.of("SELECT 1 FROM " + PgNaming.quote(child.name()) + " " + alias + " WHERE ").then(scope);
    }

    /**
     * {@return the rows in scope, further narrowed}
     *
     * @param narrowing a predicate on the rows; null or {@code TRUE} for none
     */
    PgSql rows(PgSql narrowing) {
        if (narrowing == null || PgSql.TRUE.equals(narrowing)) {
            return rows();
        }
        return rows().then(" AND (").then(narrowing).then(")");
    }

    /** {@return {@code <alias>."<column>"}} */
    String ref(String column) {
        return alias + "." + PgNaming.quote(column);
    }

    /**
     * SOME branch of the path satisfies a predicate: one nested {@code EXISTS} per hop, the innermost
     * narrowed by {@code leaf}.
     *
     * <p>
     * With {@code orMissing}, a branch on which a collection along the way is MISSING matches too, at
     * every level: that is MongoDB's answer to a comparison a missing value satisfies ({@code $eq null},
     * {@code $in [null]}): {@code {orders: [{lines: null}]}} matches {@code orders.lines.sku: null}. An
     * EMPTY collection is present and yields no branch, so it does not.
     * </p>
     *
     * @param hops      the path, outermost first; empty returns {@code leaf} itself
     * @param leaf      the predicate on the innermost rows — or, when {@code hops} is empty, on the
     *                  holding row
     * @param orMissing whether a missing collection along the way matches
     * @return the predicate — never NULL
     */
    static PgSql some(List<PgHop> hops, PgSql leaf, boolean orMissing) {
        return some(hops, 0, leaf, orMissing);
    }

    private static PgSql some(List<PgHop> hops, int from, PgSql leaf, boolean orMissing) {
        if (from == hops.size()) {
            return leaf;
        }
        PgHop hop = hops.get(from);
        PgSql inner = some(hops, from + 1, leaf, orMissing);
        PgSql exists = PgSql.FALSE.equals(inner) ? PgSql.FALSE : hop.rows(inner).wrap("EXISTS (", ")");
        return orMissing ? PgSql.any(List.of(exists, hop.absent())) : exists;
    }

    /**
     * NO branch holds the last collection (or map entry): it is missing wherever the path leads —
     * MongoDB's {@code $exists: false} on it. With a single hop, simply its absence.
     *
     * @param hops the path, outermost first — at least one hop
     * @return the predicate — never NULL
     */
    static PgSql noneHolds(List<PgHop> hops) {
        PgHop last = hops.get(hops.size() - 1);
        List<PgHop> parents = hops.subList(0, hops.size() - 1);
        if (parents.isEmpty()) {
            return last.absent();
        }
        return PgSql.not(some(parents, PgSql.not(last.absent()), false));
    }

    /**
     * SOME branch misses the last collection (or map entry) — or a collection above it: MongoDB's
     * {@code $eq null} on it.
     *
     * @param hops the path, outermost first — at least one hop
     * @param leaf the "missing or null" predicate of the last hop, over its holding row
     * @return the predicate — never NULL
     */
    static PgSql someMisses(List<PgHop> hops, PgSql leaf) {
        return some(hops.subList(0, hops.size() - 1), leaf, true);
    }
}
