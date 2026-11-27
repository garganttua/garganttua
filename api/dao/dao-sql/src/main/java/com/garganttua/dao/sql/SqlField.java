package com.garganttua.dao.sql;

import java.util.List;

import com.garganttua.dao.sql.schema.SqlChildTable;

/**
 * What a filter's field name turned out to be, once resolved against the table model.
 *
 * <p>
 * MongoDB compares a field wherever it lives in the document; a relational row splits the same
 * document across a main row, child rows and flattened columns, and each place needs a different
 * predicate shape. Resolving first and generating second keeps that choice in one place.
 * </p>
 */
public sealed interface SqlField {

    /**
     * A single value of the main row: a column, or a path inside a JSONB column.
     *
     * @param operand the compared expression
     */
    record Scalar(SqlOperand operand) implements SqlField {
    }

    /**
     * A single value inside a collection's elements — at any depth: compared through one
     * {@code EXISTS} per collection on the way ({@link SqlHop#some}), with MongoDB's array semantics.
     *
     * @param hops    the path through the child tables, outermost first — at least one hop; the last
     *                one holds the compared value
     * @param operand the compared value inside the last hop's element (null only with {@code items})
     * @param whole   whether the filter names the collection itself ({@code tags}, {@code lines.tags})
     *                rather than a path inside its elements ({@code lines.sku}, {@code stock.apple})
     * @param items   when the collection's elements are THEMSELVES arrays ({@code List<List<String>>}):
     *                the hop from one element to its items, which {@code operand} then reads; null
     *                otherwise
     */
    record Element(List<SqlHop> hops, SqlOperand operand, boolean whole, SqlHop items) implements SqlField {

        public Element {
            hops = List.copyOf(hops);
        }

        /** {@return the hop holding the compared value} */
        public SqlHop last() {
            return hops.get(hops.size() - 1);
        }

        /** {@return the hops above the last one} */
        public List<SqlHop> parents() {
            return hops.subList(0, hops.size() - 1);
        }

        /** {@return the child table holding the compared value} */
        public SqlChildTable child() {
            return last().child();
        }

        /**
         * {@return the predicate "this path is missing from the document on SOME branch" — what a
         * comparison MongoDB satisfies with a missing value ({@code $eq null}) also matches: a collection
         * on the way is absent (an EMPTY one is present and yields no branch), or, for a map, the key is}
         */
        SqlFragment missing() {
            return SqlHop.someMisses(hops, last().absent());
        }
    }

    /**
     * Something that holds no comparable value — an embedded object, a whole collection of objects,
     * a map, a reference — or nothing at all: a path the model does not know (MongoDB has no schema,
     * so an unknown field is simply absent from every document). A scalar never equals an object, so
     * only presence can be asked of it; see {@link SqlFilterTranslator}.
     *
     * @param absent  the predicate "absent from the document" ({@code $empty})
     * @param nullish the predicate "absent, or null" ({@code $eq null}) — the same, except for an
     *                array holding a null element
     */
    record Opaque(SqlFragment absent, SqlFragment nullish) implements SqlField {

        /** A path absent from every document. */
        public static final Opaque MISSING = new Opaque(SqlFragment.TRUE, SqlFragment.TRUE);

        /** {@return an object whose absence and nullness are the same test} */
        public static Opaque of(SqlFragment absent) {
            return new Opaque(absent, absent);
        }
    }
}
