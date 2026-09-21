package com.garganttua.dao.postgresql;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * Writes the rows of one collection or map field — and of every collection its elements hold, to
 * any depth: delete every row of the owner, insert the current elements.
 *
 * <p>
 * Replacing rather than diffing is deliberate. An upsert REPLACES the entity, collections included,
 * as MongoDB's {@code save()} replaces the document; working out which elements moved, changed or
 * disappeared would buy nothing but code, since the caller's transaction makes delete-then-insert
 * atomic anyway. Only the TOP-LEVEL table is deleted from: the rows of the tables below it reference
 * their parent row with {@code ON DELETE CASCADE}, so the whole tree goes with it.
 * </p>
 *
 * <p>
 * The tree is written one TABLE at a time, never one element at a time: every row of a table — across
 * all the parent rows written just before — goes in one batch. A table whose elements hold collections
 * returns the generated {@code _id} of each row, and the next level's rows reference it as their
 * {@code _parent}; every row, at any depth, also carries the root entity's id in {@code _owner}.
 * </p>
 *
 * <p>
 * A null or empty collection is written as zero rows; the owner's presence column (written with the
 * main row, or with the parent element's row one level down) is what tells them apart. A null element
 * of a scalar or POJO collection is a row whose value columns are NULL — it keeps its position; for a
 * POJO element, its {@code _present} column is NULL too, which is how the reader tells it from an
 * element whose fields are all null. A null element of a REFERENCE collection is skipped, as the
 * MongoDB DAO skips it when building its {@code DBRef} list: there is no entity to point to.
 * </p>
 */
final class PgWriteChildren {

    private final PgTable table;
    private final PgWriteReferences references;

    PgWriteChildren(PgTable table, PgWriteReferences references) {
        this.table = table;
        this.references = references;
    }

    /** An object holding collections: the root entity, or an element written one level up, with its row id. */
    private record Holder(Long rowId, Object object) {
    }

    /** One row to insert: the parent row's id (null at the top level), its position, the element. */
    private record Row(Long parent, Object position, Object element) {
    }

    /**
     * Replaces the rows of one top-level child table — and of its whole subtree — for one owner.
     *
     * @param connection the caller's connection
     * @param child      the top-level child table
     * @param ownerId    the owner's id, JDBC-ready
     * @param dto        the owning entity
     * @throws SQLException when the database refuses a statement
     * @throws ApiException when an element cannot be converted
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    void write(Connection connection, PgChildTable child, Object ownerId, Object dto)
            throws SQLException, ApiException {
        try (PreparedStatement delete = connection.prepareStatement(deleteSql(child))) {
            delete.setObject(1, ownerId);
            delete.executeUpdate();
        }
        writeLevel(connection, child, ownerId, List.of(new Holder(null, dto)));
    }

    /** Inserts the rows of one table for every holder, then — when its elements hold collections — the level below. */
    private void writeLevel(Connection connection, PgChildTable child, Object ownerId, List<Holder> holders)
            throws SQLException, ApiException {
        List<Row> rows = rows(child, holders);
        if (rows.isEmpty()) {
            return;
        }
        List<Holder> written = insert(connection, child, ownerId, rows);
        if (written.isEmpty()) {
            return;
        }
        for (PgChildTable grandchild : child.children()) {
            writeLevel(connection, grandchild, ownerId, written);
        }
    }

    /**
     * Inserts rows in one batch.
     *
     * @return when the table has children, every NON-NULL element with the id its row was given (a null
     *         element holds nothing); otherwise an empty list
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private List<Holder> insert(Connection connection, PgChildTable child, Object ownerId, List<Row> rows)
            throws SQLException, ApiException {
        String sql = insertSql(child);
        try (PreparedStatement insert = child.hasChildren()
                ? connection.prepareStatement(sql, new String[] { PgChildTable.ID })
                : connection.prepareStatement(sql)) {
            PgColumn keyColumn = child.ordered() ? null : keyColumn(child);
            for (Row row : rows) {
                bindRow(insert, child, keyColumn, ownerId, row);
                insert.addBatch();
            }
            insert.executeBatch();
            return child.hasChildren() ? generated(insert, child, rows) : List.of();
        }
    }

    private void bindRow(PreparedStatement insert, PgChildTable child, PgColumn keyColumn, Object ownerId, Row row)
            throws SQLException, ApiException {
        insert.setObject(1, ownerId);
        int position = 2;
        if (child.nested()) {
            insert.setObject(2, row.parent());
            position = 3;
        }
        if (keyColumn == null) {
            insert.setObject(position, row.position());
        } else {
            PgWriteSupport.bind(insert, position, keyColumn, PgValues.toJdbc(keyColumn, row.position()));
        }
        bindValues(insert, position + 1, child, row.element());
    }

    /** Pairs each inserted row with its generated id — pgjdbc returns them in batch order. */
    private List<Holder> generated(PreparedStatement insert, PgChildTable child, List<Row> rows)
            throws SQLException, ApiException {
        List<Holder> out = new ArrayList<>(rows.size());
        int i = 0;
        try (ResultSet keys = insert.getGeneratedKeys()) {
            while (i < rows.size() && keys.next()) {
                Object element = rows.get(i).element();
                if (element != null) {
                    out.add(new Holder(keys.getLong(1), element));
                }
                i++;
            }
        }
        if (i != rows.size()) {
            throw new ApiException("The insert into child table '" + child.name() + "' returned " + i
                    + " generated id(s) for " + rows.size() + " row(s): the rows nested under them cannot be linked");
        }
        return out;
    }

