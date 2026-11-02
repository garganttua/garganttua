package com.garganttua.api.binding.geojson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON GeoJSON needs: objects, arrays, strings, numbers, {@code true}, {@code false} and
 * {@code null} — written and read without a JSON library.
 *
 * <p>
 * It is complete for JSON values (escapes included), so it reads whatever a store renders back — a
 * PostGIS {@code ST_AsGeoJSON} adds a {@code crs} object or a {@code bbox} when asked, and both parse
 * — but it is used for GeoJSON only, and makes no claim beyond it. Numbers are read as
 * {@code Double}: a coordinate is one.
 * </p>
 */
final class GeoJsonText {

    /** The first character JSON does not have to escape. */
    private static final char FIRST_PRINTABLE = ' ';
    private static final char QUOTE = '"';
    private static final char BACKSLASH = '\\';

    private final String text;
    private int at;

    private GeoJsonText(String text) {
        this.text = text;
    }

    /** Writes a value made of maps, lists, strings, numbers, booleans and nulls. */
    static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            string(value.toString(), out);
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < FIRST_PRINTABLE) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Parses a JSON text.
     *
     * @param text the text
     * @return maps, lists, strings, {@code Double}s, booleans and nulls
     * @throws IllegalArgumentException when the text is not one JSON value
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("No GeoJSON text to read");
        }
        GeoJsonText reader = new GeoJsonText(text);
        Object value = reader.value();
        reader.blanks();
        if (reader.at != text.length()) {
            throw reader.error("unexpected text after the value");
        }
        return value;
    }

    private Object value() {
        blanks();
        if (this.at >= this.text.length()) {
            throw error("a value was expected");
        }
        char c = this.text.charAt(this.at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        this.at++;
        blanks();
        if (peek('}')) {
            this.at++;
            return map;
        }
        while (true) {
            blanks();
            if (!peek('"')) {
                throw error("a member name was expected");
            }
            String key = string();
            expect(':');
            map.put(key, value());
            blanks();
            if (peek(',')) {
                this.at++;
            } else {
                expect('}');
                return map;
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        this.at++;
        blanks();
        if (peek(']')) {
            this.at++;
            return list;
        }
        while (true) {
            list.add(value());
            blanks();
            if (peek(',')) {
                this.at++;
            } else {
                expect(']');
                return list;
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        this.at++;
        while (this.at < this.text.length()) {
            char c = this.text.charAt(this.at);
            this.at++;
            if (c == QUOTE) {
                return out.toString();
            }
            if (c != BACKSLASH) {
                out.append(c);
                continue;
            }
            if (this.at >= this.text.length()) {
                break;
            }
            char escaped = this.text.charAt(this.at);
            this.at++;
            switch (escaped) {
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> out.append(escaped);
            }
        }
        throw error("an unterminated string");
    }

    private char unicode() {
        if (this.at + 4 > this.text.length()) {
            throw error("a truncated \\u escape");
        }
        try {
            char c = (char) Integer.parseInt(this.text.substring(this.at, this.at + 4), 16);
            this.at += 4;
            return c;
        } catch (NumberFormatException e) {
            throw error("an invalid \\u escape", e);
        }
    }

    private Double number() {
        int start = this.at;
        while (this.at < this.text.length() && "+-0123456789.eE".indexOf(this.text.charAt(this.at)) >= 0) {
            this.at++;
        }
        if (start == this.at) {
            throw error("an unexpected character");
        }
        try {
            return Double.valueOf(this.text.substring(start, this.at));
        } catch (NumberFormatException e) {
            throw error("an invalid number", e);
        }
    }

    private Object literal(String word, Object value) {
        if (!this.text.startsWith(word, this.at)) {
            throw error("an unexpected word");
        }
        this.at += word.length();
        return value;
    }

    private void expect(char c) {
        blanks();
        if (!peek(c)) {
            throw error("'" + c + "' was expected");
        }
        this.at++;
    }

    private boolean peek(char c) {
        return this.at < this.text.length() && this.text.charAt(this.at) == c;
    }

    private void blanks() {
        while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
            this.at++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Invalid GeoJSON text at offset " + this.at + ": " + what);
    }

    private IllegalArgumentException error(String what, Throwable cause) {
        return new IllegalArgumentException("Invalid GeoJSON text at offset " + this.at + ": " + what, cause);
    }
}
