package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the JSON text of a child-row aggregate ({@link PgChildAggregate}) into nested lists — texts,
 * nulls and lists — in one pass over its characters.
 *
 * <p>
 * The aggregate is written by the DAO itself and has one shape: arrays whose items are JSON strings
 * (every cell is cast to {@code text}), {@code null}, or arrays (the aggregates of the tables below). A
 * general JSON parser builds a context per array and copies every string through a buffer; on a page
 * whose collections hold collections, that was the larger half of what decoding cost. This reader knows
 * the shape, so a string is one {@code substring} when it holds no escape.
 * </p>
 *
 * <p>
 * It still reads any JSON array of that shape, whitespace and escapes included ({@code \"}, {@code \\},
 * {@code \/}, {@code \b}, {@code \f}, {@code \n}, {@code \r}, {@code \t}, {@code \}{@code uXXXX}). A bare
 * literal (a number, {@code true}, {@code false}) is read as its text, as a parser's {@code getText()}
 * would. Anything else is refused with the offset where it was found.
 * </p>
 */
final class PgAggregateText {

    private static final char OPEN = '[';
    private static final char CLOSE = ']';
    private static final char COMMA = ',';
    private static final char QUOTE = '"';
    private static final char ESCAPE = '\\';

    private final String json;
    private int pos;

    private PgAggregateText(String json) {
        this.json = json;
    }

    /**
     * Reads an aggregate.
     *
     * @param json the JSON text, an array
     * @return its items: {@code String}, {@code null} or {@code List<Object>}, nested as in the text
     * @throws IllegalArgumentException when the text is not an array of that shape
     */
    static List<Object> parse(String json) {
        PgAggregateText reader = new PgAggregateText(json);
        reader.skipSpace();
        List<Object> values = reader.array();
        reader.skipSpace();
        if (reader.pos != json.length()) {
            throw reader.error("text after the array");
        }
        return values;
    }

    private List<Object> array() {
        expect(OPEN);
        List<Object> values = new ArrayList<>();
        skipSpace();
        if (peek() == CLOSE) {
            this.pos++;
            return values;
        }
        while (true) {
            skipSpace();
            values.add(value());
            skipSpace();
            char c = next();
            if (c == CLOSE) {
                return values;
            }
            if (c != COMMA) {
                throw error("',' or ']' expected");
            }
        }
    }

    private Object value() {
        char c = peek();
        if (c == QUOTE) {
            return string();
        }
        if (c == OPEN) {
            return array();
        }
        int start = this.pos;
        while (this.pos < this.json.length() && !isEnd(this.json.charAt(this.pos))) {
            this.pos++;
        }
        if (start == this.pos) {
            throw error("a value expected");
        }
        String literal = this.json.substring(start, this.pos);
        return "null".equals(literal) ? null : literal;
    }

    private static boolean isEnd(char c) {
        return c == ',' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    private String string() {
        expect(QUOTE);
        int start = this.pos;
        int length = this.json.length();
        while (this.pos < length) {
            char c = this.json.charAt(this.pos);
            if (c == QUOTE) {
                String plain = this.json.substring(start, this.pos);
                this.pos++;
                return plain;
            }
            if (c == ESCAPE) {
                return escaped(start);
            }
            this.pos++;
        }
        throw error("unterminated string");
    }

    /** The rest of a string holding an escape, from its first character. */
    private String escaped(int start) {
        StringBuilder out = new StringBuilder(this.pos - start + 16).append(this.json, start, this.pos);
        while (this.pos < this.json.length()) {
            char c = this.json.charAt(this.pos);
            this.pos++;
            if (c == QUOTE) {
                return out.toString();
            }
            if (c != ESCAPE) {
                out.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw error("unknown escape \\" + e);
            }
        }
        throw error("unterminated string");
    }

    private char unicode() {
        if (this.pos + 4 > this.json.length()) {
            throw error("truncated \\u escape");
        }
        try {
            char c = (char) Integer.parseInt(this.json, this.pos, this.pos + 4, 16);
            this.pos += 4;
            return c;
        } catch (NumberFormatException e) {
            IllegalArgumentException bad = error("bad \\u escape");
            bad.initCause(e);
            throw bad;
        }
    }

    private void skipSpace() {
        while (this.pos < this.json.length()) {
            char c = this.json.charAt(this.pos);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            this.pos++;
        }
    }

    private char peek() {
        if (this.pos >= this.json.length()) {
            throw error("unexpected end");
        }
        return this.json.charAt(this.pos);
    }

    private char next() {
        char c = peek();
        this.pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            this.pos--;
            throw error("'" + c + "' expected");
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + this.pos + " of a child-row aggregate");
    }
}
