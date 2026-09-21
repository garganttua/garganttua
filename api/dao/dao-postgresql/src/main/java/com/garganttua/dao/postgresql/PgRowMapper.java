package com.garganttua.dao.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * Rebuilds an object — a DTO, or the element of a POJO collection — from the flattened columns of
 * one row.
 *
 * <p>
 * Flattening loses one bit: {@code address = null} and {@code address = new Address()} with every
 * field null both become all-NULL columns. The shared rule is that all-NULL reads back as null — the
 * likelier intent, and the one that does not invent objects out of empty columns. It is enforced
 * explicitly rather than by merely not creating the POJO, because a DTO whose field initialiser
 * builds the POJO ({@code Address address = new Address()}) would otherwise read back a non-null
 * shell that was stored as null.
 * </p>
 */
final class PgRowMapper {

    private final PgBeans beans;

    PgRowMapper(PgBeans beans) {
        this.beans = beans;
    }

    /**
     * Fills {@code target} from consecutive cells of the current row. Composition cells are not set
     * — they hold uuids the caller resolves later — but collected into {@code references}.
     *
     * @param target     the object to fill
     * @param type       its class, where the columns' field paths start
     * @param rs         the result set, positioned on the row
     * @param cells      the selected columns, in select-list order
     * @param firstIndex the 1-based index of the first of {@code cells} in the result set
     * @param references receives the stored uuid of every composition cell, by dotted path
     * @throws ApiException when a cell cannot be read or set
     */
    void fill(Object target, IClass<?> type, ResultSet rs, List<PgSelected> cells, int firstIndex,
            Map<String, String> references) throws ApiException {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            PgSelected cell = cells.get(i);
            if (cell.column().kind() == PgColumnKind.COMPOSITION) {
                references.put(cell.column().dottedPath(), text(rs, firstIndex + i));
                continue;
            }
            values.put(cell.column().fieldPath(), read(type, rs, firstIndex + i, cell));
        }
        assign(target, values);
    }

    /**
     * A new element built from consecutive cells, or null when every cell is NULL (the all-NULL rule,
     * applied to the element itself).
     *
     * @param type       the element class
     * @param rs         the result set, positioned on the row
     * @param cells      the element's value columns
     * @param firstIndex the 1-based index of the first cell
     * @return the element, or null
     * @throws ApiException when a cell cannot be read or set
     */
    Object element(IClass<?> type, ResultSet rs, List<PgSelected> cells, int firstIndex) throws ApiException {
        Map<List<String>, Object> values = new LinkedHashMap<>();
        boolean any = false;
        for (int i = 0; i < cells.size(); i++) {
            Object value = read(type, rs, firstIndex + i, cells.get(i));
            any |= value != null;
            values.put(cells.get(i).column().fieldPath(), value);
        }
        if (!any) {
            return null;
        }
        Object element = beans.instantiate(type);
        assign(element, values);
        return element;
    }

    private Object read(IClass<?> type, ResultSet rs, int index, PgSelected cell) throws ApiException {
        IField field = beans.leaf(type, cell.column().fieldPath());
        return PgJdbcDecoder.decode(rs, index, cell.column(), field.getType(), field.getGenericType());
    }

    /**
     * Sets every value along its path, then nulls every embedded POJO none of whose cells held a value.
     * Shortest prefixes are nulled first, so a deeper prefix finds its parent already gone and stops.
     */
    private void assign(Object target, Map<List<String>, Object> values) throws ApiException {
        Set<List<String>> prefixes = new LinkedHashSet<>();
        Set<List<String>> live = new HashSet<>();
        for (Map.Entry<List<String>, Object> entry : values.entrySet()) {
            List<String> path = entry.getKey();
            for (int k = 1; k < path.size(); k++) {
                prefixes.add(path.subList(0, k));
                if (entry.getValue() != null) {
                    live.add(path.subList(0, k));
                }
            }
            beans.set(target, path, entry.getValue(), entry.getValue() != null);
        }
        List<List<String>> dead = new ArrayList<>(prefixes);
        dead.removeAll(live);
        dead.sort(Comparator.comparingInt(List::size));
        for (List<String> prefix : dead) {
            beans.set(target, prefix, null, false);
        }
    }

    private static String text(ResultSet rs, int index) throws ApiException {
        try {
            return rs.getString(index);
        } catch (SQLException e) {
            throw new ApiException("Cannot read a reference uuid at column " + index + ": " + e.getMessage(), e);
        }
    }
}
