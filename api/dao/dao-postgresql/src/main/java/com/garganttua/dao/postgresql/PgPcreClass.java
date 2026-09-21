package com.garganttua.dao.postgresql;

import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * A PCRE character class, held as an explicit set of code points and written back as a PostgreSQL
 * bracket expression.
 *
 * <p>
 * PostgreSQL's {@code \w}, {@code [[:alpha:]]} and case-insensitive brackets follow the database's
 * {@code LC_CTYPE}; PCRE's (without {@code UCP}, as MongoDB runs it) are ASCII, with case folding
 * over every Unicode letter. Expanding every class into plain code-point ranges removes the
 * database's locale from the answer: {@code \w} becomes {@code [0-9A-Z_a-z]} everywhere, and
 * {@code [é]} under {@code (?i)} becomes {@code [Éé]}. Negated escapes inside a bracket
 * ({@code [\D]}) are complements of an explicit set, so they are exact too.
 * </p>
 *
 * <p>
 * Two sets are kept: {@code folded} members get their case variants under {@code (?i)}, and
 * {@code fixed} members — the {@code \d \w \s} families and POSIX classes — do not, as in PCRE.
 * </p>
 */
// A PCRE reader: the character literals it tests ('\\', '{', ')', 'Q', ...) ARE the grammar being parsed,
// and its small integers are bounds (group 1, range lengths); naming each would only hide the syntax.
// Its constants share a name with the method that uses them (accessor style).
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidLiteralsInIfCondition"})
final class PgPcreClass {

    private static final int SURROGATE_FIRST = 0xD800;
    private static final int SURROGATE_END = 0xE000;
    private static final int ALL = Character.MAX_CODE_POINT + 1;
    private static final int PRINTABLE_FIRST = 0x20;
    private static final int PRINTABLE_LAST = 0x7E;
    private static final int BMP_END = 0x10000;

    private static final Map<String, String> POSIX = Map.ofEntries(
            Map.entry("alpha", "A-Za-z"), Map.entry("digit", "0-9"), Map.entry("alnum", "0-9A-Za-z"),
            Map.entry("upper", "A-Z"), Map.entry("lower", "a-z"), Map.entry("xdigit", "0-9A-Fa-f"),
            Map.entry("space", "\t-\r "), Map.entry("blank", "\t "), Map.entry("word", "0-9A-Za-z_"),
            Map.entry("cntrl", "\u0000-\u001F\u007F"), Map.entry("print", " -~"), Map.entry("graph", "!-~"),
            Map.entry("punct", "!-/:-@[-`{-~"), Map.entry("ascii", "\u0000-\u007F"));

    private static final Map<Integer, String> ESCAPES = Map.of(
            (int) 'd', "0-9", (int) 'w', "0-9A-Za-z_", (int) 's', "\t-\r ",
            (int) 'h', "\t \u00A0\u1680\u180E\u2000-\u200A\u202F\u205F\u3000",
            (int) 'v', "\n-\r\u0085\u2028\u2029");

    private final BitSet folded = new BitSet();
    private final BitSet fixed = new BitSet();
    private boolean negated;

    /**
     * The class of a PCRE class escape ({@code \d}, {@code \W}, {@code \h}, {@code \N}…).
     *
     * @param letter the escape letter
     * @return the class, or null when the letter is not a class escape
     */
    static PgPcreClass escape(int letter) {
        if (letter == 'N') {
            PgPcreClass nonNewline = new PgPcreClass();
            nonNewline.negated = true;
            nonNewline.folded.set('\n');
            return nonNewline;
        }
        String ranges = ESCAPES.get(Character.toLowerCase(letter));
        if (ranges == null) {
            return null;
        }
        PgPcreClass escape = new PgPcreClass();
        escape.fixed.or(ranges(ranges, Character.isUpperCase(letter)));
        return escape;
    }

    /**
     * Parses a bracket expression; the cursor stands just after its {@code [}.
     *
     * @param cursor the pattern
     * @return the class
     */
    static PgPcreClass parse(PgPcreCursor cursor) {
        PgPcreClass parsed = new PgPcreClass();
        parsed.negated = cursor.eat("^");
        boolean first = true;
        while (true) {
            if (cursor.atEnd()) {
                throw cursor.invalid("a character class is not closed by ']'");
            }
            int c = cursor.next();
            if (c == ']' && !first) {
                return parsed;
            }
            first = false;
            parsed.member(cursor, c);
        }
    }

