package com.garganttua.dao.sql;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;

/**
 * A {@code $text} search string, read the way MongoDB reads it, and the character folding both the
 * search and the searched text go through.
 *
 * <p>
 * MongoDB does not hand the string to a query parser: it splits it into TERMS (runs of characters
 * between whitespace and delimiters), {@code "quoted phrases"} and negations — a {@code -} right
 * after whitespace negates the following term or phrase, up to the next whitespace; a {@code -}
 * inside a word only separates. Words inside a phrase are ALSO positive terms. A document then
 * matches when it contains ANY positive term (after stemming), EVERY positive phrase, no negated
 * term and no negated phrase. This record holds that decomposition; {@link PgTextSearch} turns it
 * into SQL.
 * </p>
 *
 * <p>
 * <b>Folding.</b> MongoDB's text index (version 3) ignores case and diacritics. Case is left to
 * PostgreSQL's {@code lower()} and text-search parser; diacritics are removed with
 * {@code translate()} — no extension, no DDL — using {@link #FOLD_FROM} / {@link #FOLD_TO}, derived
 * from the Unicode decompositions of the Latin, Greek and Latin-extended letters (a letter maps to
 * its base letter; a lone combining mark is deleted). The same mapping is applied here, in Java, to
 * the search string, so both sides are folded identically. {@link #DELIMITERS} are the characters
 * MongoDB separates words on (ASCII punctuation except {@code _}, general punctuation, guillemets,
 * no-break space); they become spaces before the text-search parser sees the document.
 * </p>
 *
 * @param positive        the terms of which ANY must occur (folded)
 * @param negated         the terms none of which may occur (folded)
 * @param phrases         the phrases that must ALL occur verbatim, modulo case and diacritics (folded)
 * @param negatedPhrases  the phrases none of which may occur (folded)
 */
public record SqlTextQuery(List<String> positive, List<String> negated, List<String> phrases, List<String> negatedPhrases) {

    /** The diacritic folding's source characters, then the combining marks it deletes. */
    public static final String FOLD_FROM;
    /** The base letters, position by position with {@link #FOLD_FROM}. */
    public static final String FOLD_TO;
    /** The word delimiters, turned into spaces. */
    public static final String DELIMITERS;
    /** As many spaces as {@link #DELIMITERS} has characters. */
    public static final String DELIMITER_SPACES;

    private static final int COMBINING_FIRST = 0x300;
    private static final int COMBINING_LAST = 0x36F;
    private static final int[][] FOLDED_RANGES = { { 0xC0, 0x24F }, { 0x386, 0x3CE }, { 0x1E00, 0x1EFF } };
    private static final int[][] DELIMITER_RANGES = { { 0x01, 0x2F }, { 0x3A, 0x40 }, { 0x5B, 0x5E }, { 0x60, 0x60 },
            { 0x7B, 0x7F }, { 0xA0, 0xA1 }, { 0xAB, 0xAB }, { 0xBB, 0xBB }, { 0xBF, 0xBF }, { 0x2000, 0x206F },
            { 0x3000, 0x3003 } };

