package com.garganttua.dao.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.sql.schema.SqlChildKind;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * Reads the child tables of a page of rows — a top-level table and every table below it — and puts
 * each collection back on its owner.
 *
 * <p>
 * Normally NO query at all: the main query returns each collection as a JSON aggregate
 * ({@link SqlChildAggregate}), and {@link #loadAggregated} rebuilds it. A child table too wide for an
 * aggregate row is read the older way, ONE query per child table for the whole page
 * ({@code _owner = ANY(?)}), never one per row nor one per element: a page of 50 shops whose orders hold lines costs one statement for the orders and one
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
public final class SqlChildLoader {

    private final SqlBeans beans;
    private final SqlRowMapper mapper;
    /** The database the child tables live on. */
    private final ISqlDialect dialect;

    /**
     * What decoding a row of one child table needs, computed once per table — not once per row. Its
     * {@link Planned} part resolves fields on the element class, so it is built on first use, where a
     * mismatch between table model and class is reported like any read failure.
     */
    private record Shape(int cellCount, List<String> sqlTypes, List<SqlSelected> cells, SqlColumn keyColumn,
            IClass<?> singleValueType, boolean elementIsCollection, AtomicReference<Planned> planned) {
    }

    /**
     * The row plan of a child table, and for each table below it — in {@code children()} order — the slot
     * of the presence bit its field has in a row of this one, or -1 when none is selected.
     */
    @SuppressWarnings("PMD.UseVarargs") // positional slots, built once per table
    private static final class Planned {
        private final SqlRowPlan plan;
        private final int[] slots;

        private Planned(SqlRowPlan plan, int[] slots) {
            this.plan = plan;
            this.slots = slots;
        }
    }

    /** By child table, identity-keyed, built once and never written again: read without a lock. */
    private final Map<SqlChildTable, Shape> shapes;

    SqlChildLoader(SqlBeans beans, SqlRowMapper mapper, SqlTable table) {
        this.beans = beans;
        this.mapper = mapper;
        this.dialect = table.dialect();
        Map<SqlChildTable, Shape> byTable = new IdentityHashMap<>();
        for (SqlChildTable child : table.allChildren()) {
            byTable.put(child, shapeOf(this.dialect, child));
        }
        this.shapes = Collections.unmodifiableMap(byTable);
    }

    private static Shape shapeOf(ISqlDialect dialect, SqlChildTable child) {
        List<String> sqlTypes = SqlChildAggregate.sqlTypes(child).stream().map(t -> t.toUpperCase(Locale.ROOT)).toList();
        List<SqlColumn> columns = child.valueColumns();
        SqlColumn keyColumn = child.kind() == SqlChildKind.MAP
                ? new SqlColumn(SqlChildTable.KEY, "", SqlColumnKind.SCALAR, List.of(), child.keyType()) : null;
        IClass<?> single = null;
        if (columns.size() == 1 && columns.get(0).kind() != SqlColumnKind.PRESENCE && columns.get(0).fieldPath().isEmpty()) {
            single = child.kind() == SqlChildKind.COMPOSITION_COLLECTION ? IClass.getClass(String.class) : child.elementType();
        }
        return new Shape(SqlChildAggregate.cellCount(child), sqlTypes,
                columns.stream().map(c -> SqlSelected.of(dialect, null, c)).toList(), keyColumn, single,
                child.elementTable().isPresent(), new AtomicReference<>());
    }

    /** The plan of a child table, built once — two threads racing build the same one, either is kept. */
    private Planned planned(SqlChildTable child) throws ApiException {
        Shape shape = this.shapes.get(child);
        Planned planned = shape.planned().get();
        if (planned == null) {
            SqlRowPlan plan = null;
            if (shape.elementIsCollection()) {
                plan = SqlRowPlan.presenceOnly(this.beans, child.elementType(), shape.cells());
            } else if (shape.singleValueType() == null) {
                plan = this.mapper.plan(child.elementType(), shape.cells());
            }
            List<SqlChildTable> below = child.children();
            int[] slots = new int[below.size()];
            for (int k = 0; k < slots.length; k++) {
                slots[k] = plan == null ? -1 : plan.slot(below.get(k).fieldPath());
            }
            planned = new Planned(plan, slots);
            shape.planned().set(planned);
        }
        return planned;
    }

    /**
     * One child row, read: its own id (tables with children only), its map key, its element and the
     * presence bits of the structures the element holds (the element's own bit under the empty path).
     */
    @SuppressWarnings("PMD.UseVarargs") // the row's bits by plan slot, read by index
    private static final class ChildRow {
        private final Long id;
        private final Object key;
        private final Object element;
        /** By slot of the table's row plan; empty for a single-value table. */
        private final Boolean[] presence;

        private ChildRow(Long id, Object key, Object element, Boolean[] presence) {
            this.id = id;
            this.key = key;
            this.element = element;
            this.presence = presence;
        }
    }

    private static final Boolean[] NO_PRESENCE = new Boolean[0];

    /** The rows of every table of one tree, grouped by the key of their parent: root id or parent row id. */
    private static final class Loaded {
        private final Map<SqlChildTable, Map<Object, List<ChildRow>>> byTable = new IdentityHashMap<>();

        List<ChildRow> rows(SqlChildTable table, Object parent) {
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
    void load(Connection connection, SqlChildTable child, List<SqlLoadedRow> rows) throws ApiException {
        Loaded loaded = new Loaded();
        query(connection, child, rows, loaded);
        for (SqlLoadedRow row : rows) {
            place(row, child, assemble(child, loaded.rows(child, row.id()), loaded));
        }
    }

    /** Reads one table and, recursively, the tables below it. */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private void query(Connection connection, SqlChildTable child, List<SqlLoadedRow> rows, Loaded loaded)
            throws ApiException {
        String sql = selectSql(this.dialect, child);
        Map<Object, List<ChildRow>> byParent = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, this.dialect.anyOfValues(connection, rows.stream().map(SqlLoadedRow::id).toList()));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Object parent = child.nested() ? (Object) rs.getLong(scopeIndex(child)) : rs.getString(scopeIndex(child));
                    byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(row(SqlCells.of(rs), child));
                }
            }
        } catch (SQLException e) {
            throw new ApiException("Cannot read child table '" + child.name() + "' (collection '"
                    + child.dottedPath() + "'): " + e.getMessage() + " — SQL: " + sql, e);
        }
        loaded.byTable.put(child, byParent);
        for (SqlChildTable grandchild : child.children()) {
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
    public static String selectSql(ISqlDialect dialect, SqlChildTable child) {
        StringJoiner list = new StringJoiner(", ");
        if (child.hasChildren()) {
            list.add(SqlNaming.quote(SqlChildTable.ID));
        }
        String scope = SqlNaming.quote(child.nested() ? SqlChildTable.PARENT : SqlChildTable.OWNER);
        list.add(scope);
        String position = SqlNaming.quote(child.ordered() ? SqlChildTable.ORD : SqlChildTable.KEY);
        list.add(position);
        for (SqlColumn column : child.valueColumns()) {
            list.add(SqlSelected.of(dialect, null, column).expression());
        }
        return "SELECT " + list + " FROM " + SqlNaming.quote(child.name()) + " WHERE "
                + dialect.anyOf(SqlNaming.quote(SqlChildTable.OWNER)) + " ORDER BY " + scope + ", " + position;
    }

    /** {@return the 1-based index of {@code _owner} or {@code _parent} in the select list} */
    private static int scopeIndex(SqlChildTable child) {
        return child.hasChildren() ? 2 : 1;
    }


    /**
     * Loads one top-level child table — and the whole tree below it — from the aggregates the main query
     * returned ({@link SqlChildAggregate}): no statement, the rows are already there.
     *
     * @param child      the top-level child table
     * @param rows       the page, in order
     * @param aggregates the aggregate of each row, same order — JSON text, or null when the entity has no row
     * @throws ApiException when an aggregate cannot be read or a value cannot be rebuilt
     */
    void loadAggregated(SqlChildTable child, List<SqlLoadedRow> rows, List<String> aggregates) throws ApiException {
        Loaded loaded = new Loaded();
        for (int i = 0; i < rows.size(); i++) {
            collect(child, rows.get(i).id(), parse(child, aggregates.get(i)), loaded);
        }
        for (SqlLoadedRow row : rows) {
            place(row, child, assemble(child, loaded.rows(child, row.id()), loaded));
        }
    }

    /**
     * An aggregate as nested lists — rows of TEXT cells and nested aggregates ({@link SqlAggregateText}):
     * the aggregates of a page are the bulk of what a read decodes.
     */
    private static List<Object> parse(SqlChildTable child, String json) throws ApiException {
        if (json == null) {
            return List.of();
        }
        try {
            return SqlAggregateText.parse(json);
        } catch (IllegalArgumentException e) {
            throw new ApiException("Cannot read the rows of child table '" + child.name() + "' (collection '"
                    + child.dottedPath() + "'): " + e.getMessage(), e);
        }
    }

    /** Files the aggregated rows of one table under their parent, then — nested in each — the rows below. */
    private void collect(SqlChildTable child, Object parent, Object aggregate, Loaded loaded) throws ApiException {
        List<ChildRow> under = loaded.byTable.computeIfAbsent(child, k -> new HashMap<>())
                .computeIfAbsent(parent, k -> new ArrayList<>());
        if (!(aggregate instanceof List<?> rows)) {
            return;
        }
        Shape shape = this.shapes.get(child);
        int cellCount = shape.cellCount();
        List<SqlChildTable> grandchildren = child.children();
        for (Object node : rows) {
            // The row's cells come first, the aggregates of the tables below after them: the cells read
            // the list as it is, past them nothing is a cell.
            List<?> cellsAndBelow = (List<?>) node;
            ChildRow row;
            try {
                row = row(SqlCells.of(cellsAndBelow, shape.sqlTypes()), child);
            } catch (SQLException e) {
                throw new ApiException("Cannot read a row of child table '" + child.name() + "': " + e.getMessage(), e);
            }
            under.add(row);
            for (int k = 0; k < grandchildren.size(); k++) {
                collect(grandchildren.get(k), row.id, cellsAndBelow.get(cellCount + k), loaded);
            }
        }
    }

    /** One child row as read: nothing is attached to its element yet. */
    private ChildRow row(SqlCells rs, SqlChildTable child) throws ApiException, SQLException {
        Shape shape = this.shapes.get(child);
        Long id = child.hasChildren() ? rs.integer(1) : null;
        int positionIndex = scopeIndex(child) + 1;
        Object key = null;
        if (shape.keyColumn() != null) {
            key = SqlJdbcDecoder.decode(rs, positionIndex, shape.keyColumn(), child.keyType(), child.keyType().getType());
        }
        int first = positionIndex + 1;
        if (shape.singleValueType() != null) {
            IClass<?> type = shape.singleValueType();
            return new ChildRow(id, key, SqlJdbcDecoder.decode(rs, first, child.valueColumns().get(0), type,
                    type.getType()), NO_PRESENCE);
        }
        SqlRowPlan plan = planned(child).plan;
        if (shape.elementIsCollection()) {
            // The element IS a collection: its row holds only its presence bit, its content is below.
            return new ChildRow(id, key, null, plan.decodePresence(rs, first));
        }
        SqlRowMapper.Element element = mapper.readElement(plan, rs, first);
        return new ChildRow(id, key, element.value, element.presence);
    }

    /**
     * The entries (map key or null, element) of one owner's collection, each element completed with the
     * collections it holds.
     */
    private List<Map.Entry<Object, Object>> assemble(SqlChildTable child, List<ChildRow> rows, Loaded loaded)
            throws ApiException {
        List<Map.Entry<Object, Object>> entries = new ArrayList<>(rows.size());
        List<SqlChildTable> below = child.children();
        int[] slots = below.isEmpty() ? null : planned(child).slots;
        for (ChildRow row : rows) {
            Object element = row.element;
            if (row.id != null) {
                for (int k = 0; k < below.size(); k++) {
                    element = attach(row, element, below.get(k), slots[k], loaded);
                }
            }
            entries.add(new AbstractMap.SimpleEntry<>(row.key, element));
        }
        return entries;
    }

    /**
     * Puts the collection a grandchild table holds for one row on that row's element — or, when the
     * element IS that collection (empty field path), makes it the element.
     *
     * @return the element, possibly replaced
     */
    private Object attach(ChildRow row, Object element, SqlChildTable grandchild, int slot, Loaded loaded)
            throws ApiException {
        List<String> path = grandchild.fieldPath();
        boolean known = slot >= 0 && slot < row.presence.length;
        Boolean bit = known ? row.presence[slot] : null;
        if (path.isEmpty()) {
            return known && bit == null ? null : build(grandchild, assemble(grandchild,
                    loaded.rows(grandchild, row.id), loaded));
        }
        List<ChildRow> rows = loaded.rows(grandchild, row.id);
        if (element == null || (known && bit == null) || (!known && rows.isEmpty())) {
            return element;
        }
        beans.set(element, path, build(grandchild, assemble(grandchild, rows, loaded)), true);
        return element;
    }

    /** The collection or map a table's field declares, holding the entries. */
    private static Object build(SqlChildTable child, List<Map.Entry<Object, Object>> entries) throws ApiException {
        return child.kind() == SqlChildKind.MAP
                ? SqlCollections.map(child.collectionType(), entries)
                : SqlCollections.collection(child.collectionType(), child.elementType(), values(entries));
    }

    private static List<Object> values(List<Map.Entry<Object, Object>> entries) {
        List<Object> values = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> entry : entries) {
            values.add(entry.getValue());
        }
        return values;
    }

    /** Puts one owner's elements on its DTO — or hands reference uuids to the composition pass. */
    private void place(SqlLoadedRow row, SqlChildTable child, List<Map.Entry<Object, Object>> entries)
            throws ApiException {
        if (row.absent(child.dottedPath())) {
            return;
        }
        if (child.kind() == SqlChildKind.COMPOSITION_COLLECTION) {
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
