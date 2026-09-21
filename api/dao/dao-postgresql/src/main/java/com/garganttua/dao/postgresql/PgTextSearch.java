package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * MongoDB's {@code $text} over a wildcard ({@code "$**"}) text index, reproduced without any index.
 *
 * <p>
 * The applications index every string of a document for text search, so {@code $text} looks at
 * EVERY string value of the entity — whatever field the filter names. The relational model
 * scatters those strings, so the searched document is re-assembled per row: the main table's TEXT
 * columns (the uuid and enums included), the strings inside its JSONB documents, the type of its
 * geometries (a GeoJSON {@code "type"} is a string to MongoDB), and, from every child table, the
 * TEXT values of scalar collections, POJO elements and map values. Map KEYS are left out: MongoDB
 * stores them as field names, which a text index does not see. Values are joined with
 * {@code chr(1)}, so a phrase can never straddle two values — MongoDB matches a phrase inside one
 * field.
 * </p>
 *
 * <p>
 * The search string is decomposed by {@link PgTextQuery}; words go through the {@code english}
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
     * Refuses what MongoDB refuses: two {@code $text}, a {@code $text} under {@code $nor} or under
     * {@code $or}.
     *
     * @param filter the whole filter, possibly null
     * @throws ApiException when the filter breaks one of these rules
     */
    static void validate(IFilter filter) {
        if (count(filter, null) > 1) {
            throw new ApiException("A filter may hold only one $text: MongoDB refuses a second one"
                    + " ('Too many text expressions'); put every word in the same search string");
        }
    }

    private static int count(IFilter filter, String under) {
        if (filter == null || filter.getName() == null) {
            return 0;
        }
        if ("$field".equals(filter.getName())) {
            boolean text = filter.getFilters() != null && filter.getFilters().size() == 1
                    && filter.getFilters().get(0) != null && "$text".equals(filter.getFilters().get(0).getName());
            if (text && under != null) {
                throw new ApiException("$text cannot be placed under " + under + ": " + ("$nor".equals(under)
                        ? "MongoDB refuses it ('text expression not allowed in nor')"
                        : "MongoDB runs it only when every other branch of the $or has its own index, and refuses"
                                + " the query otherwise; search with $text at the top level, or inside $and"));
            }
            return text ? 1 : 0;
        }
        String next = "$nor".equals(filter.getName()) || "$or".equals(filter.getName()) ? filter.getName() : under;
        int texts = 0;
        for (IFilter sub : filter.getFilters() == null ? List.<IFilter>of() : filter.getFilters()) {
            texts += count(sub, "$nor".equals(under) ? under : next);
        }
        return texts;
    }

    /**
     * The predicate of a {@code $text} search on the main row {@code t}.
     *
     * @param table  the domain's model
     * @param search the search string
     * @return the predicate — never NULL
     * @throws ApiException when there is no search string
     */
    static PgSql predicate(PgTable table, Object search) {
        if (search == null) {
            throw new ApiException("$text filter requires a search string");
        }
        PgTextQuery query = PgTextQuery.parse(search.toString());
        if (query.positive().isEmpty()) {
            return PgSql.FALSE;
        }
        List<PgSql> conditions = new ArrayList<>();
        conditions.add(PgSql.of("to_tsvector(" + CONFIG + ", translate(s.d, ?, ?)) @@ ",
                PgTextQuery.DELIMITERS, PgTextQuery.DELIMITER_SPACES).then(tsquery(query)));
        for (String phrase : query.phrases()) {
            conditions.add(PgSql.of("strpos(lower(s.d), lower(?)) > 0", phrase));
        }
        for (String phrase : query.negatedPhrases()) {
            conditions.add(PgSql.of("strpos(lower(s.d), lower(?)) = 0", phrase));
        }
        return PgSql.of("EXISTS (SELECT 1 FROM (SELECT translate(" + document(table) + ", ?, ?) AS d) s WHERE ",
                PgTextQuery.FOLD_FROM, PgTextQuery.FOLD_TO).then(PgSql.join(" AND ", conditions)).then(")");
    }

    /** ANY positive term, and none of the negated ones; a stop word alone is empty and ignored. */
    private static PgSql tsquery(PgTextQuery query) {
        List<PgSql> any = new ArrayList<>();
        for (String term : query.positive()) {
            any.add(PgSql.of("plainto_tsquery(" + CONFIG + ", ?)", term));
        }
        PgSql result = PgSql.join(" || ", any).wrap("(", ")");
        for (String term : query.negated()) {
            result = result.then(PgSql.of(" && !!plainto_tsquery(" + CONFIG + ", ?)", term));
        }
        return result.wrap("(", ")");
    }

    /** Every string of the entity, as one SQL text expression over the main row. */
    private static String document(PgTable table) {
        List<String> parts = new ArrayList<>();
        for (PgColumn column : table.columns()) {
            strings(column, PgQuery.ALIAS).ifPresent(parts::add);
        }
        String owner = PgQuery.ALIAS + "." + PgNaming.quote(table.id().name());
        for (PgChildTable child : table.children()) {
            List<String> values = new ArrayList<>();
            for (PgColumn column : child.valueColumns()) {
                strings(column, OWNER_ALIAS).ifPresent(values::add);
            }
            if (!values.isEmpty()) {
                parts.add("(SELECT string_agg(concat_ws(" + SEPARATOR + ", " + String.join(", ", values) + "), "
                        + SEPARATOR + ") FROM " + PgNaming.quote(child.name()) + " " + OWNER_ALIAS + " WHERE "
                        + OWNER_ALIAS + "." + PgNaming.quote(PgChildTable.OWNER) + " = " + owner + ")");
            }
        }
        return "concat_ws(" + SEPARATOR + ", " + String.join(", ", parts) + ")";
    }

    /** The strings one column contributes, when it holds any. */
    private static Optional<String> strings(PgColumn column, String alias) {
        String ref = alias + "." + PgNaming.quote(column.name());
        return switch (column.kind()) {
            case ID, SCALAR -> PgTypes.TEXT.equals(column.sqlType())
                    ? Optional.of(ref) : Optional.empty();
            case JSONB, IKEY -> Optional.of("(SELECT string_agg(j.v #>> '{}', " + SEPARATOR
                    + ") FROM jsonb_path_query(" + ref + ", 'strict $.**') AS j(v) WHERE jsonb_typeof(j.v) = 'string')");
            case GEOMETRY -> Optional.of("substr(ST_GeometryType(" + ref + "), " + GEOMETRY_TYPE_PREFIX + ")");
            case COMPOSITION, PRESENCE -> Optional.empty();
        };
    }
}
