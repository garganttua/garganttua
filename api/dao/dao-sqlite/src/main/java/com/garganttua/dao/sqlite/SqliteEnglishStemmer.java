package com.garganttua.dao.sqlite;

import java.util.Map;
import java.util.Set;

/**
 * The Snowball English stemmer (Porter2) — the stemmer of MongoDB's {@code english} text index and of PostgreSQL's
 * {@code english} text search configuration — so that a {@code $text} search on SQLite reduces words to the same
 * stems.
 *
 * <p>
 * It follows the published algorithm step by step: the exceptional forms, the regions R1 and R2, then steps 0 to 5.
 * A word that is not lower-case ASCII letters (with apostrophes) is returned as is, as Snowball's English stemmer
 * leaves alone what its alphabet does not cover.
 * </p>
 */
final class SqliteEnglishStemmer {

    private static final int SHORTEST_STEMMED = 3;

    private static final Map<String, String> EXCEPTIONS = Map.ofEntries(Map.entry("skis", "ski"),
            Map.entry("skies", "sky"), Map.entry("dying", "die"), Map.entry("lying", "lie"),
            Map.entry("tying", "tie"), Map.entry("idly", "idl"), Map.entry("gently", "gentl"),
            Map.entry("ugly", "ugli"), Map.entry("early", "earli"), Map.entry("only", "onli"),
            Map.entry("singly", "singl"), Map.entry("sky", "sky"), Map.entry("news", "news"),
            Map.entry("howe", "howe"), Map.entry("atlas", "atlas"), Map.entry("cosmos", "cosmos"),
            Map.entry("bias", "bias"), Map.entry("andes", "andes"));

    private static final Set<String> AFTER_STEP_1A = Set.of("inning", "outing", "canning", "herring", "earring",
            "proceed", "exceed", "succeed");

    private static final String[][] STEP_2 = { { "ization", "ize" }, { "ational", "ate" }, { "fulness", "ful" },
            { "ousness", "ous" }, { "iveness", "ive" }, { "tional", "tion" }, { "biliti", "ble" }, { "lessli", "less" },
            { "entli", "ent" }, { "ation", "ate" }, { "alism", "al" }, { "aliti", "al" }, { "ousli", "ous" },
            { "iviti", "ive" }, { "fulli", "ful" }, { "enci", "ence" }, { "anci", "ance" }, { "abli", "able" },
            { "izer", "ize" }, { "ator", "ate" }, { "alli", "al" }, { "bli", "ble" }, { "ogi", "og" }, { "li", "" } };

    private static final String[][] STEP_3 = { { "ational", "ate" }, { "tional", "tion" }, { "alize", "al" },
            { "icate", "ic" }, { "iciti", "ic" }, { "ative", "" }, { "ical", "ic" }, { "ness", "" }, { "ful", "" } };

    private static final String[] STEP_4 = { "ement", "ance", "ence", "able", "ible", "ment", "ant", "ent", "ism",
            "ate", "iti", "ous", "ive", "ize", "ion", "al", "er", "ic" };

    private static final String[] PREFIXES = { "gener", "commun", "arsen" };

    private SqliteEnglishStemmer() {
    }

    /**
     * The stem of a lower-case word.
     *
     * @param word the word
     * @return its stem
     */
    static String stem(String word) {
        if (word.length() <= 2 || !word.chars().allMatch(c -> (c >= 'a' && c <= 'z') || c == '\'')) {
            return word;
        }
        String w = word.startsWith("'") ? word.substring(1) : word;
        String exception = EXCEPTIONS.get(w);
        if (exception != null) {
            return exception;
        }
        if (w.length() <= 2) {
            return w;
        }
        StringBuilder b = new StringBuilder(w);
        markY(b);
        int r1 = r1(b);
        int r2 = region(b, r1);
        step0(b);
        step1a(b);
        if (AFTER_STEP_1A.contains(b.toString())) {
            return b.toString();
        }
        step1b(b, r1);
        step1c(b);
        step2(b, r1);
        step3(b, r1, r2);
        step4(b, r2);
        step5(b, r1, r2);
        return b.toString().replace('Y', 'y');
    }

    private static boolean vowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    private static void markY(StringBuilder b) {
        if (b.charAt(0) == 'y') {
            b.setCharAt(0, 'Y');
        }
        for (int i = 1; i < b.length(); i++) {
            if (b.charAt(i) == 'y' && vowel(b.charAt(i - 1))) {
                b.setCharAt(i, 'Y');
            }
        }
    }

    private static int r1(StringBuilder b) {
        String s = b.toString();
        for (String prefix : PREFIXES) {
            if (s.startsWith(prefix)) {
                return prefix.length();
            }
        }
        return region(b, 0);
    }

    /** The start of the region after the first non-vowel following a vowel, from {@code from}. */
    private static int region(StringBuilder b, int from) {
        for (int i = from + 1; i < b.length(); i++) {
            if (!vowel(b.charAt(i)) && vowel(b.charAt(i - 1))) {
                return i + 1;
            }
        }
        return b.length();
    }

    private static boolean ends(StringBuilder b, String suffix) {
        int n = b.length() - suffix.length();
        return n >= 0 && b.indexOf(suffix, n) == n;
    }

