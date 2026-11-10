package com.garganttua.dao.postgresql;

import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * What reading one select list into one class needs, resolved ONCE: for every cell, the fields its value
 * goes through, the type it decodes into, and the presence bits that may veto it.
 *
 * <p>
 * Everything here depends on the table and the class only, never on a row. Resolving it per cell of every
 * row — a lookup by name per path segment, maps keyed by field paths, prefixes cut out of those paths —
 * was most of what a read cost the JVM, more than the driver. A row is now two arrays, decoded in select
 * order, then set along fields already in hand.
 * </p>
 *
 * <p>
 * The rules are {@link PgRowMapper}'s, unchanged: NULL means absent, and a presence bit decides whether a
 * POJO exists — a present one is a FRESH instance, an absent one leaves the field and everything under it
 * as the constructor left them.
 * </p>
 *
 * <p>
 * Arrays, not varargs nor collections, on purpose: a row's cells and bits are positional and read in the
 * hottest loop of a read.
 * </p>
 */
@SuppressWarnings("PMD.UseVarargs")
final class PgRowPlan {

    /** A value cell: decoded into its field's declared type, set along its chain unless a structure above is absent. */
    private static final class Value {
        private final int offset;
        private final PgColumn column;
        private final IField[] chain;
        private final IClass<?> type;
        private final Type generic;
        private final int[] guards;
        /**
         * A {@code TEXT} column into a {@code String} field — most cells of most rows: the text is the value,
         * and the general decoding would ask a dozen questions of the field's type to find that out.
         */
        private final boolean text;

        private Value(int offset, PgColumn column, IField[] chain, int[] guards) {
            this.offset = offset;
            this.column = column;
            this.chain = chain;
            this.type = chain[chain.length - 1].getType();
            this.generic = chain[chain.length - 1].getGenericType();
            this.guards = guards;
            this.text = column.kind() == PgColumnKind.SCALAR && this.type.represents(String.class)
                    && PgTypes.TEXT.equalsIgnoreCase(column.sqlType());
        }
    }

    /** A presence cell: its bit, and — when it names a POJO — the chain the fresh instance is set along. */
    private static final class Presence {
        private final int slot;
        private final int offset;
        private final PgColumn column;
        private final List<String> path;
        private final String dottedPath;
        private final IField[] pojo;
        private final int[] guards;

        private Presence(int slot, int offset, PgColumn column, IField[] pojo, int[] guards) {
            this.slot = slot;
            this.offset = offset;
            this.column = column;
            this.path = column.fieldPath();
            this.dottedPath = String.join(".", column.fieldPath());
            this.pojo = pojo;
            this.guards = guards;
        }
    }

    /** A composition cell: the uuid it holds, recorded under its dotted path for the caller to resolve. */
    private record Reference(int offset, String dottedPath) {
    }

    /** One row, decoded in select order: values by value cell, bits by presence slot (null: absent). */
    static final class Decoded {
        final Object[] values;
        final Boolean[] presence;

        private Decoded(Object[] values, Boolean[] presence) {
            this.values = values;
            this.presence = presence;
        }
    }

    private static final IClass<Boolean> BOOLEAN = IClass.getClass(Boolean.class);

    private final PgBeans beans;
    private final IClass<?> type;
    private final List<Value> values;
    private final List<Presence> presence;
    /** The presence cells naming a POJO, shallowest first — an outer POJO exists before its fields are set. */
    private final List<Presence> pojos;
    private final List<Reference> references;
    /** The slot of the element's own bit (empty path), or -1 when it is not selected. */
    private final int self;

    private PgRowPlan(PgBeans beans, IClass<?> type, List<Value> values, List<Presence> presence,
            List<Reference> references) {
        this.beans = beans;
        this.type = type;
        this.values = values;
        this.presence = presence;
        this.references = references;
        this.pojos = presence.stream().filter(p -> p.pojo != null)
                .sorted(Comparator.comparingInt((Presence p) -> p.path.size())).toList();
        int found = -1;
        for (int i = 0; i < presence.size(); i++) {
            if (presence.get(i).path.isEmpty()) {
                found = i;
            }
        }
        this.self = found;
    }

