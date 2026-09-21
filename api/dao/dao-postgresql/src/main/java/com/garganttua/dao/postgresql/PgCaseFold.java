package com.garganttua.dao.postgresql;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * The characters a case-insensitive regular expression treats as the same letter, computed from
 * Java's Unicode tables rather than from the database.
 *
 * <p>
 * PostgreSQL folds case for {@code ~*} or {@code (?i)} through the database's {@code LC_CTYPE}: with
 * the common {@code C} locale, {@code É} and {@code é} are two unrelated characters, and the answer
 * of a query would depend on how the server was initialised. MongoDB's PCRE2, in UTF mode, folds
 * every Unicode letter. {@link PgPcreTranslator} therefore never asks PostgreSQL to fold: it rewrites
 * each letter into the bracket of its case variants ({@code é} becomes {@code [éÉ]}), taken from
 * here, and the match no longer depends on any locale.
 * </p>
 *
 * <p>
 * Two letters are variants when upper-casing then lower-casing them gives the same character — the
 * simple case folding PCRE2 applies: it groups {@code k}, {@code K} and the Kelvin sign, or
 * {@code σ}, {@code ς} and {@code Σ}. The Turkish dotted and dotless i are left out, as PCRE2 leaves
 * them out outside its Turkish mode.
 * </p>
 */
final class PgCaseFold {

    private static final int[] NONE = new int[0];
    private static final int DOTTED_CAPITAL_I = 0x130;
    private static final int DOTLESS_SMALL_I = 0x131;

    private static volatile Map<Integer, int[]> variants;

    private PgCaseFold() {
    }

    /**
     * The case variants of a character.
     *
     * @param cp the code point
     * @return every code point matching it case-insensitively, itself included, in ascending order;
     *         an empty array when it has no other case
     */
    static int[] variants(int cp) {
        return table().getOrDefault(cp, NONE);
    }

    private static Map<Integer, int[]> table() {
        Map<Integer, int[]> built = variants;
        if (built == null) {
            synchronized (PgCaseFold.class) {
                built = variants;
                if (built == null) {
                    built = build();
                    variants = built;
                }
            }
        }
        return built;
    }

    private static Map<Integer, int[]> build() {
        Map<Integer, TreeSet<Integer>> groups = new HashMap<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp == DOTTED_CAPITAL_I || cp == DOTLESS_SMALL_I || Character.getType(cp) == Character.SURROGATE) {
                continue;
            }
            int key = Character.toLowerCase(Character.toUpperCase(cp));
            if (key != cp || Character.toUpperCase(cp) != cp) {
                groups.computeIfAbsent(key, k -> new TreeSet<>()).add(cp);
                groups.get(key).add(key);
            }
        }
        Map<Integer, int[]> table = new HashMap<>();
        for (TreeSet<Integer> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            int[] members = group.stream().mapToInt(Integer::intValue).toArray();
            for (int member : members) {
                table.put(member, Arrays.copyOf(members, members.length));
            }
        }
        return Map.copyOf(table);
    }
}
