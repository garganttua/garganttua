package com.garganttua.dao.postgresql;

import java.util.List;

import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;

/**
 * What a filter's field name turned out to be, once resolved against the table model.
 *
 * <p>
 * MongoDB compares a field wherever it lives in the document; a relational row splits the same
 * document across a main row, child rows and flattened columns, and each place needs a different
 * predicate shape. Resolving first and generating second keeps that choice in one place.
 * </p>
 */
sealed interface PgField {

    /**
     * A single value of the main row: a column, or a path inside a JSONB column.
     *
     * @param operand the compared expression
     */
    record Scalar(PgOperand operand) implements PgField {
    }

    /**
     * Something inside a collection: compared through {@code EXISTS} on the child table.
     *
     * @param child   the child table
     * @param scope   the condition selecting the owner's rows (and, for a map entry, its key)
     * @param operand the compared value inside the element, or null when the element is an object
     *                that can only be tested for presence
     * @param whole   whether the filter names the collection itself ({@code tags}) rather than a
     *                path inside its elements ({@code lines.sku}) — only the former is "empty" when
     *                it has no rows at all
     */
    record Element(PgChildTable child, PgSql scope, PgOperand operand, boolean whole) implements PgField {
    }

    /**
     * An embedded POJO flattened into columns: only presence can be tested.
     *
     * @param columns its flattened main-table columns
     */
    record Pojo(List<PgColumn> columns) implements PgField {
    }
}
