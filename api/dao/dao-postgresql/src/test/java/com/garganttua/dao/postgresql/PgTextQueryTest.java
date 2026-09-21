package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a {@code $text} search string is read: MongoDB's decomposition into terms, phrases and
 * negations, and the diacritic folding shared with the SQL side.
 */
@DisplayName("PgTextQuery")
class PgTextQueryTest {

    private static PgTextQuery q(String search) {
        return PgTextQuery.parse(search);
    }

    @Test
    @DisplayName("words are separate terms; punctuation separates without negating")
    void words() {
        assertEquals(List.of("coffee", "fox"), q("coffee fox").positive());
        assertEquals(List.of("state", "of", "the", "art"), q("state-of-the-art").positive());
        assertEquals(List.of("fox"), q("fox!").positive());
        assertEquals(List.of("c_300"), q("c_300").positive());
    }

    @Test
    @DisplayName("a '-' after whitespace negates up to the next whitespace")
    void negation() {
        PgTextQuery query = q("tests -unit -a-b c");
        assertEquals(List.of("tests", "c"), query.positive());
        assertEquals(List.of("unit", "a", "b"), query.negated());
    }

    @Test
    @DisplayName("a phrase is required verbatim, and its words are positive terms too")
    void phrases() {
        PgTextQuery query = q("\"brown fox\" dog");
        assertEquals(List.of("brown fox"), query.phrases());
        assertEquals(List.of("brown", "fox", "dog"), query.positive());
    }

    @Test
    @DisplayName("a negated phrase contributes no term")
    void negatedPhrase() {
        PgTextQuery query = q("dog -\"lazy cat\"");
        assertEquals(List.of("lazy cat"), query.negatedPhrases());
        assertEquals(List.of("dog"), query.positive());
        assertEquals(List.of(), query.negated());
    }

    @Test
    @DisplayName("diacritics are folded, case is left to PostgreSQL")
    void fold() {
        assertEquals("Cafe Evian Montreal", PgTextQuery.fold("Café Évian Montréal"));
        assertEquals("e", PgTextQuery.fold("é"));
        assertEquals(PgTextQuery.FOLD_FROM.codePointCount(0, PgTextQuery.FOLD_FROM.length()),
                PgTextQuery.FOLD_FROM.length(), "translate() pairs characters one by one: BMP only");
    }
}
