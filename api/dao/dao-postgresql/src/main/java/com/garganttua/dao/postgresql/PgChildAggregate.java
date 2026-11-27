package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTypes;

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
 * tables. Every cell is TEXT, written so that it decodes back exactly ({@link SqlCells#of(List, List)}):
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
    static boolean fits(SqlChildTable child) {
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
    static void ctes(List<SqlChildTable> tops, String page, String pageId, List<String> ctes, List<String> names) {
        AtomicInteger counter = new AtomicInteger();
        for (SqlChildTable top : tops) {
            names.add(cte(top, page, pageId, ctes, counter));
        }
    }

    /** Defines the CTE of one table — after those of the tables below it; {@return its name} */
    private static String cte(SqlChildTable child, String page, String pageId, List<String> ctes,
            AtomicInteger counter) {
        List<String> below = new ArrayList<>();
        for (SqlChildTable grandchild : child.children()) {
            below.add(cte(grandchild, page, pageId, ctes, counter));
        }
        String name = SqlNaming.quote("_agg_" + counter.incrementAndGet());
        StringJoiner cells = new StringJoiner(", ");
        if (child.hasChildren()) {
            cells.add(text(ALIAS, SqlChildTable.ID, BIGINT, SqlColumnKind.SCALAR));
        }
        String scope = child.nested() ? SqlChildTable.PARENT : SqlChildTable.OWNER;
        cells.add(text(ALIAS, scope, child.nested() ? BIGINT : SqlTypes.TEXT, SqlColumnKind.SCALAR));
        String position = child.ordered() ? SqlChildTable.ORD : SqlChildTable.KEY;
        cells.add(text(ALIAS, position, positionType(child), SqlColumnKind.SCALAR));
        for (SqlColumn column : child.valueColumns()) {
            cells.add(text(ALIAS, column.name(), column.sqlType(), column.kind()));
        }
        StringBuilder joins = new StringBuilder();
        for (int i = 0; i < below.size(); i++) {
            String join = "j" + i;
            cells.add(join + ".agg");
            joins.append(" LEFT JOIN ").append(below.get(i)).append(' ').append(join).append(" ON ").append(join)
                    .append(".k = ").append(ALIAS).append('.').append(SqlNaming.quote(SqlChildTable.ID));
        }
        String scopeRef = ALIAS + "." + SqlNaming.quote(scope);
        // Joined to the page FIRST: the planner then starts from the page's few rows and reaches the table by
        // its owner index, instead of joining the whole table to the aggregates below and filtering last.
        ctes.add(name + " AS (SELECT " + scopeRef + " AS k, json_agg(json_build_array(" + cells + ") ORDER BY "
                + ALIAS + "." + SqlNaming.quote(position) + ") AS agg FROM " + page + " p JOIN "
                + SqlNaming.quote(child.name()) + " " + ALIAS + " ON " + ALIAS + "." + SqlNaming.quote(SqlChildTable.OWNER)
                + " = p." + pageId + joins + " GROUP BY " + scopeRef + ")");
        return name;
    }

    /** {@return the number of cells of a row — what precedes the nested aggregates} */
    static int cellCount(SqlChildTable child) {
        return (child.hasChildren() ? 1 : 0) + 2 + child.valueColumns().size();
    }

    /** {@return the SQL type of each cell of a row, in order} */
    static List<String> sqlTypes(SqlChildTable child) {
        List<String> types = new ArrayList<>();
        if (child.hasChildren()) {
            types.add(BIGINT);
        }
        types.add(child.nested() ? BIGINT : SqlTypes.TEXT);
        types.add(positionType(child));
        for (SqlColumn column : child.valueColumns()) {
            types.add(column.sqlType());
        }
        return types;
    }

    private static String positionType(SqlChildTable child) {
        return child.ordered() ? INTEGER : SqlTypes.sqlTypeOf(child.keyType()).orElse(SqlTypes.TEXT);
    }

    /**
     * One cell as the TEXT the aggregate carries, written so that {@link SqlCells#of(List, List)} decodes it
     * back exactly.
     *
     * @param alias   the row's alias
     * @param column  the column name, unquoted
     * @param sqlType its SQL type
     * @param kind    its kind
     * @return the expression
     */
    static String text(String alias, String column, String sqlType, SqlColumnKind kind) {
        String ref = alias + "." + SqlNaming.quote(column);
        if (kind == SqlColumnKind.GEOMETRY) {
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