    static {
        StringBuilder from = new StringBuilder();
        StringBuilder to = new StringBuilder();
        for (int[] range : FOLDED_RANGES) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                String base = Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "");
                if (base.length() == 1 && base.charAt(0) != cp) {
                    from.appendCodePoint(cp);
                    to.append(base);
                }
            }
        }
        for (int cp = COMBINING_FIRST; cp <= COMBINING_LAST; cp++) {
            from.appendCodePoint(cp);
        }
        FOLD_FROM = from.toString();
        FOLD_TO = to.toString();
        StringBuilder delimiters = new StringBuilder();
        for (int[] range : DELIMITER_RANGES) {
            for (int cp = range[0]; cp <= range[1]; cp++) {
                delimiters.appendCodePoint(cp);
            }
        }
        DELIMITERS = delimiters.toString();
        DELIMITER_SPACES = " ".repeat(DELIMITERS.length());
    }

    public SqlTextQuery {
        positive = List.copyOf(positive);
        negated = List.copyOf(negated);
        phrases = List.copyOf(phrases);
        negatedPhrases = List.copyOf(negatedPhrases);
    }

    /**
     * Reads a search string.
     *
     * @param search the {@code $text} value
     * @return its terms and phrases, folded
     */
    public static SqlTextQuery parse(String search) {
        return new Parser(search).run();
    }

    /** {@return the text with diacritics removed, exactly as the SQL {@code translate()} does} */
    public static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int at = FOLD_FROM.indexOf(cp);
            if (at < 0) {
                out.appendCodePoint(cp);
            } else if (at < FOLD_TO.length()) {
                out.append(FOLD_TO.charAt(at));
            }
        });
        return out.toString();
    }

    private static boolean isDelimiter(int cp) {
        return DELIMITERS.indexOf(cp) >= 0 && !Character.isWhitespace(cp);
    }

    /** MongoDB's search-string reading: one pass, a phrase and a negation state. */
    @SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // quote: the open quote's position, set by quote(at)
    private static final class Parser {

        private final String search;
        private final List<String> positive = new ArrayList<>();
        private final List<String> negated = new ArrayList<>();
        private final List<String> phrases = new ArrayList<>();
        private final List<String> negatedPhrases = new ArrayList<>();
        @SuppressWarnings("PMD.AvoidStringBufferField") // one parser per search string, discarded after run()
        private final StringBuilder term = new StringBuilder();
        private boolean inPhrase;
        private boolean inNegation;
        private boolean afterWhitespace = true;
        private int quote;

        Parser(String search) {
            this.search = search;
        }

        SqlTextQuery run() {
            int i = 0;
            while (i < search.length()) {
                int cp = search.codePointAt(i);
                read(cp, i);
                i += Character.charCount(cp);
            }
            flush();
            return new SqlTextQuery(positive, negated, phrases, negatedPhrases);
        }

        @SuppressWarnings("PMD.AvoidLiteralsInIfCondition") // '"' is the phrase delimiter of the search grammar
        private void read(int cp, int at) {
            if (cp == '"') {
                flush();
                quote(at);
                afterWhitespace = false;
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                flush();
                afterWhitespace = true;
                inNegation = inNegation && inPhrase;
            } else if (isDelimiter(cp)) {
                flush();
                inNegation = inNegation || cp == '-' && !inPhrase && afterWhitespace;
                afterWhitespace = false;
            } else {
                term.appendCodePoint(cp);
                afterWhitespace = false;
            }
        }

        private void quote(int at) {
            if (inPhrase) {
                String phrase = fold(search.substring(quote + 1, at)).replace("\u0001", "");
                (inNegation ? negatedPhrases : phrases).add(phrase);
            } else {
                quote = at;
            }
            inPhrase = !inPhrase;
        }

        private void flush() {
            if (term.isEmpty()) {
                return;
            }
            if (!(inPhrase && inNegation)) {
                (inNegation ? negated : positive).add(fold(term.toString()));
            }
            term.setLength(0);
        }
    }

    private static final String FIELD_OPERATOR = "$field";

    /**
     * Refuses what MongoDB refuses: two {@code $text}, a {@code $text} under {@code $nor} or under
     * {@code $or}.
     *
     * @param filter the whole filter, possibly null
     * @throws ApiException when the filter breaks one of these rules
     */
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition") // 1: the most $text expressions a filter may hold
    public static void validate(IFilter filter) {
        if (count(filter, null) > 1) {
            throw new ApiException("A filter may hold only one $text: MongoDB refuses a second one"
                    + " ('Too many text expressions'); put every word in the same search string");
        }
    }

    private static int count(IFilter filter, String under) {
        if (filter == null || filter.getName() == null) {
            return 0;
        }
        if (FIELD_OPERATOR.equals(filter.getName())) {
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

}
