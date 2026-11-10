package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Read side of the PostgreSQL DAO: executes a translated {@link PgQuery} and rebuilds the DTOs —
 * flattened POJOs, child-table collections and {@code @Composed} references included.
 *
 * <p>
 * A page is read in a FIXED number of statements whatever its size — usually ONE: the collections come
 * back inside the main query, as JSON aggregates ({@link PgChildAggregate}); only a referenced domain,
 * or a collection too wide to aggregate, costs a statement more. Reading row by row would be the
 * classic N+1 — invisible on a test with three rows, crippling on a page of a hundred.
 * </p>
 *
 * <p>
 * It behaves like the MongoDB reader wherever the relational model allows: DTOs and embedded POJOs
 * are built through their no-arg constructor, values are coerced to the FIELD's declared type,
 * references are resolved one level deep, and a projection always keeps the id and every reference —
 * without them the DTO could neither be identified nor its references resolved. Like MongoDB, a
 * stored NULL never overwrites a field (the constructor's value survives, as it survives an absent
 * key), and the presence columns of the model tell a null POJO, collection or map from an empty one:
 * what was saved null reads back as the constructor left it, what was saved empty reads back empty.
 * </p>
 *
 * <p>
 * The caller owns the connection (and its transaction); the reader only opens and closes statements.
 * </p>
 */
public final class PgReader {

    /** The CTE holding the page, when the collections are aggregated over it. */
    private static final String PAGE = "page";

    /** The page's own order, numbered, so the outer query can restore it. */
    private static final String ROW_NUMBER = "_row";

    /** The number of rows the filter matches, carried by every row of a page. */
    private static final String TOTAL = "_total";

    private final PgTable table;
    private final IClass<?> dtoClass;
    private final PgBeans beans = new PgBeans();
    private final PgRowMapper mapper = new PgRowMapper(beans);
    private final PgChildLoader children;
    private final PgCompositions compositions;

    /**
     * A reader resolving references one level deep.
     *
     * @param table    the domain's table model
     * @param dtoClass the DTO its rows map to
     * @param registry the shapes of the other domains of the database, to follow references
     */
    public PgReader(PgTable table, IClass<?> dtoClass, PgSchemaRegistry registry) {
        this(table, dtoClass, registry, true);
    }

    /**
     * @param resolve false for the reader of REFERENCED rows, which must not follow their own
     *                references (one level only)
     */
    PgReader(PgTable table, IClass<?> dtoClass, PgSchemaRegistry registry, boolean resolve) {
        this.table = Objects.requireNonNull(table, "table");
        this.dtoClass = Objects.requireNonNull(dtoClass, "dtoClass");
        this.children = new PgChildLoader(beans, mapper, table);
        this.compositions = new PgCompositions(table, dtoClass, registry == null ? new PgSchemaRegistry() : registry,
                beans, resolve);
    }

    /**
     * The DTOs matching a query, in the query's order.
     *
     * @param connection an open connection, owned by the caller
     * @param query      the translated filter, sort, page and projection
     * @return the DTOs — a mutable list, empty when nothing matches
     * @throws ApiException when a statement fails or a row cannot be rebuilt into the DTO
     */
    public List<Object> find(Connection connection, PgQuery query) throws ApiException {
        return new ArrayList<>(read(connection, query, false).dtos());
    }

    /**
     * A page and the total its filter matches, read by the SAME statement: {@code count(*) OVER ()} is
     * computed over every matching row before the {@code LIMIT} cuts the page. Only a page past the end
     * — no row to carry the total — costs a count of its own.
     *
     * @param connection an open connection, owned by the caller
     * @param query      the translated filter, sort, page and projection
     * @return the DTOs of the page and the total
     * @throws ApiException when a statement fails or a row cannot be rebuilt into the DTO
     */
    public Paged findPage(Connection connection, PgQuery query) throws ApiException {
        Paged paged = read(connection, query, true);
        if (paged.total() < 0) {
            boolean firstPage = query.offset() == null || query.offset() == 0L;
            return new Paged(paged.dtos(), firstPage ? 0L : count(connection, query));
        }
        return paged;
    }

    /**
     * What a read returned.
     *
     * @param dtos  the DTOs, in the query's order
     * @param total the number of rows the filter matches, or -1 when not asked or not known
     */
    public record Paged(List<Object> dtos, long total) {

