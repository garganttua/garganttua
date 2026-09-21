package com.garganttua.dao.postgresql;

import com.garganttua.dao.postgresql.schema.PgChildTable;

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
     * A single value inside a collection's elements: compared through {@code EXISTS} on the child
     * table, with MongoDB's array semantics.
     *
     * @param child   the child table
     * @param scope   the condition selecting the owner's rows (and, for a map entry, its key)
     * @param operand the compared value inside the element
     * @param whole   whether the filter names the collection itself ({@code tags}) rather than a
     *                path inside its elements ({@code lines.sku}, {@code stock.apple})
     * @param missing the predicate "this path is missing from the document" — what a comparison
     *                MongoDB satisfies with a missing value ({@code $eq null}) also matches: the
     *                collection is absent (an EMPTY array is present and does not match), or, for a
     *                map, the key is absent
     */
    record Element(PgChildTable child, PgSql scope, PgOperand operand, boolean whole, PgSql missing)
            implements PgField {
    }

    /**
     * Something that holds no comparable value — an embedded object, a whole collection of objects,
     * a map, a reference — or nothing at all: a path the model does not know (MongoDB has no schema,
     * so an unknown field is simply absent from every document). A scalar never equals an object, so
     * only presence can be asked of it; see {@link PgFilterTranslator}.
     *
     * @param absent  the predicate "absent from the document" ({@code $empty})
     * @param nullish the predicate "absent, or null" ({@code $eq null}) — the same, except for an
     *                array holding a null element
     */
    record Opaque(PgSql absent, PgSql nullish) implements PgField {

        /** A path absent from every document. */
        static final Opaque MISSING = new Opaque(PgSql.TRUE, PgSql.TRUE);

        /** {@return an object whose absence and nullness are the same test} */
        static Opaque of(PgSql absent) {
            return new Opaque(absent, absent);
        }
    }
}
