package com.garganttua.dao.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * Rebuilds an object — a DTO, or the element of a POJO collection — from the flattened columns of
 * one row.
 *
 * <p>
 * It reads a row the way the MongoDB reader reads a document, which is what makes the two DAOs
 * answer alike:
 * </p>
 * <ul>
 * <li><b>NULL means absent.</b> The MongoDB writer omits a null field and its reader skips an absent
 * key, so a field initialiser ({@code String status = "draft"}) survives a stored null. Here a NULL
 * cell is therefore never written into a field: the value the no-arg constructor left is kept.</li>
 * <li><b>Presence decides whether a POJO exists.</b> Flattening cannot tell {@code address = null}
 * from {@code new Address()} with every field null — both are all-NULL columns — so the schema keeps
 * a presence column beside them. {@code TRUE}: the POJO existed and is rebuilt as a FRESH instance
 * (as MongoDB builds a sub-document, whatever the initialiser put there), even when all its fields
 * are null. {@code NULL}: it did not, and the field is left as the constructor left it.</li>
 * </ul>
 */
final class PgRowMapper {

    private final PgBeans beans;

    PgRowMapper(PgBeans beans) {
        this.beans = beans;
    }

    /**
     * Fills a DTO from consecutive cells of the current row. Composition cells are not set — they
     * hold uuids the caller resolves later — but collected into the row's references; presence cells
     * are applied to the POJOs and kept on the row for the collections read later.
     *
     * @param row        the row being read: its instance is filled, its references and presence recorded
     * @param type       the DTO class, where the columns' field paths start
     * @param rs         the result set, positioned on the row
     * @param cells      the selected columns, in select-list order
     * @param firstIndex the 1-based index of the first of {@code cells} in the result set
     * @throws ApiException when a cell cannot be read or set
     */
    void fill(PgLoadedRow row, IClass<?> type, ResultSet rs, List<PgSelected> cells, int firstIndex)
            throws ApiException {
        Cells read = read(type, rs, cells, firstIndex, row.references());
        read.presence().forEach((path, present) -> row.presence().put(String.join(".", path), present));
        assign(row.instance(), type, read);
    }

    /**
     * An element read from a child row, with the presence bits of the structures it holds.
     *
     * @param value    the element, or null when the element itself was null
     * @param presence the presence cells of the row by field path relative to the element — the
     *                 element's own bit under the empty path — so the collections it holds, read from
     *                 the tables below, can tell "empty" from "absent"
     */
    record Element(Object value, Map<List<String>, Boolean> presence) {
    }

    /**
     * A new element built from consecutive cells — null when the element itself was null — with the
     * row's presence bits.
     *
     * <p>
     * The element's own presence cell ({@code _present}, empty path) tells a null element from one
     * whose fields are all null. Without it (a table written before presence existed), the old rule
     * applies: every cell NULL reads as a null element.
     * </p>
     *
     * @param type       the element class
     * @param rs         the result set, positioned on the row
     * @param cells      the element's value columns
     * @param firstIndex the 1-based index of the first cell
     * @return the element and its presence bits
     * @throws ApiException when a cell cannot be read or set
     */
    Element readElement(IClass<?> type, ResultSet rs, List<PgSelected> cells, int firstIndex) throws ApiException {
        Cells read = read(type, rs, cells, firstIndex, new LinkedHashMap<>());
        List<String> self = List.of();
        boolean exists = read.presence().containsKey(self)
                ? read.presence().get(self) != null
                : read.values().values().stream().anyMatch(v -> v != null);
        if (!exists) {
            return new Element(null, read.presence());
        }
        Object element = beans.instantiate(type);
        assign(element, type, read);
        return new Element(element, read.presence());
    }

