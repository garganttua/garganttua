package com.garganttua.dao.sqlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlQuery;
import com.garganttua.dao.sql.SqlTextQuery;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * {@code $text} on SQLite: the document's words, folded, stemmed by the Snowball English stemmer with the English
 * stop words removed, matched in Java by {@link #FUNCTION} — the semantics the PostgreSQL DAO gives {@code $text}
 * with its {@code english} configuration.
 *
 * <p>
 * The search matches when one of its positive terms (all of a term's words) is among the document's stems and no
 * negated term is; each quoted phrase must appear, case-insensitively, in the folded document, and no negated phrase
 * may. Without a declared text index the document is every string the entity holds — its text columns, the strings
 * of its JSON columns, the type of its geometries, the strings of its collections — as on PostgreSQL; with one, it is
 * the indexed field.
 * </p>
 */
final class SqliteTextSearch {

    /** The function registered on every connection: {@code gg_text(document, search)} is 1 when it matches. */
    static final String FUNCTION = "gg_text";

    private static final String SEPARATOR = "char(1)";
    private static final String OWNER_ALIAS = "tx";
    private static final int CACHED_SEARCHES = 256;

    /** PostgreSQL's {@code english.stop}: the words its English configuration ignores. */
    private static final Set<String> STOP_WORDS = Set.of("i", "me", "my", "myself", "we", "our", "ours",
            "ourselves", "you", "your", "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she", "her",
            "hers", "herself", "it", "its", "itself", "they", "them", "their", "theirs", "themselves", "what", "which",
            "who", "whom", "this", "that", "these", "those", "am", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "doing", "a", "an", "the", "and", "but", "if", "or",
            "because", "as", "until", "while", "of", "at", "by", "for", "with", "about", "against", "between", "into",
            "through", "during", "before", "after", "above", "below", "to", "from", "up", "down", "in", "out", "on",
            "off", "over", "under", "again", "further", "then", "once", "here", "there", "when", "where", "why", "how",
            "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not", "only",
            "own", "same", "so", "than", "too", "very", "s", "t", "can", "will", "just", "don", "should", "now");

    /** A parsed search: each term as the stems all of which must be present. */
    private record Search(List<List<String>> positive, List<List<String>> negated, List<String> phrases,
            List<String> negatedPhrases) {
    }

    private static final Map<String, Search> SEARCHES = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHED_SEARCHES, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Search> eldest) {
                    return size() > CACHED_SEARCHES;
                }
            });

    private SqliteTextSearch() {
    }

    /** {@code $text} over every string of the entity. */
    static SqlFragment predicate(SqlTable table, Object search) {
        return call(document(table), search);
    }

    /** {@code $text} over the field of the declared text index. */
    static SqlFragment predicateOnIndexed(SqlColumn column, Object search) {
        return call(SqlQuery.ALIAS + "." + SqlNaming.quote(column.name()), search);
    }

    private static SqlFragment call(String document, Object search) {
        if (search == null) {
            throw new ApiException("$text filter requires a search string");
        }
        if (SqlTextQuery.parse(search.toString()).positive().isEmpty()) {
            return SqlFragment.FALSE;
        }
        return SqlFragment.of("(" + FUNCTION + "(" + document + ", ?) = 1)", search.toString());
    }

    /**
     * Whether {@code document} matches {@code search}.
     *
     * @param document the text searched, or null
     * @param search   the {@code $text} search string
     * @return whether it matches
     */
    static boolean matches(String document, String search) {
        if (document == null) {
            return false;
        }
        Search s = SEARCHES.computeIfAbsent(search, SqliteTextSearch::parse);
        String folded = SqlTextQuery.fold(document);
        Set<String> stems = stems(folded);
        boolean any = s.positive().stream().anyMatch(stems::containsAll);
        if (!any || s.negated().stream().anyMatch(stems::containsAll)) {
            return false;
        }
        String lower = folded.toLowerCase(Locale.ROOT);
        return s.phrases().stream().allMatch(p -> lower.contains(p.toLowerCase(Locale.ROOT)))
                && s.negatedPhrases().stream().noneMatch(p -> lower.contains(p.toLowerCase(Locale.ROOT)));
    }

    private static Search parse(String search) {
        SqlTextQuery query = SqlTextQuery.parse(search);
        return new Search(terms(query.positive()), terms(query.negated()), query.phrases(), query.negatedPhrases());
    }

    /** Each term as its stems; a term made only of stop words matches nothing and is dropped, as on PostgreSQL. */
    private static List<List<String>> terms(List<String> terms) {
        List<List<String>> out = new ArrayList<>();
        for (String term : terms) {
            List<String> stems = new ArrayList<>(stems(term));
            if (!stems.isEmpty()) {
                out.add(stems);
            }
        }
        return out;
    }

    /** The stems of a folded text: delimiters split it, stop words are dropped, the rest is stemmed. */
    static Set<String> stems(String folded) {
        StringBuilder spaced = new StringBuilder(folded.length());
        folded.codePoints().forEach(cp -> spaced.appendCodePoint(
                SqlTextQuery.DELIMITERS.indexOf(cp) >= 0 ? ' ' : cp));
        Set<String> stems = new HashSet<>();
        for (String word : spaced.toString().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && !STOP_WORDS.contains(word)) {
                stems.add(SqliteEnglishStemmer.stem(word));
            }
        }
        return stems;
    }

    /** Every string of the entity, separated so that no phrase spans two of them. */
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
                parts.add("(SELECT group_concat(concat_ws(" + SEPARATOR + ", " + String.join(", ", values) + "), "
                        + SEPARATOR + ") FROM " + SqlNaming.quote(child.name()) + " " + OWNER_ALIAS + " WHERE "
                        + OWNER_ALIAS + "." + SqlNaming.quote(SqlChildTable.OWNER) + " = " + owner + ")");
            }
        }
        return parts.isEmpty() ? "NULL" : "concat_ws(" + SEPARATOR + ", " + String.join(", ", parts) + ")";
    }

    private static Optional<String> strings(SqlColumn column, String alias) {
        String ref = alias + "." + SqlNaming.quote(column.name());
        return switch (column.kind()) {
            case ID, SCALAR -> SqlTypes.TEXT.equals(column.sqlType()) ? Optional.of(ref) : Optional.empty();
            case JSONB, IKEY -> Optional.of("(SELECT group_concat(j.value, " + SEPARATOR + ") FROM json_tree(" + ref
                    + ") AS j WHERE j.type = 'text')");
            case GEOMETRY -> Optional.of("json_extract(" + ref + ", '$.type')");
            case COMPOSITION, PRESENCE -> Optional.empty();
        };
    }
}
