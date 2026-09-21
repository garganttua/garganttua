package com.garganttua.dao.postgresql;

import java.util.List;

import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * MongoDB's sort key of a path that crosses child tables: the smallest value reachable through it
 * (ascending) or the largest (descending), over EVERY branch of the path — at any depth.
 *
 * <p>
 * MongoDB collects the values of a path through all the arrays it crosses: {@code orders.lines.qty}
 * yields the qty of every line of every order. A branch on which a collection along the way is
 * missing ({@code {lines: null}}) — or a map key is — yields a missing value, which is the SMALLEST
 * of all; an empty collection yields nothing. So the candidate values are gathered level by level,
 * each level {@code CROSS JOIN LATERAL} the level below plus one NULL row where the collection below
 * is absent, and the extreme is taken with the NULL-aware order of the sort. A single hop is the
 * one-level case: the elements of one collection.
 * </p>
 *
 * <p>
 * Every fragment here must be free of bind values: an {@code ORDER BY} carries none, so the hops come
 * from a locator that inlines map keys ({@link PgPathLocator}).
 * </p>
 */
final class PgPathSortKey {

    private PgPathSortKey() {
    }

    /**
     * The sort key.
     *
     * @param bson      the order builder of this {@code ORDER BY}, for fresh aliases
     * @param hops      the path, outermost first — at least one hop
     * @param element   the compared value, over the last hop's alias
     * @param direction the direction with its NULLS clause ({@code  ASC NULLS FIRST})
     * @return {@code (SELECT smallest-or-largest reachable value)} followed by the direction
     */
    static String of(PgBsonOrder bson, List<PgHop> hops, PgBsonOrder.Encoded element, String direction) {
        String k = bson.alias("k");
        return "(SELECT " + k + ".e FROM (" + values(bson, hops, 0, element) + ") AS " + k + " ORDER BY " + k
                + ".e" + direction + " LIMIT 1)" + direction;
    }

    /** {@code SELECT e FROM <level> …}: the values reachable from the rows of hop {@code i}. */
    private static String values(PgBsonOrder bson, List<PgHop> hops, int i, PgBsonOrder.Encoded element) {
        PgHop hop = hops.get(i);
        String from = " FROM " + PgNaming.quote(hop.child().name()) + " " + hop.alias();
        String where = " WHERE " + text(hop.scope());
        if (i == hops.size() - 1) {
            return "SELECT " + element.orNull() + " AS e" + from + where;
        }
        String v = bson.alias("v");
        return "SELECT " + v + ".e" + from + " CROSS JOIN LATERAL (" + values(bson, hops, i + 1, element)
                + " UNION ALL SELECT NULL WHERE " + text(hops.get(i + 1).absent()) + ") AS " + v + where;
    }

    private static String text(PgSql sql) {
        if (!sql.params().isEmpty()) {
            throw new IllegalStateException("An ORDER BY fragment carries bind values: " + sql.text());
        }
        return sql.text();
    }
}
