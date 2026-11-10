package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader of child-row aggregates: it replaces a general JSON parser on the hottest path of a read, so
 * it must read back exactly what PostgreSQL's {@code json_agg(json_build_array(...))} writes.
 */
@DisplayName("Child-row aggregate text")
class PgAggregateTextTest {

    @Test
    @DisplayName("rows of texts and nulls, with nested aggregates, as PostgreSQL lays them out")
    void shape() {
        List<Object> rows = PgAggregateText.parse(
                "[[\"1\", \"a\", null, [[\"10\", \"x\"], \n [\"11\", \"y\"]]], \n [\"2\", \"b\", \"c\", null]]");

        assertEquals(List.of(
                Arrays.asList("1", "a", null, List.of(List.of("10", "x"), List.of("11", "y"))),
                Arrays.asList("2", "b", "c", null)), rows);
    }

    @Test
    @DisplayName("empty arrays, at the top and nested")
    void empty() {
        assertEquals(List.of(), PgAggregateText.parse("[]"));
        assertEquals(List.of(List.of(), List.of("a")), PgAggregateText.parse(" [ [ ] , [\"a\"] ] "));
    }

    @Test
    @DisplayName("every JSON escape reads back to its character, surrogate pairs included")
    void escapes() {
        String text = (String) PgAggregateText.parse(
                "[\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9 \\ud83d\\ude00 end\"]").get(0);

        assertEquals("q\" b\\ s/ \b\f\n\r\t é \ud83d\ude00 end", text);
    }

    @Test
    @DisplayName("a string without escape is taken as it is — non-ASCII included")
    void plain() {
        assertEquals(List.of("Établissement Saint-Éloi — 13 rue d'Alésia"),
                PgAggregateText.parse("[\"Établissement Saint-Éloi — 13 rue d'Alésia\"]"));
    }

    @Test
    @DisplayName("a bare literal is read as its text, as a parser's getText would")
    void literals() {
        assertEquals(Arrays.asList("12", "true", null, "-3.5"), PgAggregateText.parse("[12, true, null, -3.5]"));
    }

    @Test
    @DisplayName("malformed text is refused with where it went wrong")
    void malformed() {
        for (String bad : List.of("", "{}", "[\"a\"", "[\"a\" \"b\"]", "[\"unterminated]", "[\"\\x\"]",
                "[\"\\u12\"]", "[\"a\"] x", "[,]")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PgAggregateText.parse(bad), bad);
            assertTrue(e.getMessage().contains("offset"), () -> bad + " -> " + e.getMessage());
        }
    }
}
