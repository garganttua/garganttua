package com.garganttua.dao.postgresql;

import java.sql.ResultSet;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;

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
 *
 * <p>
 * What a select list needs is resolved once into a {@link PgRowPlan}: a caller builds the plan of its
 * select list, then reads every row with it.
 * </p>
 */
final class PgRowMapper {

    private final PgBeans beans;

    PgRowMapper(PgBeans beans) {
        this.beans = beans;
    }

    /**
     * The plan of a select list read into a class — built once, then used for every row.
     *
     * @param type  the class the cells' field paths start from
     * @param cells the selected columns, in select-list order
     * @return the plan
     * @throws ApiException when a path does not exist on the class
     */
    PgRowPlan plan(IClass<?> type, List<PgSelected> cells) throws ApiException {
        return PgRowPlan.of(this.beans, type, cells);
    }

    /**
     * Fills a DTO from consecutive cells of the current row. Composition cells are not set — they
     * hold uuids the caller resolves later — but collected into the row's references; presence cells
     * are applied to the POJOs and kept on the row for the collections read later.
     *
     * @param row        the row being read: its instance is filled, its references and presence recorded
     * @param plan       the plan of the select list, on the DTO class
     * @param rs         the result set, positioned on the row
     * @param firstIndex the 1-based index of the first cell of the plan in the result set
     * @throws ApiException when a cell cannot be read or set
     */
    void fill(PgLoadedRow row, PgRowPlan plan, ResultSet rs, int firstIndex) throws ApiException {
        PgRowPlan.Decoded read = plan.decode(PgCells.of(rs), firstIndex, row.references());
        plan.presenceByDottedPath(read, row.presence());
        plan.assign(row.instance(), read);
    }

    /**
     * An element read from a child row, with the presence bits of the structures it holds — by slot of
     * the element's plan ({@link PgRowPlan#slot}), the element's own bit under the empty path — so the
     * collections it holds, read from the tables below, can tell "empty" from "absent".
     */
    @SuppressWarnings({ "PMD.UseVarargs", "PMD.ArrayIsStoredDirectly" }) // the row's own bits, handed over
    static final class Element {
        final Object value;
        final Boolean[] presence;

        Element(Object value, Boolean[] presence) {
            this.value = value;
            this.presence = presence;
        }
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
     * @param plan       the plan of the element's value columns, on the element class
     * @param rs         the cells, positioned on the row
     * @param firstIndex the 1-based index of the first cell
     * @return the element and its presence bits
     * @throws ApiException when a cell cannot be read or set
     */
    Element readElement(PgRowPlan plan, PgCells rs, int firstIndex) throws ApiException {
        PgRowPlan.Decoded read = plan.decode(rs, firstIndex, null);
        if (!plan.exists(read)) {
            return new Element(null, read.presence);
        }
        Object element = plan.instantiate();
        plan.assign(element, read);
        return new Element(element, read.presence);
    }
}
