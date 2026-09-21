package com.garganttua.dao.postgresql;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * Reads the child tables of a page of rows — a top-level table and every table below it — and puts
 * each collection back on its owner.
 *
 * <p>
 * ONE query per child table for the whole page ({@code _owner = ANY(?)}), never one per row nor one
 * per element: a page of 50 shops whose orders hold lines costs one statement for the orders and one
 * for the lines, whatever the number of orders. Every row carries the ROOT's id in {@code _owner}, so
 * even a table three levels down is read by the page's ids alone; its rows are then grouped by
 * {@code _parent} — the {@code _id} of the row one level up — and the trees rebuilt bottom-up, each
 * element getting its collections before it joins its own (a hashed set must see it complete).
 * </p>
 *
 * <p>
 * Zero child rows mean either "empty" or "null", and only the owner's presence column knows which —
 * MongoDB keeps {@code []} and an absent field apart, so the reader must too. At the top level that
 * column is on the main row; one level down, on the parent element's row. Presence {@code TRUE}: the
 * collection is built, empty when there are no rows. Presence NULL: the field is left as the
 * constructor left it, exactly as the MongoDB reader leaves a field whose key is absent.
 * </p>
 */
final class PgChildLoader {

    private final PgBeans beans;
    private final PgRowMapper mapper;

    PgChildLoader(PgBeans beans, PgRowMapper mapper) {
        this.beans = beans;
        this.mapper = mapper;
    }

    /**
     * One child row, read: its own id (tables with children only), its map key, its element and the
     * presence bits of the structures the element holds (the element's own bit under the empty path).
     */
    private record ChildRow(Long id, Object key, Object element, Map<List<String>, Boolean> presence) {
    }

    /** The rows of every table of one tree, grouped by the key of their parent: root id or parent row id. */
    private static final class Loaded {
        private final Map<PgChildTable, Map<Object, List<ChildRow>>> byTable = new IdentityHashMap<>();

        List<ChildRow> rows(PgChildTable table, Object parent) {
            return byTable.getOrDefault(table, Map.of()).getOrDefault(parent, List.of());
        }
    }

    /**
     * Loads one top-level child table, and the whole tree below it, for every row of the page.
     *
     * @param connection the connection
     * @param child      the top-level child table
     * @param rows       the page, in order
     * @throws ApiException when a query fails or a value cannot be rebuilt
     */
    void load(Connection connection, PgChildTable child, List<PgLoadedRow> rows) throws ApiException {
        Loaded loaded = new Loaded();
        query(connection, child, rows, loaded);
        for (PgLoadedRow row : rows) {
            place(row, child, assemble(child, loaded.rows(child, row.id()), loaded));
        }
    }