    private void member(PgPcreCursor cursor, int c) {
        if (c == '[' && cursor.peek(0) == ':') {
            posix(cursor);
            return;
        }
        int low = c;
        if (c == '\\') {
            int e = cursor.next();
            PgPcreClass escaped = e == 'b' || e == 'N' ? null : escape(e);
            if (escaped != null) {
                fixed.or(escaped.members());
                return;
            }
            if (e == 'E') {
                return;
            }
            if (e == 'Q') {
                quoted(cursor);
                return;
            }
            refuseProperty(cursor, e);
            low = e == 'b' ? '\b' : cursor.character(e);
        }
        if (cursor.peek(0) == '-' && cursor.peek(1) != ']' && cursor.peek(1) != -1) {
            cursor.next();
            range(cursor, low);
            return;
        }
        folded.set(low);
    }

    private void range(PgPcreCursor cursor, int low) {
        int high = cursor.next();
        if (high == '\\') {
            int e = cursor.next();
            refuseProperty(cursor, e);
            if (escape(e) != null) {
                throw cursor.invalid("a range cannot end with a class escape");
            }
            high = e == 'b' ? '\b' : cursor.character(e);
        }
        if (high < low) {
            throw cursor.invalid("a range of a character class is out of order");
        }
        folded.set(low, high + 1);
    }

    private static void refuseProperty(PgPcreCursor cursor, int e) {
        if (e == 'p' || e == 'P' || e == 'X' || e == 'R' || e == 'N') {
            throw cursor.untranslatable("\\" + Character.toString(e) + " inside a character class",
                    "Unicode properties and sequences have no bracket equivalent");
        }
    }

    private void quoted(PgPcreCursor cursor) {
        while (!cursor.atEnd() && !cursor.eat("\\E")) {
            folded.set(cursor.next());
        }
    }

    private void posix(PgPcreCursor cursor) {
        cursor.next();
        boolean complement = cursor.eat("^");
        String name = cursor.until(':');
        if (!cursor.eat("]")) {
            throw cursor.invalid("a POSIX class [:" + name + ": is not closed by ':]'");
        }
        String ranges = POSIX.get(name.toLowerCase(Locale.ROOT));
        if (ranges == null) {
            throw cursor.invalid("[:" + name + ":] is not a POSIX class");
        }
        fixed.or(ranges(ranges, complement));
    }

    /** {@return the explicit members, before case expansion and negation} */
    private BitSet members() {
        BitSet all = (BitSet) folded.clone();
        all.or(fixed);
        return negated ? complement(all) : all;
    }

    /**
     * The bracket expression PostgreSQL reads as the same set.
     *
     * @param caseless whether {@code (?i)} applies
     * @return the bracket expression
     */
    String toAre(boolean caseless) {
        BitSet all = (BitSet) folded.clone();
        if (caseless) {
            for (int cp = folded.nextSetBit(0); cp >= 0; cp = folded.nextSetBit(cp + 1)) {
                for (int variant : PgCaseFold.variants(cp)) {
                    all.set(variant);
                }
            }
        }
        all.or(fixed);
        all.clear(SURROGATE_FIRST, SURROGATE_END);
        all.clear(0);
        if (all.isEmpty()) {
            return negated ? "." : "[^\\u0001-\\U0010FFFF]";
        }
        StringBuilder out = new StringBuilder(negated ? "[^" : "[");
        int low = all.nextSetBit(0);
        while (low >= 0) {
            int end = all.nextClearBit(low);
            out.append(escape(low, true));
            if (end - low > 2) {
                out.append('-');
            }
            if (end - low > 1) {
                out.append(escape(end - 1, true));
            }
            low = all.nextSetBit(end);
        }
        return out.append(']').toString();
    }

    /**
     * One character, spelled so PostgreSQL reads it literally — the same spelling in and out of a
     * bracket: ASCII letters and digits as they are, other printable ASCII behind a backslash,
     * everything else as a {@code \\u} code.
     *
     * @param cp        the code point
     * @param inBracket whether it sits in a bracket expression (a space needs no escape outside)
     * @return its spelling
     */
    static String escape(int cp, boolean inBracket) {
        if (cp < 0x80 && Character.isLetterOrDigit(cp) || cp == ' ' && !inBracket) {
            return Character.toString(cp);
        }
        if (cp >= PRINTABLE_FIRST && cp <= PRINTABLE_LAST) {
            return "\\" + (char) cp;
        }
        return cp < BMP_END ? String.format(Locale.ROOT, "\\u%04X", cp) : String.format(Locale.ROOT, "\\U%08X", cp);
    }

    private static BitSet ranges(String spec, boolean complement) {
        BitSet set = new BitSet();
        int i = 0;
        while (i < spec.length()) {
            int low = spec.charAt(i);
            if (i + 2 < spec.length() && spec.charAt(i + 1) == '-') {
                set.set(low, spec.charAt(i + 2) + 1);
                i += 3;
            } else {
                set.set(low);
                i++;
            }
        }
        return complement ? complement(set) : set;
    }

    private static BitSet complement(BitSet set) {
        BitSet complement = new BitSet();
        complement.set(0, ALL);
        complement.andNot(set);
        return complement;
    }
}
