package com.garganttua.dao.postgresql;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * Writes the rows of one collection or map field: delete every row of the owner, insert the current
 * elements.
 *
 * <p>
 * Replacing rather than diffing is deliberate. An upsert REPLACES the entity, collections included,
 * as MongoDB's {@code save()} replaces the document; working out which elements moved, changed or
 * disappeared would buy nothing but code, since the caller's transaction makes delete-then-insert
 * atomic anyway.
 * </p>
 *
 * <p>
 * A null or empty collection is written as zero rows; the owner's presence column (written with the
 * main row) is what tells them apart. A null element of a scalar or POJO collection
 * is a row whose value columns are NULL — it keeps its position; for a POJO element, its
 * {@code _present} column is NULL too, which is how the reader tells it from an element whose fields
 * are all null. A null element of a REFERENCE
 * collection is skipped, as the MongoDB DAO skips it when building its {@code DBRef} list: there is
 * no entity to point to.
 * </p>
 */
final class PgWriteChildren {

    private final PgTable table;
    private final PgWriteReferences references;

    PgWriteChildren(PgTable table, PgWriteReferences references) {
        this.table = table;
        this.references = references;
    }

    /**
     * Replaces the rows of one child table for one owner.
     *
     * @param connection the caller's connection
     * @param child      the child table
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
        Object container = PgWriteSupport.valueAt(dto, child.fieldPath());
        if (container == null) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(insertSql(child))) {
            int rows = child.ordered() ? addElements(insert, child, ownerId, container)
                    : addEntries(insert, child, ownerId, container);
            if (rows > 0) {
                insert.executeBatch();
            }
        }
    }

    private int addElements(PreparedStatement insert, PgChildTable child, Object ownerId, Object container)
            throws SQLException, ApiException {
        int ord = 0;
        for (Object element : elements(child, container)) {
            if (element == null && child.kind() == PgChildKind.COMPOSITION_COLLECTION) {
                continue;
            }
            insert.setObject(1, ownerId);
            insert.setObject(2, ord);
            ord++;
            bindValues(insert, child, element);
            insert.addBatch();
        }
        return ord;
    }

    private int addEntries(PreparedStatement insert, PgChildTable child, Object ownerId, Object container)
            throws SQLException, ApiException {
        if (!(container instanceof Map<?, ?> map)) {
            throw new ApiException("Field '" + child.dottedPath() + "' of table '" + table.name()
                    + "' is mapped as a map but holds a " + container.getClass().getName());
        }
        PgColumn keyColumn = keyColumn(child);
        int rows = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                throw new ApiException("Map field '" + child.dottedPath() + "' of table '" + table.name()
                        + "' has a null key: a relational row needs one (" + PgChildTable.KEY + " is NOT NULL)");
            }
            insert.setObject(1, ownerId);
            PgWriteSupport.bind(insert, 2, keyColumn, PgValues.toJdbc(keyColumn, entry.getKey()));
            bindValues(insert, child, entry.getValue());
            insert.addBatch();
            rows++;
        }
        return rows;
    }

    private void bindValues(PreparedStatement insert, PgChildTable child, Object element)
            throws SQLException, ApiException {
        int index = 3;
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

    private static String insertSql(PgChildTable child) {
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        names.add(PgNaming.quote(PgChildTable.OWNER));
        values.add("?");
        names.add(PgNaming.quote(child.ordered() ? PgChildTable.ORD : PgChildTable.KEY));
        values.add("?");
        for (PgColumn column : child.valueColumns()) {
            names.add(PgNaming.quote(column.name()));
            values.add(PgValues.placeholder(column));
        }
        return "INSERT INTO " + PgNaming.quote(child.name()) + " (" + names + ") VALUES (" + values + ")";
    }
}
