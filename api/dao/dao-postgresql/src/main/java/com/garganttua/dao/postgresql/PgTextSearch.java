package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlQuery;
import com.garganttua.dao.sql.SqlTextQuery;

/**
 * MongoDB's {@code $text} over a wildcard ({@code "$**"}) text index, reproduced without any index.
 *
 * <p>
 * The applications index every string of a document for text search, so {@code $text} looks at
 * EVERY string value of the entity — whatever field the filter names. The relational model
 * scatters those strings, so the searched document is re-assembled per row: the main table's TEXT
 * columns (the uuid and enums included), the strings inside its JSONB documents, the type of its
 * geometries (a GeoJSON {@code "type"} is a string to MongoDB), and, from every child table at every
 * depth, the TEXT values of scalar collections, POJO elements and map values — every child table
 * carries the ROOT id in {@code _owner}, so a nested one is read like a top-level one. Map KEYS are
 * left out: MongoDB stores them as field names, which a text index does not see. Values are joined
 * with {@code chr(1)}, so a phrase can never straddle two values — MongoDB matches a phrase inside
 * one field.
 * </p>
 *
 * <p>
 * The search string is decomposed by {@link SqlTextQuery}; words go through the {@code english}
 * text-search configuration — MongoDB's default language — for the same stemming and stop words.
 * Diacritics are folded with {@code translate()} on both sides. Case folding of the document is
 * PostgreSQL's {@code lower()} / text-search lowercasing, which follows the database's
 * {@code LC_CTYPE}: on a {@code C}-locale database, non-Latin capitals (Greek, Cyrillic) are not
 * folded; Latin letters are, since their diacritic-free forms are ASCII.
 * </p>
 *
 * <p>
 * MongoDB's restrictions are enforced by {@link #validate} before any SQL is built, with MongoDB's
 * reasons: one {@code $text} per filter, never under {@code $nor}, never under {@code $or} (MongoDB
 * would need an index on every other branch; with the wildcard text index alone it refuses the
 * query). Without an index, the search is a sequential scan.
 * </p>
 */
final class PgTextSearch {

    private static final String CONFIG = "'english'";
    private static final String SEPARATOR = "chr(1)";
    private static final String OWNER_ALIAS = "tx";
    private static final int GEOMETRY_TYPE_PREFIX = 4;

    private PgTextSearch() {
    }

    /**
     * The predicate of a {@code $text} search on the main row {@code t}.
     *
     * @param table  the domain's model
     * @param search the search string
     * @return the predicate — never NULL
     * @throws ApiException when there is no search string
     */
    static SqlFragment predicate(SqlTable table, Object search) {
        if (search == null) {
            throw new ApiException("$text filter requires a search string");
        }
        SqlTextQuery query = SqlTextQuery.parse(search.toString());
        if (query.positive().isEmpty()) {
            return SqlFragment.FALSE;
        }
        List<SqlFragment> conditions = new ArrayList<>();
        conditions.add(SqlFragment.of("to_tsvector(" + CONFIG + ", translate(s.d, ?, ?)) @@ ",
                SqlTextQuery.DELIMITERS, SqlTextQuery.DELIMITER_SPACES).then(tsquery(query)));
        for (String phrase : query.phrases()) {
            conditions.add(SqlFragment.of("strpos(lower(s.d), lower(?)) > 0", phrase));
        }
        for (String phrase : query.negatedPhrases()) {
            conditions.add(SqlFragment.of("strpos(lower(s.d), lower(?)) = 0", phrase));
        }
        return SqlFragment.of("EXISTS (SELECT 1 FROM (SELECT translate(" + document(table) + ", ?, ?) AS d) s WHERE ",
                SqlTextQuery.FOLD_FROM, SqlTextQuery.FOLD_TO).then(SqlFragment.join(" AND ", conditions)).then(")");
    }

    /**
     * The folded text of one column, with the folding written as LITERALS rather than bind
     * parameters: this is the expression a text index is built on, and PostgreSQL uses an expression
     * index only when the query spells the very same expression — a parameter is not a literal to the
     * planner.
     *
     * @param columnRef the column, as SQL ({@code "title"} or {@code t."title"})
     * @return the folded text expression
     */
    static String folded(String columnRef) {
        return "translate(" + columnRef + ", " + literal(SqlTextQuery.FOLD_FROM) + ", "
                + literal(SqlTextQuery.FOLD_TO) + ")";
    }