    private static void replace(StringBuilder b, String suffix, String by) {
        b.setLength(b.length() - suffix.length());
        b.append(by);
    }

    private static boolean hasVowel(StringBuilder b, int end) {
        for (int i = 0; i < end; i++) {
            if (vowel(b.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void step0(StringBuilder b) {
        for (String s : new String[] { "'s'", "'s", "'" }) {
            if (ends(b, s)) {
                replace(b, s, "");
                return;
            }
        }
    }

    private static void step1a(StringBuilder b) {
        if (ends(b, "sses")) {
            replace(b, "sses", "ss");
        } else if (ends(b, "ied") || ends(b, "ies")) {
            replace(b, "ies", b.length() > 4 ? "i" : "ie");
        } else if (ends(b, "us") || ends(b, "ss")) {
            return;
        } else if (ends(b, "s") && hasVowel(b, b.length() - 2)) {
            replace(b, "s", "");
        }
    }

    private static void step1b(StringBuilder b, int r1) {
        for (String s : new String[] { "eedly", "eed" }) {
            if (ends(b, s)) {
                if (b.length() - s.length() >= r1) {
                    replace(b, s, "ee");
                }
                return;
            }
        }
        for (String s : new String[] { "ingly", "edly", "ing", "ed" }) {
            if (ends(b, s)) {
                int stem = b.length() - s.length();
                if (hasVowel(b, stem)) {
                    replace(b, s, "");
                    afterStep1b(b, r1);
                }
                return;
            }
        }
    }

    private static void afterStep1b(StringBuilder b, int r1) {
        if (ends(b, "at") || ends(b, "bl") || ends(b, "iz")) {
            b.append('e');
        } else if (endsDouble(b)) {
            b.setLength(b.length() - 1);
        } else if (isShort(b, r1)) {
            b.append('e');
        }
    }

    private static boolean endsDouble(StringBuilder b) {
        int n = b.length();
        if (n < 2 || b.charAt(n - 1) != b.charAt(n - 2)) {
            return false;
        }
        return "bdfgmnprt".indexOf(b.charAt(n - 1)) >= 0;
    }

    private static boolean isShort(StringBuilder b, int r1) {
        return r1 >= b.length() && endsShortSyllable(b);
    }

    private static boolean endsShortSyllable(StringBuilder b) {
        int n = b.length();
        if (n == 2) {
            return vowel(b.charAt(0)) && !vowel(b.charAt(1));
        }
        if (n < SHORTEST_STEMMED) {
            return false;
        }
        char last = b.charAt(n - 1);
        return !vowel(b.charAt(n - SHORTEST_STEMMED)) && vowel(b.charAt(n - 2)) && !vowel(last) && last != 'w'
                && last != 'x' && last != 'Y';
    }

    private static void step1c(StringBuilder b) {
        int n = b.length();
        if (n > 2 && (b.charAt(n - 1) == 'y' || b.charAt(n - 1) == 'Y') && !vowel(b.charAt(n - 2))) {
            b.setCharAt(n - 1, 'i');
        }
    }

    private static void step2(StringBuilder b, int r1) {
        for (String[] rule : STEP_2) {
            if (ends(b, rule[0])) {
                int stem = b.length() - rule[0].length();
                if (stem < r1) {
                    return;
                }
                if ("ogi".equals(rule[0]) && (stem == 0 || b.charAt(stem - 1) != 'l')) {
                    return;
                }
                if ("li".equals(rule[0]) && (stem == 0 || "cdeghkmnrt".indexOf(b.charAt(stem - 1)) < 0)) {
                    return;
                }
                replace(b, rule[0], rule[1]);
                return;
            }
        }
    }

    private static void step3(StringBuilder b, int r1, int r2) {
        for (String[] rule : STEP_3) {
            if (ends(b, rule[0])) {
                int stem = b.length() - rule[0].length();
                if (stem >= r1 && (!"ative".equals(rule[0]) || stem >= r2)) {
                    replace(b, rule[0], rule[1]);
                }
                return;
            }
        }
    }

    private static void step4(StringBuilder b, int r2) {
        for (String suffix : STEP_4) {
            if (ends(b, suffix)) {
                int stem = b.length() - suffix.length();
                if (stem < r2) {
                    return;
                }
                if ("ion".equals(suffix) && (stem == 0 || (b.charAt(stem - 1) != 's' && b.charAt(stem - 1) != 't'))) {
                    return;
                }
                replace(b, suffix, "");
                return;
            }
        }
    }

    private static void step5(StringBuilder b, int r1, int r2) {
        int n = b.length();
        if (n == 0) {
            return;
        }
        if (b.charAt(n - 1) == 'e') {
            int stem = n - 1;
            if (stem >= r2 || (stem >= r1 && !endsShortSyllable(new StringBuilder(b.substring(0, stem))))) {
                b.setLength(stem);
            }
        } else if (b.charAt(n - 1) == 'l' && n - 1 >= r2 && n >= 2 && b.charAt(n - 2) == 'l') {
            b.setLength(n - 1);
        }
    }
}
