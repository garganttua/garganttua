package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * A collection read INSIDE the main query: the rows of a child table — and, nested in each of them, the
 * rows of the tables below — aggregated into one JSON value per entity, by common table expressions over
 * the page ({@link #ctes}).
 *
 * <p>
 * It replaces one query per child table by a column of the main query: an entity with three lists, one
 * of which holds seven lists per element, is read in ONE round trip instead of eleven, and in one
 * statement — one snapshot, so the read needs no transaction.
 * </p>
 *
 * <p>
 * A row is a JSON array laid out as the child table's own select list —
 * {@code [_id?, _owner|_parent, _ord|_key, values…]} — followed by the aggregates of its own child
 * tables. Every cell is TEXT, written so that it decodes back exactly ({@link PgCells#of(List, List)}):
 * a temporal as an integer count (microseconds since the epoch, days since 1970-01-01, microseconds
 * since midnight), which keeps years beyond 9999 and before year 1 exact; binary as hexadecimal; a
 * geometry as GeoJSON; anything else as its text output.
 * </p>
 */
final class PgChildAggregate {

    /** {@code json_build_array} is a function: PostgreSQL caps its arguments at 100. */
    static final int MAX_ARGUMENTS = 100;

    private static final String BIGINT = "BIGINT";
    private static final String INTEGER = "INTEGER";
    private static final String ALIAS = "x";

    private PgChildAggregate() {
    }

    /**
     * Whether a child table — and every table below it — fits in an aggregate row.
     *
     * @param child a child table
     * @return {@code true} when every row has at most {@link #MAX_ARGUMENTS} cells and nested aggregates
     */
    static boolean fits(PgChildTable child) {
        if (cellCount(child) + child.children().size() > MAX_ARGUMENTS) {
            return false;
        }
        return child.children().stream().allMatch(PgChildAggregate::fits);
    }

    /**
     * The aggregates of the collections of a page, as common table expressions over the page.
     *
     * <p>
     * Set-based: every table of every tree is read ONCE for the whole page — {@code _owner IN (page)},
     * through the primary key at the top and the owner index below — and aggregated by parent; the levels
     * are joined bottom-up, so each row of a table carries the aggregates of its own child tables. A
     * correlated sub-query per row would run once per row and per table: a thousand index probes for a
     * page of twenty entities holding lists of lists.
     * </p>
     *
     * @param tops     the top-level child tables to aggregate
     * @param page     the name of the CTE holding the page
     * @param pageId   the id column of the page, quoted
     * @param ctes     receives the CTE definitions, in dependency order
     * @param names    receives the CTE name of each top-level table, same order as {@code tops}
     */
    static void ctes(List<PgChildTable> tops, String page, String pageId, List<String> ctes, List<String> names) {
        AtomicInteger counter = new AtomicInteger();
        for (PgChildTable top : tops) {
            names.add(cte(top, page, pageId, ctes, counter));
        }
    }

    /** Defines the CTE of one table — after those of the tables below it; {@return its name} */
    private static String cte(PgChildTable child, String page, String pageId, List<String> ctes,
            AtomicInteger counter) {
        List<String> below = new ArrayList<>();
        for (PgChildTable grandchild : child.children()) {
            below.add(cte(grandchild, page, pageId, ctes, counter));
        }
        String name = PgNaming.quote("_agg_" + counter.incrementAndGet());
        StringJoiner cells = new StringJoiner(", ");
        if (child.hasChildren()) {
            cells.add(text(ALIAS, PgChildTable.ID, BIGINT, PgColumnKind.SCALAR));
        }
        String scope = child.nested() ? PgChildTable.PARENT : PgChildTable.OWNER;
        cells.add(text(ALIAS, scope, child.nested() ? BIGINT : PgTypes.TEXT, PgColumnKind.SCALAR));
        String position = child.ordered() ? PgChildTable.ORD : PgChildTable.KEY;
        cells.add(text(ALIAS, position, positionType(child), PgColumnKind.SCALAR));
        for (PgColumn column : child.valueColumns()) {
            cells.add(text(ALIAS, column.name(), column.sqlType(), column.kind()));
        }
        StringBuilder joins = new StringBuilder();
        for (int i = 0; i < below.size(); i++) {
            String join = "j" + i;
            cells.add(join + ".agg");
            joins.append(" LEFT JOIN ").append(below.get(i)).append(' ').append(join).append(" ON ").append(join)
                    .append(".k = ").append(ALIAS).append('.').append(PgNaming.quote(PgChildTable.ID));
        }
        String scopeRef = ALIAS + "." + PgNaming.quote(scope);
        // Joined to the page FIRST: the planner then starts from the page's few rows and reaches the table by
        // its owner index, instead of joining the whole table to the aggregates below and filtering last.
        ctes.add(name + " AS (SELECT " + scopeRef + " AS k, json_agg(json_build_array(" + cells + ") ORDER BY "
                + ALIAS + "." + PgNaming.quote(position) + ") AS agg FROM " + page + " p JOIN "
                + PgNaming.quote(child.name()) + " " + ALIAS + " ON " + ALIAS + "." + PgNaming.quote(PgChildTable.OWNER)
                + " = p." + pageId + joins + " GROUP BY " + scopeRef + ")");
        return name;
    }

    /** {@return the number of cells of a row — what precedes the nested aggregates} */
    static int cellCount(PgChildTable child) {
        return (child.hasChildren() ? 1 : 0) + 2 + child.valueColumns().size();
    }

    /** {@return the SQL type of each cell of a row, in order} */
    static List<String> sqlTypes(PgChildTable child) {
        List<String> types = new ArrayList<>();
        if (child.hasChildren()) {
            types.add(BIGINT);
        }
        types.add(child.nested() ? BIGINT : PgTypes.TEXT);
        types.add(positionType(child));
        for (PgColumn column : child.valueColumns()) {
            types.add(column.sqlType());
        }
        return types;
    }

    private static String positionType(PgChildTable child) {
        return child.ordered() ? INTEGER : PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT);
    }

    /**
     * One cell as the TEXT the aggregate carries, written so that {@link PgCells#of(List, List)} decodes it
     * back exactly.
     *
     * @param alias   the row's alias
     * @param column  the column name, unquoted
     * @param sqlType its SQL type
     * @param kind    its kind
     * @return the expression
     */
    static String text(String alias, String column, String sqlType, PgColumnKind kind) {
        String ref = alias + "." + PgNaming.quote(column);
        if (kind == PgColumnKind.GEOMETRY) {
            return "ST_AsGeoJSON(" + ref + ")";
        }
        return switch (sqlType.toUpperCase(Locale.ROOT)) {
            // Exact in every version: the whole seconds (an integer, exact in a double), plus the micros.
            case "TIMESTAMPTZ" -> micros("(" + ref + " AT TIME ZONE 'UTC')");
            case "TIMESTAMP" -> micros(ref);
            case "DATE" -> "(" + ref + " - DATE '1970-01-01')::text";
            case "TIME" -> "round(extract(epoch FROM " + ref + ") * 1000000)::bigint::text";
            case "BYTEA" -> "encode(" + ref + ", 'hex')";
            default -> ref + "::text";
        };
    }

    private static String micros(String timestamp) {
        return "(extract(epoch FROM date_trunc('second', " + timestamp + "))::bigint * 1000000 + extract(microseconds FROM "
                + timestamp + ")::bigint % 1000000)::text";
    }
}