    /** Reads one table and, recursively, the tables below it. */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private void query(Connection connection, PgChildTable child, List<PgLoadedRow> rows, Loaded loaded)
            throws ApiException {
        String sql = selectSql(child);
        Map<Object, List<ChildRow>> byParent = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, owners(connection, rows));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Object parent = child.nested() ? (Object) rs.getLong(scopeIndex(child)) : rs.getString(scopeIndex(child));
                    byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row(rs, child));
                }
            }
        } catch (SQLException e) {
            throw new ApiException("Cannot read child table '" + child.name() + "' (collection '"
                    + child.dottedPath() + "'): " + e.getMessage() + " — SQL: " + sql, e);
        }
        loaded.byTable.put(child, byParent);
        for (PgChildTable grandchild : child.children()) {
            query(connection, grandchild, rows, loaded);
        }
    }

    /**
     * The select statement of a child table, package-visible for tests: {@code [_id,] _owner|_parent,
     * _ord|_key, <values>}, filtered on the page's ROOT ids and ordered by parent then position.
     *
     * @param child the child table, at any depth
     * @return the statement, with one {@code ?} for the array of root ids
     */
    static String selectSql(PgChildTable child) {
        StringJoiner list = new StringJoiner(", ");
        if (child.hasChildren()) {
            list.add(PgNaming.quote(PgChildTable.ID));
        }
        String scope = PgNaming.quote(child.nested() ? PgChildTable.PARENT : PgChildTable.OWNER);
        list.add(scope);
        String position = PgNaming.quote(child.ordered() ? PgChildTable.ORD : PgChildTable.KEY);
        list.add(position);
        for (PgColumn column : child.valueColumns()) {
            list.add(PgSelected.of(null, column).expression());
        }
        return "SELECT " + list + " FROM " + PgNaming.quote(child.name()) + " WHERE "
                + PgNaming.quote(PgChildTable.OWNER) + " = ANY(?) ORDER BY " + scope + ", " + position;
    }

    /** {@return the 1-based index of {@code _owner} or {@code _parent} in the select list} */
    private static int scopeIndex(PgChildTable child) {
        return child.hasChildren() ? 2 : 1;
    }

    private static Array owners(Connection connection, List<PgLoadedRow> rows) throws SQLException {
        return connection.createArrayOf("text", rows.stream().map(PgLoadedRow::id).toArray());
    }

    /** One child row as read: nothing is attached to its element yet. */
    private ChildRow row(ResultSet rs, PgChildTable child) throws ApiException, SQLException {
        Long id = child.hasChildren() ? rs.getLong(1) : null;
        int positionIndex = scopeIndex(child) + 1;
        Object key = null;
        if (child.kind() == PgChildKind.MAP) {
            PgColumn keyColumn = new PgColumn(PgChildTable.KEY, "", PgColumnKind.SCALAR, List.of(), child.keyType());
            key = PgJdbcDecoder.decode(rs, positionIndex, keyColumn, child.keyType(), child.keyType().getType());
        }
        int first = positionIndex + 1;
        List<PgColumn> columns = child.valueColumns();
        List<PgSelected> cells = columns.stream().map(c -> PgSelected.of(null, c)).toList();
        if (child.elementTable().isPresent()) {
            // The element IS a collection: its row holds only its presence bit, its content is below.
            return new ChildRow(id, key, null, mapper.presence(rs, cells, first));
        }
        if (columns.size() == 1 && columns.get(0).kind() != PgColumnKind.PRESENCE
                && columns.get(0).fieldPath().isEmpty()) {
            IClass<?> type = child.kind() == PgChildKind.COMPOSITION_COLLECTION
                    ? IClass.getClass(String.class) : child.elementType();
            return new ChildRow(id, key, PgJdbcDecoder.decode(rs, first, columns.get(0), type, type.getType()), Map.of());
        }
        PgRowMapper.Element element = mapper.readElement(child.elementType(), rs, cells, first);
        return new ChildRow(id, key, element.value(), element.presence());
    }

    /**
     * The entries (map key or null, element) of one owner's collection, each element completed with the
     * collections it holds.
     */
    private List<Map.Entry<Object, Object>> assemble(PgChildTable child, List<ChildRow> rows, Loaded loaded)
            throws ApiException {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(rows.size());
        for (ChildRow row : rows) {
            Object element = row.element();
            for (PgChildTable grandchild : row.id() == null ? List.<PgChildTable>of() : child.children()) {
                element = attach(row, element, grandchild, loaded);
            }
            entries.add(new AbstractMap.SimpleEntry<>(row.key(), element));
        }
        return entries;
    }

    /**
     * Puts the collection a grandchild table holds for one row on that row's element — or, when the
     * element IS that collection (empty field path), makes it the element.
     *
     * @return the element, possibly replaced
     */
    private Object attach(ChildRow row, Object element, PgChildTable grandchild, Loaded loaded) throws ApiException {
        List<String> path = grandchild.fieldPath();
        Boolean bit = row.presence().get(path);
        boolean known = row.presence().containsKey(path);
        if (path.isEmpty()) {
            return known && bit == null ? null : build(grandchild, assemble(grandchild,
                    loaded.rows(grandchild, row.id()), loaded));
        }
        List<ChildRow> rows = loaded.rows(grandchild, row.id());
        if (element == null || (known && bit == null) || (!known && rows.isEmpty())) {
            return element;
        }
        beans.set(element, path, build(grandchild, assemble(grandchild, rows, loaded)), true);
        return element;
    }

    /** The collection or map a table's field declares, holding the entries. */
    private static Object build(PgChildTable child, List<Map.Entry<Object, Object>> entries) throws ApiException {
        return child.kind() == PgChildKind.MAP
                ? PgCollections.map(child.collectionType(), entries)
                : PgCollections.collection(child.collectionType(), child.elementType(),
                        entries.stream().map(Map.Entry::getValue).toList());
    }

    /** Puts one owner's elements on its DTO — or hands reference uuids to the composition pass. */
    private void place(PgLoadedRow row, PgChildTable child, List<Map.Entry<Object, Object>> entries)
            throws ApiException {
        if (row.absent(child.dottedPath())) {
            return;
        }
        if (child.kind() == PgChildKind.COMPOSITION_COLLECTION) {
            List<String> uuids = new ArrayList<>(entries.size());
            entries.forEach(e -> uuids.add((String) e.getValue()));
            row.referenceLists().put(child.dottedPath(), uuids);
            return;
        }
        // Present (or presence not selected but rows found): set it, creating a missing owner POJO.
        // With no presence bit and no rows, an empty collection must not bring a null POJO into
        // existence.
        boolean known = row.presence().containsKey(child.dottedPath());
        beans.set(row.instance(), child.fieldPath(), build(child, entries), known || !entries.isEmpty());
    }
}