    /**
     * The presence cells alone of a row — for an element that IS a collection or a map, whose only value
     * column is its presence bit and whose content lives in the table below.
     *
     * @param rs         the result set, positioned on the row
     * @param cells      the value columns
     * @param firstIndex the 1-based index of the first cell
     * @return the presence bits by field path
     * @throws ApiException when a cell cannot be read
     */
    Map<List<String>, Boolean> presence(ResultSet rs, List<PgSelected> cells, int firstIndex) throws ApiException {
        Map<List<String>, Boolean> presence = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            PgSelected cell = cells.get(i);
            if (cell.column().kind() == PgColumnKind.PRESENCE) {
                presence.put(cell.column().fieldPath(), bit(rs, firstIndex + i, cell));
            }
        }
        return presence;
    }

    private static Boolean bit(ResultSet rs, int index, PgSelected cell) throws ApiException {
        return (Boolean) PgJdbcDecoder.decode(rs, index, cell.column(), IClass.getClass(Boolean.class), Boolean.class);
    }

    /** The decoded cells of one row: values and presence bits, by field path. */
    private record Cells(Map<List<String>, Object> values, Map<List<String>, Boolean> presence) {
    }

    private Cells read(IClass<?> type, ResultSet rs, List<PgSelected> cells, int firstIndex,
            Map<String, String> references) throws ApiException {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        Map<List<String>, Boolean> presence = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            PgSelected cell = cells.get(i);
            PgColumnKind kind = cell.column().kind();
            if (kind == PgColumnKind.COMPOSITION) {
                references.put(cell.column().dottedPath(), text(rs, firstIndex + i));
            } else if (kind == PgColumnKind.PRESENCE) {
                presence.put(cell.column().fieldPath(), bit(rs, firstIndex + i, cell));
            } else {
                values.put(cell.column().fieldPath(), decode(type, rs, firstIndex + i, cell));
            }
        }
        return new Cells(values, presence);
    }

    private Object decode(IClass<?> type, ResultSet rs, int index, PgSelected cell) throws ApiException {
        IField field = beans.leaf(type, cell.column().fieldPath());
        return PgJdbcDecoder.decode(rs, index, cell.column(), field.getType(), field.getGenericType());
    }

    /**
     * Creates every present POJO (shallowest first, each a fresh instance), then sets every non-NULL
     * value that does not lie under an absent one.
     */
    private void assign(Object target, IClass<?> type, Cells cells) throws ApiException {
        List<List<String>> present = new ArrayList<>();
        for (Map.Entry<List<String>, Boolean> bit : cells.presence().entrySet()) {
            if (bit.getValue() != null && !bit.getKey().isEmpty() && isPojo(type, bit.getKey())) {
                present.add(bit.getKey());
            }
        }
        present.sort(Comparator.comparingInt(List::size));
        for (List<String> path : present) {
            if (!underAbsent(path, cells.presence())) {
                beans.set(target, path, beans.instantiate(beans.leaf(type, path).getType()), true);
            }
        }
        for (Map.Entry<List<String>, Object> entry : cells.values().entrySet()) {
            if (entry.getValue() != null && !underAbsent(entry.getKey(), cells.presence())) {
                beans.set(target, entry.getKey(), entry.getValue(), true);
            }
        }
    }

    /** Whether a structure at a path is a POJO (the other presence bits belong to collections and maps). */
    private boolean isPojo(IClass<?> type, List<String> path) throws ApiException {
        IClass<?> declared = beans.leaf(type, path).getType();
        return !declared.isArray() && !IClass.getClass(Collection.class).isAssignableFrom(declared)
                && !IClass.getClass(Map.class).isAssignableFrom(declared);
    }

    /** Whether a proper prefix of the path is a structure known to be absent (presence selected, NULL). */
    static boolean underAbsent(List<String> path, Map<List<String>, Boolean> presence) {
        for (int k = 1; k < path.size(); k++) {
            List<String> prefix = path.subList(0, k);
            if (presence.containsKey(prefix) && presence.get(prefix) == null) {
                return true;
            }
        }
        return false;
    }

    private static String text(ResultSet rs, int index) throws ApiException {
        try {
            return rs.getString(index);
        } catch (SQLException e) {
            throw new ApiException("Cannot read a reference uuid at column " + index + ": " + e.getMessage(), e);
        }
    }
}
