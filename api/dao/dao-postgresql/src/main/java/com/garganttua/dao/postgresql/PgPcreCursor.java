package com.garganttua.dao.postgresql;

import com.garganttua.api.commons.ApiException;

/**
 * A reading position in a PCRE pattern, by code point, with the character escapes PCRE shares
 * between the pattern body and its bracket expressions.
 *
 * <p>
 * {@link PgPcreTranslator} and {@link PgPcreClass} read the same pattern; keeping the position and
 * the escape grammar in one place means a {@code \x{e9}} is decoded identically inside and outside
 * brackets, and that every refusal names the pattern the same way.
 * </p>
 */
// A PCRE reader: the character literals it tests ('\\', '{', ')', 'Q', ...) ARE the grammar being parsed,
// and its small integers are bounds (group 1, range lengths); naming each would only hide the syntax.
// Its constants share a name with the method that uses them (accessor style).
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidLiteralsInIfCondition"})
final class PgPcreCursor {

    private static final int HEX = 16;
    private static final int OCTAL = 8;
    private static final int CONTROL_FLIP = 0x40;

    private final String pattern;
    private int pos;

    PgPcreCursor(String pattern) {
        this.pattern = pattern;
    }

    boolean atEnd() {
        return pos >= pattern.length();
    }

    /** {@return the pattern from the current position on, not consumed} */
    String rest() {
        return pattern.substring(pos);
    }

    /** Consumes {@code count} characters already examined through {@link #rest()}. */
    void skip(int count) {
        pos += count;
    }

    /** {@return the next code point, consumed} */
    int next() {
        if (atEnd()) {
            throw invalid("the pattern ends too early");
        }
        int cp = pattern.codePointAt(pos);
        pos += Character.charCount(cp);
        return cp;
    }

    /** {@return the code point {@code ahead} characters away, or -1 past the end} */
    int peek(int ahead) {
        int at = pos + ahead;
        return at < pattern.length() ? pattern.charAt(at) : -1;
    }

    /** {@return whether the rest of the pattern starts with the text, consuming it when it does} */
    boolean eat(String text) {
        if (pattern.startsWith(text, pos)) {
            pos += text.length();
            return true;
        }
        return false;
    }

    /** {@return the text up to (excluded) the terminator, consuming both} */
    String until(char terminator) {
        int end = pattern.indexOf(terminator, pos);
        if (end < 0) {
            throw invalid("'" + terminator + "' is missing");
        }
        String text = pattern.substring(pos, end);
        pos = end + 1;
        return text;
    }

    /** {@return the longest run of decimal digits, consumed (possibly empty)} */
    String digits() {
        int start = pos;
        while (!atEnd() && Character.isDigit(pattern.charAt(pos)) && pattern.charAt(pos) < 0x80) {
            pos++;
        }
        return pattern.substring(start, pos);
    }

    /** {@return whether only closing parentheses remain — nothing can backtrack into what precedes} */
    boolean onlyClosersRemain() {
        for (int i = pos; i < pattern.length(); i++) {
            if (pattern.charAt(i) != ')') {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes a character escape — the part after the backslash has been read.
     *
     * @param c the escape letter or symbol
     * @return the code point it stands for
     * @throws ApiException when PCRE would not accept the escape either
     */
    int character(int c) {
        return switch (c) {
            case 't' -> '\t';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 'f' -> '\f';
            case 'e' -> 0x1B;
            case 'a' -> 0x07;
            case 'x' -> hex();
            case 'o' -> number(braced(), OCTAL);
            case '0' -> octalAfterZero();
            case 'c' -> Character.toUpperCase(next()) ^ CONTROL_FLIP;
            default -> {
                if (c < 0x80 && Character.isLetterOrDigit(c)) {
                    throw invalid("the escape \\" + Character.toString(c) + " is not a PCRE escape");
                }
                yield c;
            }
        };
    }

    private int hex() {
        if (peek(0) == '{') {
            return number(braced(), HEX);
        }
        StringBuilder digits = new StringBuilder();
        while (digits.length() < 2 && !atEnd() && Character.digit(pattern.charAt(pos), HEX) >= 0) {
            digits.append(pattern.charAt(pos));
            pos++;
        }
        return digits.isEmpty() ? 0 : number(digits.toString(), HEX);
    }

    private int octalAfterZero() {
        StringBuilder digits = new StringBuilder("0");
        while (digits.length() < 3 && !atEnd() && Character.digit(pattern.charAt(pos), OCTAL) >= 0) {
            digits.append(pattern.charAt(pos));
            pos++;
        }
        return number(digits.toString(), OCTAL);
    }

    private String braced() {
        if (!eat("{")) {
            throw invalid("a '{' is expected after the escape");
        }
        return until('}');
    }

    private int number(String digits, int radix) {
        int cp;
        try {
            cp = Integer.parseInt(digits.trim(), radix);
        } catch (NumberFormatException e) {
            ApiException refusal = notACharacterCode(digits);
            refusal.initCause(e);
            throw refusal;
        }
        if (!Character.isValidCodePoint(cp)) {
            throw notACharacterCode(digits);
        }
        return cp;
    }

    private ApiException notACharacterCode(String digits) {
        return invalid("'" + digits + "' is not a character code");
    }

    /** {@return a refusal of an invalid pattern — PCRE refuses it too} */
    ApiException invalid(String why) {
        return new ApiException("invalid regular expression '" + pattern + "': " + why);
    }

    /** {@return a refusal of a valid PCRE construct PostgreSQL cannot reproduce exactly} */
    ApiException untranslatable(String construct, String why) {
        return new ApiException("the regular expression '" + pattern + "' uses " + construct
                + ", which PostgreSQL's regular expressions cannot reproduce exactly (" + why
                + "); rewrite the pattern without it");
    }
}
