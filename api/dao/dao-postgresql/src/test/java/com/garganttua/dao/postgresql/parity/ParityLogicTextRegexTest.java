package com.garganttua.dao.postgresql.parity;

import static com.garganttua.dao.postgresql.parity.ParityFilter.field;
import static com.garganttua.dao.postgresql.parity.ParityFilter.listed;
import static com.garganttua.dao.postgresql.parity.ParityFilter.logical;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;
import com.mongodb.client.model.Indexes;

/**
 * Parity of the logical operators ({@code $and}, {@code $or}, {@code $nor}), {@code $regex} and
 * {@code $text} between the MongoDB DAO and the PostgreSQL DAO.
 *
 * <p>
 * Every scenario compares the SET OF UUIDS each engine returns (or its failure), not the full
 * objects: what is under test here is which rows a filter selects. Read-back shape differences
 * (null vs empty collection, empty embedded object) belong to another family and would otherwise
 * mask the answer to the question each scenario asks.
 * </p>
 */
@DisplayName("Parity — logical operators, $regex, $text")
class ParityLogicTextRegexTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public enum Color {
        RED, GREEN, BLUE
    }

    public static class Address {
        String city;
        String street;
    }

    public static class Item {
        String uuid;
        String name;
        String code;
        String description;
        Integer age;
        List<String> tags;
        Address address;
        Color color;
    }

    public static class Doc {
        String uuid;
        String title;
        String body;
        List<String> tags;
        Address address;
    }

    private static Doc doc(String uuid, String title, String body, List<String> tags, String city) {
        Doc d = new Doc();
        d.uuid = uuid;
        d.title = title;
        d.body = body;
        d.tags = tags == null ? null : new ArrayList<>(tags);
        if (city != null) {
            d.address = new Address();
            d.address.city = city;
        }
        return d;
    }

    private static Item item(String uuid, String name, String code, String description, Integer age,
            List<String> tags, String city, Color color) {
        Item i = new Item();
        i.uuid = uuid;
        i.name = name;
        i.code = code;
        i.description = description;
        i.age = age;
        i.tags = tags == null ? null : new ArrayList<>(tags);
        if (city != null) {
            i.address = new Address();
            i.address.city = city;
            i.address.street = "main street";
        }
        i.color = color;
        return i;
    }

    /**
     * The discriminating data set for logical and regex scenarios. Absent values are null in Java:
     * MongoDocumentWriter OMITS null fields, so they are "absent" on the MongoDB side.
     */
    private static ParityHarness seeded() {
        ParityHarness h = ParityHarness.of(Domain.of("items", Item.class));
        h.save("items",
                item("i1", "alpha", "A-100", "the cat sat", 18, List.of("red", "blue"), "Paris", Color.RED),
                item("i2", "Alpha", "B.200", "concatenate", 30, List.of("green"), "paris", Color.BLUE),
                item("i3", "beta", null, null, null, null, null, null),
                item("i4", null, "a+b", "x", 5, List.of("x1"), "Lyon", Color.GREEN),
                item("i5", "gamma\n", "C_300", "tab\there", 42, List.of("Red"), "Nice", Color.RED),
                item("i6", "line1\nline2", "Bx200", "cafe", 7, List.of("zz"), "Évian", null),
                item("i7", "café", "abc", "a.b", 60, null, "Lille", Color.BLUE));
        return h;
    }

    /** Reduces both answers to their sorted uuid sets (errors are kept as they are). */
    private static Outcome ids(Outcome o) {
        return new Outcome(uuids(o.mongo()), uuids(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object uuids(Object result) {
        if (!(result instanceof List<?> list)) {
            return result;
        }
        TreeSet<String> set = new TreeSet<>();
        for (Object o : list) {
            set.add(((Item) o).uuid);
        }
        return new ArrayList<>(set);
    }

    private static void check(String what, ParityHarness h, IFilter filter) {
        ParityHarness.assertSame(what, ids(h.find("items", filter)), false);
    }

    // ------------------------------------------------------------------ logical operators

    @Nested
    @DisplayName("Logical operators")
    class Logical {

        @Test
        @DisplayName("$and of $or and a comparison")
        void andOfOr() {
            check("(name=alpha OR age<10) AND color=RED", seeded(), logical("$and",
                    logical("$or", field("name", "$eq", "alpha"), field("age", "$lt", 10)),
                    field("color", "$eq", "RED")));
        }

        @Test
        @DisplayName("$or of three branches, one on an array")
        void orThree() {
            check("name=beta OR tags=green OR age>50", seeded(), logical("$or",
                    field("name", "$eq", "beta"), field("tags", "$eq", "green"), field("age", "$gt", 50)));
        }

        @Test
        @DisplayName("$nor over $eq keeps rows whose field is absent")
        void norEqAbsent() {
            check("NOR(name=alpha, name=beta)", seeded(), logical("$nor",
                    field("name", "$eq", "alpha"), field("name", "$eq", "beta")));
        }

        @Test
        @DisplayName("$nor over $gt keeps rows whose number is absent")
        void norGtAbsent() {
            check("NOR(age>20, code=abc)", seeded(), logical("$nor",
                    field("age", "$gt", 20), field("code", "$eq", "abc")));
        }

        @Test
        @DisplayName("$nor over $ne null selects exactly the absent rows")
        void norNeNull() {
            check("NOR(code!=null, description!=null)", seeded(), logical("$nor",
                    field("code", "$ne", null), field("description", "$ne", null)));
        }

        @Test
        @DisplayName("$nor over $empty keeps only present fields")
        void norEmpty() {
            check("NOR(name $empty, code $empty)", seeded(), logical("$nor",
                    field("name", "$empty", true), field("code", "$empty", true)));
        }

        @Test
        @DisplayName("$nor over a nested POJO field keeps rows without the POJO")
        void norNestedField() {
            check("NOR(address.city=Paris, address.city=Lyon)", seeded(), logical("$nor",
                    field("address.city", "$eq", "Paris"), field("address.city", "$eq", "Lyon")));
        }

        @Test
        @DisplayName("$nor over an array keeps null arrays")
        void norArray() {
            check("NOR(tags=red, tags=green)", seeded(), logical("$nor",
                    field("tags", "$eq", "red"), field("tags", "$eq", "green")));
        }

        @Test
        @DisplayName("$nor over $in listing null")
        void norInNull() {
            check("NOR(code IN [null, abc], age<0)", seeded(), logical("$nor",
                    listed("code", "$in", null, "abc"), field("age", "$lt", 0)));
        }

        @Test
        @DisplayName("$nor of $and (NOT of a conjunction over absent values)")
        void norOfAnd() {
            check("NOR(AND(age>=18, color=RED), name=beta)", seeded(), logical("$nor",
                    logical("$and", field("age", "$gte", 18), field("color", "$eq", "RED")),
                    field("name", "$eq", "beta")));
        }

        @Test
        @DisplayName("$nor inside $nor is a double negation")
        void norOfNor() {
            check("NOR(NOR(age>20, name=beta), code=abc)", seeded(), logical("$nor",
                    logical("$nor", field("age", "$gt", 20), field("name", "$eq", "beta")),
                    field("code", "$eq", "abc")));
        }

        @Test
        @DisplayName("$or inside $nor on absent fields")
        void orInsideNor() {
            check("NOR(OR(description=x, age<6), code=A-100)", seeded(), logical("$nor",
                    logical("$or", field("description", "$eq", "x"), field("age", "$lt", 6)),
                    field("code", "$eq", "A-100")));
        }

        @Test
        @DisplayName("count agrees with find under $nor")
        void countNor() {
            ParityHarness h = seeded();
            ParityHarness.assertSame("count NOR(age>20, name=alpha)", h.count("items", logical("$nor",
                    field("age", "$gt", 20), field("name", "$eq", "alpha"))), false);
        }

        @Test
        @DisplayName("$or mixing an unknown field with a known one")
        void orUnknownField() {
            check("name=alpha OR nosuchfield=1", seeded(), logical("$or",
                    field("name", "$eq", "alpha"), field("nosuchfield", "$eq", 1)));
        }

        @Test
        @DisplayName("$or with $empty on an unknown field (absent everywhere)")
        void orUnknownEmpty() {
            check("name=alpha OR nosuchfield $empty", seeded(), logical("$or",
                    field("name", "$eq", "alpha"), field("nosuchfield", "$empty", true)));
        }

        @Test
        @DisplayName("$nor over an unknown field")
        void norUnknownField() {
            check("NOR(nosuchfield=1, name=alpha)", seeded(), logical("$nor",
                    field("nosuchfield", "$eq", 1), field("name", "$eq", "alpha")));
        }

        @Test
        @DisplayName("$and with an unknown nested path")
        void andUnknownNested() {
            check("age>0 AND address.zip=75000", seeded(), logical("$and",
                    field("age", "$gt", 0), field("address.zip", "$eq", "75000")));
        }

        @Test
        @DisplayName("a logical operator with a single operand is refused by both")
        void singleOperand() {
            check("$or[name=alpha]", seeded(), logical("$or", field("name", "$eq", "alpha")));
        }

        @Test
        @DisplayName("$or with a mistyped (String) number")
        void orMistyped() {
            check("age='18' OR name=beta", seeded(), logical("$or",
                    field("age", "$eq", "18"), field("name", "$eq", "beta")));
        }
    }

    // ------------------------------------------------------------------ $regex

    @Nested
    @DisplayName("$regex")
    class Regex {

        @Test
        @DisplayName("^ anchor, case-sensitive")
        void caretAnchor() {
            check("name ~ ^al", seeded(), field("name", "$regex", "^al"));
        }

        @Test
        @DisplayName("(?i) at the start of the pattern")
        void inlineCaseInsensitive() {
            check("name ~ (?i)^al", seeded(), field("name", "$regex", "(?i)^al"));
        }

        @Test
        @DisplayName("(?i) in the middle of the pattern")
        void inlineCaseInsensitiveMid() {
            check("name ~ ^al(?i)PHA", seeded(), field("name", "$regex", "^al(?i)PHA"));
        }

        @Test
        @DisplayName("$ anchor before a trailing newline")
        void dollarBeforeTrailingNewline() {
            check("name ~ ^gamma$", seeded(), field("name", "$regex", "^gamma$"));
        }

        @Test
        @DisplayName("$ anchor on plain values")
        void dollarPlain() {
            check("name ~ a$", seeded(), field("name", "$regex", "a$"));
        }

        @Test
        @DisplayName("dot against a newline")
        void dotNewline() {
            check("name ~ line1.line2", seeded(), field("name", "$regex", "line1.line2"));
        }

        @Test
        @DisplayName("\\d quantified")
        void digits() {
            check("code ~ \\d{3}$", seeded(), field("code", "$regex", "\\d{3}$"));
        }

        @Test
        @DisplayName("\\w on ASCII")
        void wordAscii() {
            check("code ~ ^\\w\\w\\w$", seeded(), field("code", "$regex", "^\\w\\w\\w$"));
        }

        @Test
        @DisplayName("\\w on a non-ASCII letter")
        void wordUnicode() {
            check("name ~ ^caf\\w$", seeded(), field("name", "$regex", "^caf\\w$"));
        }

        @Test
        @DisplayName("\\s matches a tab and a space")
        void whitespace() {
            check("description ~ \\s", seeded(), field("description", "$regex", "\\s"));
        }

        @Test
        @DisplayName("character class range")
        void charClass() {
            check("code ~ ^[A-B][^0-9]", seeded(), field("code", "$regex", "^[A-B][^0-9]"));
        }

        @Test
        @DisplayName("alternation in a group")
        void alternation() {
            check("name ~ ^(alpha|beta)$", seeded(), field("name", "$regex", "^(alpha|beta)$"));
        }

        @Test
        @DisplayName("unescaped dot matches any character")
        void unescapedDot() {
            check("code ~ ^B.200$", seeded(), field("code", "$regex", "^B.200$"));
        }

        @Test
        @DisplayName("escaped dot matches only a dot")
        void escapedDot() {
            check("code ~ ^B\\.200$", seeded(), field("code", "$regex", "^B\\.200$"));
        }

        @Test
        @DisplayName("escaped + and a literal dot in a class")
        void specialChars() {
            check("code ~ a\\+b OR description ~ a[.]b", seeded(), logical("$or",
                    field("code", "$regex", "a\\+b"), field("description", "$regex", "a[.]b")));
        }

        @Test
        @DisplayName("\\b word boundary (PCRE)")
        void wordBoundary() {
            check("description ~ \\bcat\\b", seeded(), field("description", "$regex", "\\bcat\\b"));
        }

        @Test
        @DisplayName("\\A and \\z anchors (PCRE)")
        void pcreAnchors() {
            check("name ~ \\Abeta\\z", seeded(), field("name", "$regex", "\\Abeta\\z"));
        }

        @Test
        @DisplayName("possessive quantifier (PCRE)")
        void possessive() {
            check("code ~ \\d++", seeded(), field("code", "$regex", "\\d++"));
        }

        @Test
        @DisplayName("lookahead")
        void lookahead() {
            check("name ~ ^a(?=l)", seeded(), field("name", "$regex", "^a(?=l)"));
        }

        @Test
        @DisplayName("case-insensitive on a non-ASCII letter")
        void caseInsensitiveAccent() {
            check("name ~ (?i)^CAFÉ$", seeded(), field("name", "$regex", "(?i)^CAFÉ$"));
        }

        @Test
        @DisplayName("an invalid pattern fails on both")
        void invalidPattern() {
            check("name ~ (", seeded(), field("name", "$regex", "("));
        }

        @Test
        @DisplayName("a numeric pattern value is used as its text")
        void numericPatternValue() {
            check("code ~ 200 (Integer)", seeded(), field("code", "$regex", 200));
        }

        @Test
        @DisplayName("on a number field")
        void onNumberField() {
            check("age ~ 1", seeded(), field("age", "$regex", "1"));
        }

        @Test
        @DisplayName("on a number field inside $nor")
        void onNumberFieldUnderNor() {
            check("NOR(age ~ 1, name=beta)", seeded(), logical("$nor",
                    field("age", "$regex", "1"), field("name", "$eq", "beta")));
        }

        @Test
        @DisplayName("on a number field in an $or with a string branch")
        void onNumberFieldInOr() {
            check("age ~ 1 OR name ~ ^b", seeded(), logical("$or",
                    field("age", "$regex", "1"), field("name", "$regex", "^b")));
        }

        @Test
        @DisplayName("on a field absent on some rows")
        void onAbsentField() {
            check("name ~ .*", seeded(), field("name", "$regex", ".*"));
        }

        @Test
        @DisplayName("under $nor, a regex on an absent field keeps the row")
        void norOnAbsent() {
            check("NOR(code ~ ^[A-C], description ~ c)", seeded(), logical("$nor",
                    field("code", "$regex", "^[A-C]"), field("description", "$regex", "c")));
        }

        @Test
        @DisplayName("on array elements")
        void onArray() {
            check("tags ~ ^r", seeded(), field("tags", "$regex", "^r"));
        }

        @Test
        @DisplayName("on array elements, case-insensitive")
        void onArrayCaseInsensitive() {
            check("tags ~ (?i)^r", seeded(), field("tags", "$regex", "(?i)^r"));
        }

        @Test
        @DisplayName("on a nested POJO field")
        void onNested() {
            check("address.city ~ (?i)^p", seeded(), field("address.city", "$regex", "(?i)^p"));
        }

        @Test
        @DisplayName("on a nested POJO field with a non-ASCII initial")
        void onNestedAccent() {
            check("address.city ~ ^É", seeded(), field("address.city", "$regex", "^É"));
        }

        @Test
        @DisplayName("on an enum field")
        void onEnum() {
            check("color ~ ^(RED|BLUE)$", seeded(), field("color", "$regex", "^(RED|BLUE)$"));
        }

        @Test
        @DisplayName("on the uuid field")
        void onUuid() {
            check("uuid ~ ^i[1-3]$", seeded(), field("uuid", "$regex", "^i[1-3]$"));
        }

        @Test
        @DisplayName("on an unknown field")
        void onUnknown() {
            check("nosuchfield ~ .", seeded(), field("nosuchfield", "$regex", "."));
        }
    }

    // ------------------------------------------------------------------ $text

    @Nested
    @DisplayName("$text")
    class Text {

        private ParityHarness texts() {
            ParityHarness h = ParityHarness.of(Domain.of("items", Doc.class));
            h.mongoDatabase().getCollection("items").createIndex(Indexes.text("$**"));
            h.save("items",
                    doc("t1", "Running in the park", "fast runner", List.of("marathon"), "Montréal"),
                    doc("t2", "The café is open", "coffee and cake", List.of("breakfast"), "Paris"),
                    doc("t3", "Quick brown fox", "jumps over lazy dog", List.of("animals", "fox"), "Lyon"),
                    doc("t4", "He ran home", null, null, null),
                    doc("t5", "Run the tests", "unit tests", List.of("ci"), "Nice"),
                    doc("t6", null, "cafe au lait", List.of("drink"), null),
                    doc("t7", "integration tests", "slow", null, "Brest"));
            return h;
        }

        private void text(String what, ParityHarness h, IFilter filter) {
            Outcome o = h.find("items", filter);
            ParityHarness.assertSame(what, new Outcome(docIds(o.mongo()), docIds(o.pg()), o.mongoError(),
                    o.pgError()), false);
        }

        private static Object docIds(Object result) {
            if (!(result instanceof List<?> list)) {
                return result;
            }
            TreeSet<String> set = new TreeSet<>();
            for (Object o : list) {
                set.add(((Doc) o).uuid);
            }
            return new ArrayList<>(set);
        }

        private static IFilter search(String value) {
            return field("title", "$text", value);
        }

        @Test
        @DisplayName("a single word")
        void singleWord() {
            text("$text fox", texts(), search("fox"));
        }

        @Test
        @DisplayName("case-insensitive")
        void caseInsensitive() {
            text("$text FOX", texts(), search("FOX"));
        }

        @Test
        @DisplayName("several words match ANY of them")
        void severalWords() {
            text("$text coffee fox", texts(), search("coffee fox"));
        }

        @Test
        @DisplayName("several words, all present in one row")
        void severalWordsAllPresent() {
            text("$text brown fox", texts(), search("brown fox"));
        }

        @Test
        @DisplayName("a quoted phrase in order")
        void phraseInOrder() {
            text("$text \"brown fox\"", texts(), search("\"brown fox\""));
        }

        @Test
        @DisplayName("a quoted phrase out of order")
        void phraseOutOfOrder() {
            text("$text \"fox brown\"", texts(), search("\"fox brown\""));
        }

        @Test
        @DisplayName("a negated word")
        void negated() {
            text("$text tests -unit", texts(), search("tests -unit"));
        }

        @Test
        @DisplayName("stemming: run finds running")
        void stemRun() {
            text("$text run", texts(), search("run"));
        }

        @Test
        @DisplayName("stemming: running finds run")
        void stemRunning() {
            text("$text running", texts(), search("running"));
        }

        @Test
        @DisplayName("irregular form: ran")
        void ran() {
            text("$text ran", texts(), search("ran"));
        }

        @Test
        @DisplayName("a stop word alone")
        void stopWord() {
            text("$text the", texts(), search("the"));
        }

        @Test
        @DisplayName("unaccented query against an accented word")
        void accentsUnaccentedQuery() {
            text("$text cafe", texts(), search("cafe"));
        }

        @Test
        @DisplayName("accented query against an unaccented word")
        void accentsAccentedQuery() {
            text("$text café", texts(), search("café"));
        }

        @Test
        @DisplayName("a word of a nested POJO string field")
        void nestedField() {
            text("$text lyon", texts(), search("lyon"));
        }

        @Test
        @DisplayName("an unaccented word of an accented nested field")
        void nestedFieldAccent() {
            text("$text montreal", texts(), search("montreal"));
        }

        @Test
        @DisplayName("a word held only in a string array")
        void stringArray() {
            text("$text marathon", texts(), search("marathon"));
        }

        @Test
        @DisplayName("a word held only in the uuid")
        void uuidWord() {
            text("$text t3", texts(), search("t3"));
        }

        @Test
        @DisplayName("the field name of the filter is ignored")
        void fieldIgnored() {
            text("$text dog on field 'nosuchfield'", texts(), field("nosuchfield", "$text", "dog"));
        }

        @Test
        @DisplayName("punctuation around the word")
        void punctuation() {
            text("$text fox!", texts(), search("fox!"));
        }

        @Test
        @DisplayName("combined with a comparison in $and")
        void inAnd() {
            text("$text tests AND body=slow", texts(), logical("$and",
                    search("tests"), field("body", "$eq", "slow")));
        }

        @Test
        @DisplayName("inside $or with a non-text branch")
        void inOr() {
            text("$text fox OR body=slow", texts(), logical("$or",
                    search("fox"), field("body", "$eq", "slow")));
        }

        @Test
        @DisplayName("inside $nor")
        void inNor() {
            text("NOR($text fox, body=slow)", texts(), logical("$nor",
                    search("fox"), field("body", "$eq", "slow")));
        }

        @Test
        @DisplayName("two $text in one $and")
        void twoTexts() {
            text("$text fox AND $text dog", texts(), logical("$and", search("fox"), search("dog")));
        }

        @Test
        @DisplayName("count agrees with find")
        void count() {
            ParityHarness.assertSame("count $text coffee fox", texts().count("items", search("coffee fox")), false);
        }

        @Test
        @DisplayName("sorted and paged $text")
        void sortedPaged() {
            Outcome o = texts().find("items", Optional.of(ParityFilter.page(0, 2)), Optional.of(search("tests")),
                    Optional.of(ParityFilter.sort("uuid", com.garganttua.api.commons.sort.SortDirection.asc)),
                    Optional.empty());
            ParityHarness.assertSame("$text tests sorted by uuid, page 0 size 2",
                    new Outcome(docIds(o.mongo()), docIds(o.pg()), o.mongoError(), o.pgError()), false);
        }
    }
}