        /** Read-only: the reader hands the page over, it does not share a list with its caller. */
        public Paged {
            dtos = Collections.unmodifiableList(dtos);
        }
    }

    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private Paged read(Connection connection, PgQuery query, boolean withTotal) throws ApiException {
        List<PgSelected> selected = selection(query.projection());
        List<PgChildTable> loaded = childrenToLoad(query.projection());
        List<PgChildTable> aggregated = loaded.stream().filter(PgChildAggregate::fits).toList();
        String sql = selectSql(selected, aggregated, query, withTotal);
        List<PgLoadedRow> rows = new ArrayList<>();
        List<List<String>> aggregates = new ArrayList<>();
        aggregated.forEach(c -> aggregates.add(new ArrayList<>()));
        // After the selected columns: the page's row number (when aggregating), the total (when asked),
        // then one aggregate per collection.
        int extra = selected.size() + 1 + (aggregated.isEmpty() ? 0 : 1);
        int totalIndex = withTotal ? extra : -1;
        int firstAggregate = withTotal ? extra + 1 : extra;
        long total = -1L;
        PgRowPlan plan = mapper.plan(dtoClass, selected);
        int idIndex = 1 + indexOfId(selected);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPage(statement, bind(statement, query.params()), query);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(row(rs, plan, idIndex));
                    if (withTotal) {
                        total = rs.getLong(totalIndex);
                    }
                    for (int i = 0; i < aggregated.size(); i++) {
                        aggregates.get(i).add(rs.getString(firstAggregate + i));
                    }
                }
            }
        } catch (SQLException e) {
            throw failure("read", e, sql);
        }
        return new Paged(complete(connection, rows, loaded, aggregated, aggregates), total);
    }

    /**
     * Puts the collections and references back on the rows read: from the aggregates, then — for a
     * collection too wide to aggregate — by a query of its own; {@return the DTOs, in order}
     */
    private List<Object> complete(Connection connection, List<PgLoadedRow> rows, List<PgChildTable> loaded,
            List<PgChildTable> aggregated, List<List<String>> aggregates) throws ApiException {
        if (!rows.isEmpty()) {
            for (int i = 0; i < aggregated.size(); i++) {
                children.loadAggregated(aggregated.get(i), rows, aggregates.get(i));
            }
            for (PgChildTable child : loaded) {
                if (!aggregated.contains(child)) {
                    children.load(connection, child, rows);
                }
            }
            compositions.resolve(connection, rows);
        }
        List<Object> dtos = new ArrayList<>(rows.size());
        rows.forEach(r -> dtos.add(r.instance()));
        return dtos;
    }

    /**
     * The number of rows matching a query's filter (its sort, page and projection are ignored).
     *
     * @param connection an open connection, owned by the caller
     * @param query      the translated filter
     * @return the count
     * @throws ApiException when the statement fails
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    public long count(Connection connection, PgQuery query) throws ApiException {
        String sql = "SELECT count(*) FROM " + PgNaming.quote(table.name()) + " " + PgQuery.ALIAS
                + " WHERE " + query.where();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, query.params());
            try (ResultSet rs = statement.executeQuery()) {
                // count(*) without GROUP BY always yields exactly one row.
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw failure("count", e, sql);
        }
    }

    /** {@return the select list of a projection — package-visible so tests can check the SQL shape} */
    String selectList(List<String> projection) {
        StringJoiner list = new StringJoiner(", ");
        selection(projection).forEach(s -> list.add(s.expression()));
        return list.toString();
    }

    /** {@return the full SELECT of a query, with {@code ?} for its parameters and page} */
    String selectSql(PgQuery query) {
        List<PgChildTable> aggregated = childrenToLoad(query.projection()).stream()
                .filter(PgChildAggregate::fits).toList();
        return selectSql(selection(query.projection()), aggregated, query, false);
    }

    /**
     * Whether a query is read in ONE statement — then it needs no transaction: one statement is one
     * snapshot. It is not when a collection is too wide to be aggregated, or when the domain has
     * references, which are resolved against the other domains' tables afterwards.
     *
     * @param query the query
     * @return {@code true} for a single statement
     */
    boolean singleStatement(PgQuery query) {
        return table.compositions().isEmpty()
                && childrenToLoad(query.projection()).stream().allMatch(PgChildAggregate::fits);
    }

    private String selectSql(List<PgSelected> selected, List<PgChildTable> aggregated, PgQuery query,
            boolean withTotal) {
        StringJoiner list = new StringJoiner(", ");
        selected.forEach(s -> list.add(s.expression()));
        StringBuilder main = new StringBuilder("SELECT ").append(list);
        if (!aggregated.isEmpty()) {
            // The page's own order, numbered: the outer query restores it after joining the aggregates.
            String order = query.orderBy().isBlank() ? "" : query.orderBy();
            main.append(", row_number() OVER (").append(order).append(") AS ").append(PgNaming.quote(ROW_NUMBER));
        }
        if (withTotal) {
            // Over every matching row: window functions run before the LIMIT.
            main.append(", count(*) OVER () AS ").append(PgNaming.quote(TOTAL));
        }
        main.append(" FROM ").append(PgNaming.quote(table.name())).append(' ').append(PgQuery.ALIAS)
                .append(" WHERE ").append(query.where());
        if (!query.orderBy().isBlank()) {
            main.append(' ').append(query.orderBy());
        }
        if (query.limit() != null) {
            main.append(" LIMIT ?");
        }
        if (query.offset() != null) {
            main.append(" OFFSET ?");
        }
        return aggregated.isEmpty() ? main.toString() : withAggregates(main.toString(), aggregated);
    }

    /**
     * The page as a CTE, the aggregates of its collections beside it, joined back in the page's own order.
     */
    private String withAggregates(String main, List<PgChildTable> aggregated) {
        List<String> ctes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String pageId = PgNaming.quote(table.id().name());
        PgChildAggregate.ctes(aggregated, PAGE, pageId, ctes, names);
        StringJoiner outer = new StringJoiner(", ");
        StringBuilder joins = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String join = "a" + i;
            outer.add(join + ".agg");
            joins.append(" LEFT JOIN ").append(names.get(i)).append(' ').append(join).append(" ON ").append(join)
                    .append(".k = ").append(PAGE).append('.').append(pageId);
        }
        return "WITH " + PAGE + " AS (" + main + "), " + String.join(", ", ctes) + " SELECT " + PAGE + ".*, " + outer
                + " FROM " + PAGE + joins + " ORDER BY " + PAGE + "." + PgNaming.quote(ROW_NUMBER);
    }

    /**
     * The main columns to select: those the projection names or lies under or above, plus — always —
     * the id and every reference column.
     */
    private List<PgSelected> selection(List<String> projection) {
        List<PgSelected> selected = new ArrayList<>();
        for (PgColumn column : table.columns()) {
            boolean always = column.kind() == PgColumnKind.ID || column.kind() == PgColumnKind.COMPOSITION
                    || isReferenceCollectionPresence(column);
            if (always || projects(projection, column.dottedPath())) {
                selected.add(PgSelected.of(PgQuery.ALIAS, column));
            }
        }
        return selected;
    }

    /**
     * Whether a column is the presence bit of a reference collection — read whenever its child table
     * is, i.e. always: without it, a reference list saved null could not be told from an empty one.
     */
    private boolean isReferenceCollectionPresence(PgColumn column) {
        return column.kind() == PgColumnKind.PRESENCE && table.child(column.dottedPath())
                .map(c -> c.kind() == PgChildKind.COMPOSITION_COLLECTION).orElse(false);
    }

    /**
     * The child tables to read: the projected ones, and — like the MongoDB DAO, which always keeps its
     * DBRef fields — every reference collection.
     */
    private List<PgChildTable> childrenToLoad(List<String> projection) {
        List<PgChildTable> out = new ArrayList<>();
        for (PgChildTable child : table.children()) {
            boolean reference = child.kind() == PgChildKind.COMPOSITION_COLLECTION;
            if (reference ? compositions.needsRows(child) : projects(projection, child.dottedPath())) {
                out.add(child);
            }
        }
        return out;
    }

    /**
     * Whether a projection covers a path. A projected path counts by its FIRST segment only, as in
     * the MongoDB DAO, which projects the top-level field of a dotted path and so hands back the whole
     * sub-document: {@code address.city} covers every {@code address} column, and a projected
     * {@code node.label} the JSONB column {@code node}. No projection, or an empty one, covers
     * everything.
     */
    static boolean projects(List<String> projection, String path) {
        if (projection == null || projection.isEmpty()) {
            return true;
        }
        for (String projected : projection) {
            if (projected == null) {
                continue;
            }
            String p = projected.trim();
            int dot = p.indexOf('.');
            p = dot < 0 ? p : p.substring(0, dot);
            if (path.equals(p) || path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    private PgLoadedRow row(ResultSet rs, PgRowPlan plan, int idIndex) throws ApiException, SQLException {
        PgLoadedRow row = new PgLoadedRow(rs.getString(idIndex), plan.instantiate());
        mapper.fill(row, plan, rs, 1);
        return row;
    }

    private static int indexOfId(List<PgSelected> selected) {
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).column().kind() == PgColumnKind.ID) {
                return i;
            }
        }
        throw new IllegalStateException("the id column is always selected");
    }

    private static int bind(PreparedStatement statement, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            statement.setObject(index, param);
            index++;
        }
        return index;
    }

    private static void bindPage(PreparedStatement statement, int next, PgQuery query)
            throws SQLException, ApiException {
        int index = next;
        if (query.limit() != null) {
            statement.setInt(index, (int) nonNegative("limit", query.limit()));
            index++;
        }
        if (query.offset() != null) {
            statement.setLong(index, nonNegative("offset", query.offset()));
        }
    }

    private static long nonNegative(String what, long value) throws ApiException {
        if (value < 0) {
            throw new ApiException("A page " + what + " cannot be negative (got " + value + ")");
        }
        return value;
    }

    private ApiException failure(String what, SQLException e, String sql) {
        return new ApiException("Cannot " + what + " table '" + table.name() + "' (DTO " + dtoClass.getName()
                + "): " + e.getMessage() + " — SQL: " + sql, e);
    }
}
