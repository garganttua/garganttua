package com.garganttua.dao.postgresql;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Reads the child tables of a page of rows and puts each collection back on its owner.
 *
 * <p>
 * ONE query per child table for the whole page ({@code _owner = ANY(?)}), never one per row: a page
 * of 50 orders with 3 collections costs 4 statements, not 151. The rows come back sorted by owner
 * then position, so each owner's elements arrive in the order they were written.
 * </p>
 *
 * <p>
 * Zero child rows mean either "empty" or "null", and only the owner's presence column knows which —
 * MongoDB keeps {@code []} and an absent field apart, so the reader must too. Presence {@code TRUE}:
 * the collection is built, empty when there are no rows. Presence NULL: the field is left as the
 * constructor left it, exactly as the MongoDB reader leaves a field whose key is absent.
 * </p>
 */
final class PgChildLoader {

    /** Child-table result columns before the value columns: {@code _owner}, then {@code _ord} or {@code _key}. */
    private static final int FIRST_VALUE = 3;

    private final PgBeans beans;
    private final PgRowMapper mapper;

    PgChildLoader(PgBeans beans, PgRowMapper mapper) {
        this.beans = beans;
        this.mapper = mapper;
    }

    /**
     * Loads one child table for every row of the page.
     *
     * @param connection the connection
     * @param child      the child table
     * @param rows       the page, in order
     * @throws ApiException when the query fails or a value cannot be rebuilt
     */
    void load(Connection connection, PgChildTable child, List<PgLoadedRow> rows) throws ApiException {
        String sql = selectSql(child);
        Map<String, List<Map.Entry<Object, Object>>> byOwner = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, owners(connection, rows));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    byOwner.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(entry(rs, child));
                }
            }
        } catch (SQLException e) {
            throw new ApiException("Cannot read child table '" + child.name() + "' (collection '"
                    + child.dottedPath() + "'): " + e.getMessage() + " — SQL: " + sql, e);
        }
        for (PgLoadedRow row : rows) {
            place(row, child, byOwner.getOrDefault(row.id(), List.of()));
        }
    }

    /** {@return the select statement of a child table, package-visible for tests} */
    static String selectSql(PgChildTable child) {
        StringJoiner list = new StringJoiner(", ");
        list.add(PgNaming.quote(PgChildTable.OWNER));
        String position = PgNaming.quote(child.ordered() ? PgChildTable.ORD : PgChildTable.KEY);
        list.add(position);
        for (PgColumn column : child.valueColumns()) {
            list.add(PgSelected.of(null, column).expression());
        }
        return "SELECT " + list + " FROM " + PgNaming.quote(child.name()) + " WHERE "
                + PgNaming.quote(PgChildTable.OWNER) + " = ANY(?) ORDER BY "
                + PgNaming.quote(PgChildTable.OWNER) + ", " + position;
    }

    private static Array owners(Connection connection, List<PgLoadedRow> rows) throws SQLException {
        return connection.createArrayOf("text", rows.stream().map(PgLoadedRow::id).toArray());
    }

    /** One child row as (map key or null, element). */
    private Map.Entry<Object, Object> entry(ResultSet rs, PgChildTable child) throws ApiException {
        Object key = null;
        if (child.kind() == PgChildKind.MAP) {
            PgColumn keyColumn = new PgColumn(PgChildTable.KEY, "", PgColumnKind.SCALAR, List.of(), child.keyType());
            key = PgJdbcDecoder.decode(rs, 2, keyColumn, child.keyType(), child.keyType().getType());
        }
        return new AbstractMap.SimpleEntry<>(key, value(rs, child));
    }

    private Object value(ResultSet rs, PgChildTable child) throws ApiException {
        List<PgColumn> columns = child.valueColumns();
        IClass<?> element = child.elementType();
        if (columns.size() == 1 && columns.get(0).kind() != PgColumnKind.PRESENCE
                && columns.get(0).fieldPath().isEmpty()) {
            PgColumn value = columns.get(0);
            IClass<?> type = child.kind() == PgChildKind.COMPOSITION_COLLECTION
                    ? IClass.getClass(String.class) : element;
            return PgJdbcDecoder.decode(rs, FIRST_VALUE, value, type, type.getType());
        }
        List<PgSelected> cells = columns.stream().map(c -> PgSelected.of(null, c)).toList();
        return mapper.element(element, rs, cells, FIRST_VALUE);
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
        Object built = child.kind() == PgChildKind.MAP
                ? PgCollections.map(child.collectionType(), entries)
                : PgCollections.collection(child.collectionType(), child.elementType(),
                        entries.stream().map(Map.Entry::getValue).toList());
        // Present (or presence not selected but rows found): set it, creating a missing owner POJO.
        // With no presence bit and no rows, an empty collection must not bring a null POJO into
        // existence.
        boolean known = row.presence().containsKey(child.dottedPath());
        beans.set(row.instance(), child.fieldPath(), built, known || !entries.isEmpty());
    }
}