    /**
     * Resolves a select list against a class.
     *
     * @param beans the reflection helper the fields are resolved and set with
     * @param type  the class the cells' field paths start from
     * @param cells the selected columns, in select-list order
     * @return the plan
     * @throws ApiException when a path does not exist on the class
     */
    static PgRowPlan of(PgBeans beans, IClass<?> type, List<PgSelected> cells) throws ApiException {
        return of(beans, type, cells, false);
    }

    /**
     * The plan of the presence bits alone — for an element that IS a collection: its row holds its own
     * bit, its content lives in the table below, and its type has no fields to resolve paths on.
     *
     * @param beans the reflection helper
     * @param type  the element type
     * @param cells the selected columns, in select-list order
     * @return the plan, reading no value
     * @throws ApiException never in practice: no path is resolved
     */
    static PgRowPlan presenceOnly(PgBeans beans, IClass<?> type, List<PgSelected> cells) throws ApiException {
        return of(beans, type, cells, true);
    }

    private static PgRowPlan of(PgBeans beans, IClass<?> type, List<PgSelected> cells, boolean presenceOnly)
            throws ApiException {
        List<List<String>> presencePaths = new ArrayList<>();
        for (PgSelected cell : cells) {
            if (cell.column().kind() == PgColumnKind.PRESENCE) {
                presencePaths.add(cell.column().fieldPath());
            }
        }
        List<Value> values = new ArrayList<>();
        List<Presence> presence = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            PgColumn column = cells.get(i).column();
            int[] guards = guards(column.fieldPath(), presencePaths);
            switch (column.kind()) {
                case PRESENCE -> presence.add(new Presence(presence.size(), i, column,
                        !presenceOnly && isPojo(beans, type, column.fieldPath())
                                ? beans.chain(type, column.fieldPath()) : null, guards));
                case COMPOSITION -> {
                    if (!presenceOnly) {
                        references.add(new Reference(i, column.dottedPath()));
                    }
                }
                default -> {
                    if (!presenceOnly) {
                        values.add(new Value(i, column, beans.chain(type, column.fieldPath()), guards));
                    }
                }
            }
        }
        return new PgRowPlan(beans, type, values, presence, references);
    }

    /** The presence slots whose path is a proper prefix of {@code path}: a NULL there vetoes the cell. */
    private static int[] guards(List<String> path, List<List<String>> presencePaths) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < presencePaths.size(); slot++) {
            List<String> prefix = presencePaths.get(slot);
            if (!prefix.isEmpty() && prefix.size() < path.size() && path.subList(0, prefix.size()).equals(prefix)) {
                slots.add(slot);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Whether a presence bit names a POJO — the others belong to collections and maps, read elsewhere. */
    private static boolean isPojo(PgBeans beans, IClass<?> type, List<String> path) throws ApiException {
        if (path.isEmpty()) {
            return false;
        }
        IClass<?> declared = beans.leaf(type, path).getType();
        return !declared.isArray() && !IClass.getClass(Collection.class).isAssignableFrom(declared)
                && !IClass.getClass(Map.class).isAssignableFrom(declared);
    }

    /**
     * Decodes one row's cells, recording its composition uuids.
     *
     * @param cells      the row
     * @param firstIndex the 1-based index of the plan's first cell
     * @param refs       receives the composition uuids by dotted path; null to skip them (an element)
     * @return the decoded row
     * @throws ApiException when a cell cannot be read into its type
     */
    Decoded decode(PgCells cells, int firstIndex, Map<String, String> refs) throws ApiException {
        for (Reference reference : this.references) {
            if (refs == null) {
                break;
            }
            try {
                refs.put(reference.dottedPath(), cells.text(firstIndex + reference.offset()));
            } catch (SQLException e) {
                throw new ApiException("Cannot read a reference uuid at column " + (firstIndex + reference.offset())
                        + ": " + e.getMessage(), e);
            }
        }
        Boolean[] bits = decodePresence(cells, firstIndex);
        Object[] decoded = new Object[this.values.size()];
        for (int i = 0; i < decoded.length; i++) {
            Value v = this.values.get(i);
            decoded[i] = v.text ? text(cells, firstIndex + v.offset, v.column)
                    : PgJdbcDecoder.decode(cells, firstIndex + v.offset, v.column, v.type, v.generic);
        }
        return new Decoded(decoded, bits);
    }

    private static String text(PgCells cells, int index, PgColumn column) throws ApiException {
        try {
            return cells.text(index);
        } catch (SQLException e) {
            throw new ApiException("Cannot read column '" + column.name() + "' (" + column.sqlType()
                    + ") into a String: " + e.getMessage(), e);
        }
    }

    /**
     * The presence bits alone of a row — for an element that IS a collection, whose content is below.
     *
     * @param cells      the row
     * @param firstIndex the 1-based index of the plan's first cell
     * @return the bits by presence slot
     * @throws ApiException when a cell cannot be read
     */
    Boolean[] decodePresence(PgCells cells, int firstIndex) throws ApiException {
        Boolean[] bits = new Boolean[this.presence.size()];
        for (int slot = 0; slot < bits.length; slot++) {
            Presence p = this.presence.get(slot);
            bits[slot] = (Boolean) PgJdbcDecoder.decode(cells, firstIndex + p.offset, p.column, BOOLEAN, Boolean.class);
        }
        return bits;
    }

    /**
     * The slot of the presence bit selected for a path — resolved when a table's plan is built, so that a
     * row's bit is then an array read.
     *
     * @param path a field path relative to the plan's class
     * @return the slot, or -1 when no presence bit is selected for that path
     */
    int slot(List<String> path) {
        for (Presence p : this.presence) {
            if (p.path.equals(path)) {
                return p.slot;
            }
        }
        return -1;
    }

    /**
     * Whether the decoded row holds an element at all: its own bit when selected, else any non-NULL value
     * (a table written before presence existed).
     */
    boolean exists(Decoded row) {
        if (this.self >= 0) {
            return row.presence[this.self] != null;
        }
        for (Object value : row.values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    /** {@return a fresh instance of the plan's class} */
    Object instantiate() throws ApiException {
        return this.beans.instantiate(this.type);
    }

    /**
     * Creates every present POJO (shallowest first, each a fresh instance), then sets every non-NULL value
     * that does not lie under an absent structure.
     */
    void assign(Object target, Decoded row) throws ApiException {
        Boolean[] bits = row.presence;
        for (Presence p : this.pojos) {
            if (bits[p.slot] != null && !vetoed(p.guards, bits)) {
                this.beans.set(target, p.pojo, this.beans.instantiate(p.pojo[p.pojo.length - 1].getType()), true);
            }
        }
        Object[] decoded = row.values;
        for (int i = 0; i < decoded.length; i++) {
            Value v = this.values.get(i);
            if (decoded[i] != null && !vetoed(v.guards, bits)) {
                this.beans.set(target, v.chain, decoded[i], true);
            }
        }
    }

    private static boolean vetoed(int[] guards, Boolean[] bits) {
        for (int slot : guards) {
            if (bits[slot] == null) {
                return true;
            }
        }
        return false;
    }

    /** Records the row's presence bits by dotted path, where the main row keeps them. */
    void presenceByDottedPath(Decoded row, Map<String, Boolean> into) {
        for (int slot = 0; slot < this.presence.size(); slot++) {
            into.put(this.presence.get(slot).dottedPath, row.presence[slot]);
        }
    }
}