    /**
     * The text-search vector of one column — the key of a declared text index, and what {@code $text}
     * queries when the domain declares one.
     *
     * @param columnRef the column, as SQL
     * @return the vector expression
     */
    static String indexedVector(String columnRef) {
        return "to_tsvector(" + CONFIG + ", translate(" + folded(columnRef) + ", "
                + literal(SqlTextQuery.DELIMITERS) + ", " + literal(SqlTextQuery.DELIMITER_SPACES) + "))";
    }

    /**
     * A constant of this class as an SQL string literal. Safe because the text is ours, never a
     * caller's; quotes are doubled, which is the standard ({@code standard_conforming_strings}, on by
     * default since PostgreSQL 9.1) spelling.
     */
    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * The predicate of a {@code $text} search restricted to the ONE field a text index is declared
     * on — what MongoDB searches when the collection's text index covers that field. It spells the
     * index expression exactly, so the index serves the search.
     *
     * @param column the declared text column
     * @param search the search string
     * @return the predicate — never NULL
     */
    static SqlFragment predicateOnIndexed(SqlColumn column, Object search) {
        if (search == null) {
            throw new ApiException("$text filter requires a search string");
        }
        SqlTextQuery query = SqlTextQuery.parse(search.toString());
        if (query.positive().isEmpty()) {
            return SqlFragment.FALSE;
        }
        String ref = SqlQuery.ALIAS + "." + SqlNaming.quote(column.name());
        List<SqlFragment> conditions = new ArrayList<>();
        conditions.add(SqlFragment.of(indexedVector(ref) + " @@ ").then(tsquery(query)));
        for (String phrase : query.phrases()) {
            conditions.add(SqlFragment.of("strpos(lower(" + folded(ref) + "), lower(?)) > 0", phrase));
        }
        for (String phrase : query.negatedPhrases()) {
            conditions.add(SqlFragment.of("coalesce(strpos(lower(" + folded(ref) + "), lower(?)), 0) = 0", phrase));
        }
        return SqlFragment.join(" AND ", conditions).wrap("(", ")");
    }

    /** ANY positive term, and none of the negated ones; a stop word alone is empty and ignored. */
    private static SqlFragment tsquery(SqlTextQuery query) {
        List<SqlFragment> any = new ArrayList<>();
        for (String term : query.positive()) {
            any.add(SqlFragment.of("plainto_tsquery(" + CONFIG + ", ?)", term));
        }
        SqlFragment result = SqlFragment.join(" || ", any).wrap("(", ")");
        for (String term : query.negated()) {
            result = result.then(SqlFragment.of(" && !!plainto_tsquery(" + CONFIG + ", ?)", term));
        }
        return result.wrap("(", ")");
    }

    /** Every string of the entity, as one SQL text expression over the main row. */
    private static String document(SqlTable table) {
        List<String> parts = new ArrayList<>();
        for (SqlColumn column : table.columns()) {
            strings(column, SqlQuery.ALIAS).ifPresent(parts::add);
        }
        String owner = SqlQuery.ALIAS + "." + SqlNaming.quote(table.id().name());
        for (SqlChildTable child : table.allChildren()) {
            List<String> values = new ArrayList<>();
            for (SqlColumn column : child.valueColumns()) {
                strings(column, OWNER_ALIAS).ifPresent(values::add);
            }
            if (!values.isEmpty()) {
                parts.add("(SELECT string_agg(concat_ws(" + SEPARATOR + ", " + String.join(", ", values) + "), "
                        + SEPARATOR + ") FROM " + SqlNaming.quote(child.name()) + " " + OWNER_ALIAS + " WHERE "
                        + OWNER_ALIAS + "." + SqlNaming.quote(SqlChildTable.OWNER) + " = " + owner + ")");
            }
        }
        return "concat_ws(" + SEPARATOR + ", " + String.join(", ", parts) + ")";
    }

    /** The strings one column contributes, when it holds any. */
    private static Optional<String> strings(SqlColumn column, String alias) {
        String ref = alias + "." + SqlNaming.quote(column.name());
        return switch (column.kind()) {
            case ID, SCALAR -> SqlTypes.TEXT.equals(column.sqlType())
                    ? Optional.of(ref) : Optional.empty();
            case JSONB, IKEY -> Optional.of("(SELECT string_agg(j.v #>> '{}', " + SEPARATOR
                    + ") FROM jsonb_path_query(" + ref + ", 'strict $.**') AS j(v) WHERE jsonb_typeof(j.v) = 'string')");
            case GEOMETRY -> Optional.of("substr(ST_GeometryType(" + ref + "), " + GEOMETRY_TYPE_PREFIX + ")");
            case COMPOSITION, PRESENCE -> Optional.empty();
        };
    }
}
