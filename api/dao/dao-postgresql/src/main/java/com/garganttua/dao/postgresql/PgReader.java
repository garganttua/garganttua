package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
 * A page is read in a FIXED number of statements whatever its size: one for the main rows, one per
 * child table ({@code _owner = ANY(?)}), one per referenced domain. Reading row by row would be the
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

    private final PgTable table;
    private final IClass<?> dtoClass;
    private final PgBeans beans = new PgBeans();
    private final PgRowMapper mapper = new PgRowMapper(beans);
    private final PgChildLoader children = new PgChildLoader(beans, mapper);
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
        List<PgSelected> selected = selection(query.projection());
        String sql = selectSql(selected, query);
        List<PgLoadedRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPage(statement, bind(statement, query.params()), query);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(row(rs, selected));
                }
            }
        } catch (SQLException e) {
            throw failure("read", e, sql);
        }
        if (!rows.isEmpty()) {
            for (PgChildTable child : childrenToLoad(query.projection())) {
                children.load(connection, child, rows);
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
    public long count(Connection connection, PgQuery query) throws ApiException {
        String sql = "SELECT count(*) FROM " + PgNaming.quote(table.name()) + " " + PgQuery.ALIAS
                + " WHERE " + query.where();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, query.params());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
        return selectSql(selection(query.projection()), query);
    }

    private String selectSql(List<PgSelected> selected, PgQuery query) {
        StringJoiner list = new StringJoiner(", ");
        selected.forEach(s -> list.add(s.expression()));
        StringBuilder sql = new StringBuilder("SELECT ").append(list).append(" FROM ")
                .append(PgNaming.quote(table.name())).append(' ').append(PgQuery.ALIAS)
                .append(" WHERE ").append(query.where());
        if (!query.orderBy().isBlank()) {
            sql.append(' ').append(query.orderBy());
        }
        if (query.limit() != null) {
            sql.append(" LIMIT ?");
        }
        if (query.offset() != null) {
            sql.append(" OFFSET ?");
        }
        return sql.toString();
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

    private PgLoadedRow row(ResultSet rs, List<PgSelected> selected) throws ApiException, SQLException {
        Object dto = beans.instantiate(dtoClass);
        int idIndex = 1 + indexOfId(selected);
        PgLoadedRow row = new PgLoadedRow(rs.getString(idIndex), dto);
        mapper.fill(row, dtoClass, rs, selected, 1);
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
            statement.setObject(index++, param);
        }
        return index;
    }

    private static void bindPage(PreparedStatement statement, int next, PgQuery query)
            throws SQLException, ApiException {
        int index = next;
        if (query.limit() != null) {
            statement.setInt(index++, (int) nonNegative("limit", query.limit()));
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