    /** The rows of one table: every element of every holder's container, in order. */
    private List<Row> rows(PgChildTable child, List<Holder> holders) throws ApiException {
        List<Row> rows = new ArrayList<>();
        for (Holder holder : holders) {
            Object container = PgWriteSupport.valueAt(holder.object(), child.fieldPath());
            if (container == null) {
                continue;
            }
            if (child.ordered()) {
                addElements(rows, child, holder.rowId(), container);
            } else {
                addEntries(rows, child, holder.rowId(), container);
            }
        }
        return rows;
    }

    private void addElements(List<Row> rows, PgChildTable child, Long parent, Object container) throws ApiException {
        int ord = 0;
        for (Object element : elements(child, container)) {
            if (element == null && child.kind() == PgChildKind.COMPOSITION_COLLECTION) {
                continue;
            }
            rows.add(new Row(parent, ord, element));
            ord++;
        }
    }

    private void addEntries(List<Row> rows, PgChildTable child, Long parent, Object container) throws ApiException {
        if (!(container instanceof Map<?, ?> map)) {
            throw new ApiException("Field '" + child.dottedPath() + "' of table '" + table.name()
                    + "' is mapped as a map but holds a " + container.getClass().getName());
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw new ApiException("Map field '" + child.dottedPath() + "' of table '" + table.name()
                        + "' has a null key: a relational row needs one (" + PgChildTable.KEY + " is NOT NULL)");
            }
            rows.add(new Row(parent, entry.getKey(), entry.getValue()));
        }
    }

    private void bindValues(PreparedStatement insert, int first, PgChildTable child, Object element)
            throws SQLException, ApiException {
        int index = first;
        for (PgColumn column : child.valueColumns()) {
            Object value = column.kind() == PgColumnKind.COMPOSITION
                    ? references.uuidOf(child.composedCollection(), element)
                    : PgWriteSupport.valueAt(element, column.fieldPath());
            PgWriteSupport.bind(insert, index, column, PgValues.toJdbc(column, value));
            index++;
        }
    }

    /** The elements of a list, set or array, in iteration order. */
    private List<Object> elements(PgChildTable child, Object container) throws ApiException {
        if (container instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (container.getClass().isArray()) {
            int length = Array.getLength(container);
            List<Object> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                out.add(Array.get(container, i));
            }
            return out;
        }
        throw new ApiException("Field '" + child.dottedPath() + "' of table '" + table.name()
                + "' is mapped as a collection but holds a " + container.getClass().getName());
    }

    /** A column describing the map key, so the key converts like any other value. */
    private static PgColumn keyColumn(PgChildTable child) {
        String sqlType = PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT);
        return new PgColumn(PgChildTable.KEY, sqlType, PgColumnKind.SCALAR, List.of(), child.keyType());
    }

    private static String deleteSql(PgChildTable child) {
        return "DELETE FROM " + PgNaming.quote(child.name()) + " WHERE " + PgNaming.quote(PgChildTable.OWNER) + " = ?";
    }

    /** {@return the insert of one child table's row, package-visible for tests} */
    static String insertSql(PgChildTable child) {
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        names.add(PgNaming.quote(PgChildTable.OWNER));
        values.add("?");
        if (child.nested()) {
            names.add(PgNaming.quote(PgChildTable.PARENT));
            values.add("?");
        }
        names.add(PgNaming.quote(child.ordered() ? PgChildTable.ORD : PgChildTable.KEY));
        values.add("?");
        for (PgColumn column : child.valueColumns()) {
            names.add(PgNaming.quote(column.name()));
            values.add(PgValues.placeholder(column));
        }
        return "INSERT INTO " + PgNaming.quote(child.name()) + " (" + names + ") VALUES (" + values + ")";
    }
}
