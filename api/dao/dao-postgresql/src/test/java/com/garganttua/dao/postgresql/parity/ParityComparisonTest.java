package com.garganttua.dao.postgresql.parity;

import static com.garganttua.dao.postgresql.parity.ParityFilter.field;
import static com.garganttua.dao.postgresql.parity.ParityFilter.listed;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;

/**
 * Parity of the comparison operators ({@code $eq $ne $gt $gte $lt $lte $in $nin}) and of the TYPE of
 * the compared value, between the MongoDB DAO and the PostgreSQL DAO.
 *
 * <p>
 * MongoDB compares only values of the same BSON type class (numbers of any width together, strings,
 * booleans, dates): a value of another class never matches, and never fails. Filter values keep their
 * JSON type through the api, so a client sending {@code {"age":"18"}} hands the DAO the String
 * {@code "18"}. Each scenario seeds rows that must match, rows that must not, rows where the field is
 * null (MongoDB omits null fields: they are ABSENT from the document), and states what both engines
 * must answer.
 * </p>
 *
 * <p>
 * The seeded data is read-only, so one harness serves the whole class.
 * </p>
 */
@DisplayName("Parity — comparison operators and value types")
class ParityComparisonTest {

    public enum Color {
        RED, GREEN, BLUE
    }

    public static class Item {
        String uuid;
        String name;
        Integer age;
        Long big;
        Double score;
        Boolean active;
        Color color;
        Instant at;
        String ref;
        int rank;
    }

    /** BigDecimal lives in its own domain: see {@link Decimals}. */
    public static class Priced {
        String uuid;
        BigDecimal price;
    }

    private static final String ITEMS = "items";
    private static final String EMPTIES = "empties";
    private static final String PRICED = "priced";

    private static final String REF_A = "0b8f1c9e-0000-4000-8000-00000000000a";
    private static final String REF_B = "0b8f1c9e-0000-4000-8000-00000000000b";
    private static final String REF_C = "7f000000-0000-4000-8000-00000000000c";

    private static ParityHarness h;

    @BeforeAll
    static void seed() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
        h = ParityHarness.of(Domain.of(ITEMS, Item.class), Domain.of(EMPTIES, Item.class),
                Domain.of(PRICED, Priced.class));
        h.save(ITEMS,
                item("i1", "alice", 18, 18L, 18.0, true, Color.RED, "2024-01-01T00:00:00Z", REF_A, 18),
                item("i2", "Bob", 30, 5_000_000_000L, 18.5, false, Color.GREEN, "2024-06-01T12:00:00Z", REF_B, 30),
                item("i3", "18", 17, 17L, 17.9, true, Color.BLUE, "2023-12-31T23:59:59.999Z", REF_C, 17),
                item("i4", "true", null, null, null, null, null, null, null, 0),
                item("i5", null, 19, 19L, 19.0, false, Color.RED, "2025-01-01T00:00:00Z", REF_A, 19),
                item("i6", "Zed", 18, 20L, 30.25, true, Color.GREEN, "2024-01-01T00:00:00.001Z", REF_B, 18));
        h.save(PRICED, priced("p1", "10.50"), priced("p2", "10.5"), priced("p3", "9.99"), priced("p4", "100"),
                priced("p5", null));
    }

    private static Item item(String uuid, String name, Integer age, Long big, Double score, Boolean active,
            Color color, String at, String ref, int rank) {
        Item i = new Item();
        i.uuid = uuid;
        i.name = name;
        i.age = age;
        i.big = big;
        i.score = score;
        i.active = active;
        i.color = color;
        i.at = at == null ? null : Instant.parse(at);
        i.ref = ref;
        i.rank = rank;
        return i;
    }

    private static Priced priced(String uuid, String price) {
        Priced p = new Priced();
        p.uuid = uuid;
        p.price = price == null ? null : new BigDecimal(price);
        return p;
    }

    private static void same(String what, IFilter filter) {
        ParityHarness.assertSame(what, h.find(ITEMS, filter), false);
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("String field")
    class Strings {

        @Test
        @DisplayName("$eq with a String value")
        void eqString() {
            same("name $eq 'alice'", field("name", "$eq", "alice"));
        }

        @Test
        @DisplayName("$gt orders strings by code point (uppercase before lowercase)")
        void gtOrdering() {
            same("name $gt 'B'", field("name", "$gt", "B"));
        }

        @Test
        @DisplayName("$gt vs $gte on the exact bound")
        void boundary() {
            same("name $gt 'alice'", field("name", "$gt", "alice"));
            same("name $gte 'alice'", field("name", "$gte", "alice"));
        }

        @Test
        @DisplayName("$eq with an Integer never matches a string (\"18\" is stored)")
        void eqIntegerOnString() {
            same("name $eq 18 (Integer)", field("name", "$eq", 18));
        }

        @Test
        @DisplayName("$ne with an Integer matches every document, the string \"18\" included")
        void neIntegerOnString() {
            same("name $ne 18 (Integer)", field("name", "$ne", 18));
        }

        @Test
        @DisplayName("$eq with a Boolean never matches the string \"true\"")
        void eqBooleanOnString() {
            same("name $eq true (Boolean)", field("name", "$eq", Boolean.TRUE));
        }

        @Test
        @DisplayName("$gt with an Integer: no string is greater than a number")
        void gtIntegerOnString() {
            same("name $gt 0 (Integer)", field("name", "$gt", 0));
        }

        @Test
        @DisplayName("$in with a mixed-type list matches only the same-typed elements")
        void inMixed() {
            same("name $in ['alice', 18]", listed("name", "$in", "alice", 18));
        }

        @Test
        @DisplayName("$nin with null inside excludes the absent name")
        void ninWithNull() {
            same("name $nin ['alice', null]", listed("name", "$nin", "alice", null));
        }

        @Test
        @DisplayName("$in with null inside includes the absent name")
        void inWithNull() {
            same("name $in ['Bob', null]", listed("name", "$in", "Bob", null));
        }

        @Test
        @DisplayName("UUID-as-String: $eq, $gt and $in on a String holding UUIDs")
        void uuidAsString() {
            same("ref $eq REF_A", field("ref", "$eq", REF_A));
            same("ref $gt REF_A", field("ref", "$gt", REF_A));
            same("ref $in [REF_B, REF_C]", listed("ref", "$in", REF_B, REF_C));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Integer field")
    class Integers {

        @Test
        @DisplayName("$eq with an Integer")
        void eqInteger() {
            same("age $eq 18", field("age", "$eq", 18));
        }

        @Test
        @DisplayName("$gt vs $gte on the exact bound")
        void boundary() {
            same("age $gt 18", field("age", "$gt", 18));
            same("age $gte 18", field("age", "$gte", 18));
            same("age $lt 18", field("age", "$lt", 18));
            same("age $lte 18", field("age", "$lte", 18));
        }

        @Test
        @DisplayName("$eq with the String \"18\" matches nothing (string vs number)")
        void eqStringOnInteger() {
            same("age $eq '18' (String)", field("age", "$eq", "18"));
        }

        @Test
        @DisplayName("$gt with the String \"18\" matches nothing")
        void gtStringOnInteger() {
            same("age $gt '18' (String)", field("age", "$gt", "18"));
        }

        @Test
        @DisplayName("$ne with the String \"18\" matches every document")
        void neStringOnInteger() {
            same("age $ne '18' (String)", field("age", "$ne", "18"));
        }

        @Test
        @DisplayName("$eq with a non-numeric String matches nothing, without failing")
        void eqGarbageOnInteger() {
            same("age $eq 'abc' (String)", field("age", "$eq", "abc"));
        }

        @Test
        @DisplayName("$eq with a Long widens")
        void eqLongOnInteger() {
            same("age $eq 18L", field("age", "$eq", 18L));
        }

        @Test
        @DisplayName("$eq with 18.0 (Double) matches 18")
        void eqWholeDoubleOnInteger() {
            same("age $eq 18.0", field("age", "$eq", 18.0));
        }

        @Test
        @DisplayName("$eq with 18.5 (Double) matches nothing")
        void eqFractionOnInteger() {
            same("age $eq 18.5", field("age", "$eq", 18.5));
        }

        @Test
        @DisplayName("$gte 17.5 excludes 17")
        void gteFractionOnInteger() {
            same("age $gte 17.5", field("age", "$gte", 17.5));
        }

        @Test
        @DisplayName("$lt 18.9 includes 18")
        void ltFractionOnInteger() {
            same("age $lt 18.9", field("age", "$lt", 18.9));
        }

        @Test
        @DisplayName("$eq with a Long beyond the int range matches nothing (no truncation)")
        void eqOverflowOnInteger() {
            same("age $eq 4294967314L (2^32 + 18)", field("age", "$eq", 4_294_967_314L));
        }

        @Test
        @DisplayName("$lt with a Long beyond the int range matches every present age")
        void ltOverflowOnInteger() {
            same("age $lt 4294967312L (2^32 + 16)", field("age", "$lt", 4_294_967_312L));
        }

        @Test
        @DisplayName("$in with [18, \"30\"] matches only 18")
        void inMixedOnInteger() {
            same("age $in [18, '30']", listed("age", "$in", 18, "30"));
        }

        @Test
        @DisplayName("$in with [18, null] matches 18 and the absent age")
        void inNullOnInteger() {
            same("age $in [18, null]", listed("age", "$in", 18, null));
        }

        @Test
        @DisplayName("$nin with [18, null] excludes 18 and the absent age")
        void ninNullOnInteger() {
            same("age $nin [18, null]", listed("age", "$nin", 18, null));
        }

        @Test
        @DisplayName("$in with a non-numeric String among numbers matches the numbers, without failing")
        void inGarbageOnInteger() {
            same("age $in ['abc', 18]", listed("age", "$in", "abc", 18));
        }

        @Test
        @DisplayName("$eq with a Boolean matches nothing, without failing")
        void eqBooleanOnInteger() {
            same("age $eq true (Boolean)", field("age", "$eq", Boolean.TRUE));
        }

        @Test
        @DisplayName("$nin with [\"18\"] excludes nothing")
        void ninStringOnInteger() {
            same("age $nin ['18']", listed("age", "$nin", "18"));
        }

        @Test
        @DisplayName("$nin with [17, 30] keeps the absent age")
        void ninKeepsAbsent() {
            same("age $nin [17, 30]", listed("age", "$nin", 17, 30));
        }

        @Test
        @DisplayName("a primitive int (always stored) against a Double bound")
        void primitiveInt() {
            same("rank $gt 17.5", field("rank", "$gt", 17.5));
            same("rank $eq 0", field("rank", "$eq", 0));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("null as the compared value")
    class NullValue {

        @Test
        @DisplayName("$eq null matches the absent field only")
        void eqNull() {
            same("age $eq null", field("age", "$eq", null));
        }

        @Test
        @DisplayName("$ne null matches the present field only")
        void neNull() {
            same("age $ne null", field("age", "$ne", null));
        }

        @Test
        @DisplayName("$gt / $lt null match nothing")
        void strictNull() {
            same("age $gt null", field("age", "$gt", null));
            same("age $lt null", field("age", "$lt", null));
        }

        @Test
        @DisplayName("$gte / $lte null match the absent field")
        void inclusiveNull() {
            same("age $gte null", field("age", "$gte", null));
            same("age $lte null", field("age", "$lte", null));
        }

        @Test
        @DisplayName("null on a String and on an Instant field")
        void nullOnOtherTypes() {
            same("name $eq null", field("name", "$eq", null));
            same("at $ne null", field("at", "$ne", null));
            same("active $gte null", field("active", "$gte", null));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Long field")
    class Longs {

        @Test
        @DisplayName("$eq with an Integer widens")
        void eqIntegerOnLong() {
            same("big $eq 18 (Integer)", field("big", "$eq", 18));
        }

        @Test
        @DisplayName("$gt beyond the int range")
        void gtBig() {
            same("big $gt 4000000000L", field("big", "$gt", 4_000_000_000L));
        }

        @Test
        @DisplayName("$eq with the String \"5000000000\" matches nothing")
        void eqStringOnLong() {
            same("big $eq '5000000000'", field("big", "$eq", "5000000000"));
        }

        @Test
        @DisplayName("$gte 18.5 excludes 18")
        void gteFractionOnLong() {
            same("big $gte 18.5", field("big", "$gte", 18.5));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Double field")
    class Doubles {

        @Test
        @DisplayName("$eq with an Integer matches 18.0")
        void eqIntegerOnDouble() {
            same("score $eq 18 (Integer)", field("score", "$eq", 18));
        }

        @Test
        @DisplayName("$gt with an Integer bound")
        void gtIntegerOnDouble() {
            same("score $gt 18 (Integer)", field("score", "$gt", 18));
        }

        @Test
        @DisplayName("$lt with a Long bound")
        void ltLongOnDouble() {
            same("score $lt 18L", field("score", "$lt", 18L));
        }

        @Test
        @DisplayName("$eq with the String \"18.5\" matches nothing")
        void eqStringOnDouble() {
            same("score $eq '18.5'", field("score", "$eq", "18.5"));
        }

        @Test
        @DisplayName("$in with [18, 18.5] mixes widths")
        void inWidthsOnDouble() {
            same("score $in [18, 18.5]", listed("score", "$in", 18, 18.5));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Boolean field")
    class Booleans {

        @Test
        @DisplayName("$eq true")
        void eqTrue() {
            same("active $eq true", field("active", "$eq", Boolean.TRUE));
        }

        @Test
        @DisplayName("$ne false keeps true and the absent flag")
        void neFalse() {
            same("active $ne false", field("active", "$ne", Boolean.FALSE));
        }

        @Test
        @DisplayName("$gt false: true orders after false")
        void gtFalse() {
            same("active $gt false", field("active", "$gt", Boolean.FALSE));
        }

        @Test
        @DisplayName("$eq with the String \"true\" matches nothing")
        void eqStringTrue() {
            same("active $eq 'true' (String)", field("active", "$eq", "true"));
        }

        @Test
        @DisplayName("$eq with the Integer 1 matches nothing")
        void eqOne() {
            same("active $eq 1 (Integer)", field("active", "$eq", 1));
        }

        @Test
        @DisplayName("$ne with the String \"true\" matches every document")
        void neStringTrue() {
            same("active $ne 'true' (String)", field("active", "$ne", "true"));
        }

        @Test
        @DisplayName("$eq with a non-boolean String matches nothing")
        void eqGarbage() {
            same("active $eq 'yes' (String)", field("active", "$eq", "yes"));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Enum field (filter value as String)")
    class Enums {

        @Test
        @DisplayName("$eq with the constant's name")
        void eqName() {
            same("color $eq 'RED'", field("color", "$eq", "RED"));
        }

        @Test
        @DisplayName("$in / $nin with names, $nin keeping the absent color")
        void inNin() {
            same("color $in ['RED', 'BLUE']", listed("color", "$in", "RED", "BLUE"));
            same("color $nin ['RED']", listed("color", "$nin", "RED"));
        }

        @Test
        @DisplayName("$gt compares names lexically, not by ordinal")
        void gtName() {
            same("color $gt 'GREEN'", field("color", "$gt", "GREEN"));
        }

        @Test
        @DisplayName("$ne with an ordinal (Integer) matches every document")
        void neOrdinal() {
            same("color $ne 0 (Integer)", field("color", "$ne", 0));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Instant field")
    class Instants {

        @Test
        @DisplayName("$eq with an Instant object")
        void eqInstant() {
            same("at $eq Instant", field("at", "$eq", Instant.parse("2024-01-01T00:00:00Z")));
        }

        @Test
        @DisplayName("$gt with an Instant object, on the bound (1 ms apart)")
        void gtInstant() {
            same("at $gt Instant", field("at", "$gt", Instant.parse("2024-01-01T00:00:00Z")));
            same("at $gte Instant", field("at", "$gte", Instant.parse("2024-01-01T00:00:00Z")));
        }

        @Test
        @DisplayName("$eq with an ISO String")
        void eqIsoString() {
            same("at $eq '2024-01-01T00:00:00Z'", field("at", "$eq", "2024-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("$gt with an ISO String")
        void gtIsoString() {
            same("at $gt '2024-01-01T00:00:00Z'", field("at", "$gt", "2024-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("$eq with epoch millis (Long)")
        void eqMillis() {
            same("at $eq 1704067200000L", field("at", "$eq", 1_704_067_200_000L));
        }

        @Test
        @DisplayName("$eq with a date-only String")
        void eqDateOnly() {
            same("at $eq '2024-01-01'", field("at", "$eq", "2024-01-01"));
        }

        @Test
        @DisplayName("$in with ISO Strings")
        void inIsoStrings() {
            same("at $in ['2024-01-01T00:00:00Z', '2025-01-01T00:00:00Z']",
                    listed("at", "$in", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("$ne with an ISO String")
        void neIsoString() {
            same("at $ne '2024-01-01T00:00:00Z'", field("at", "$ne", "2024-01-01T00:00:00Z"));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("BigDecimal field")
    class Decimals {

        private void count(String what, IFilter filter) {
            ParityHarness.assertSame(what, h.count(PRICED, filter), false);
        }

        @Test
        @DisplayName("reads a BigDecimal back")
        void readBack() {
            ParityHarness.assertSame("find all priced", h.find(PRICED, null), false);
        }

        @Test
        @DisplayName("$eq with a BigDecimal ignores the scale (10.5 = 10.50)")
        void eqDecimal() {
            count("price $eq 10.5 (BigDecimal)", field("price", "$eq", new BigDecimal("10.5")));
        }

        @Test
        @DisplayName("$eq with a Double")
        void eqDouble() {
            count("price $eq 10.5 (Double)", field("price", "$eq", 10.5));
        }

        @Test
        @DisplayName("$gt with an Integer")
        void gtInteger() {
            count("price $gt 10 (Integer)", field("price", "$gt", 10));
        }

        @Test
        @DisplayName("$eq with the String \"10.5\" matches nothing")
        void eqStringOnDecimal() {
            count("price $eq '10.5' (String)", field("price", "$eq", "10.5"));
        }

        @Test
        @DisplayName("$lte with a String bound matches nothing")
        void lteString() {
            count("price $lte '100' (String)", field("price", "$lte", "100"));
        }

        @Test
        @DisplayName("$nin with null inside")
        void ninNull() {
            count("price $nin [9.99, null]", listed("price", "$nin", new BigDecimal("9.99"), null));
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Empty collection")
    class Empty {

        @Test
        @DisplayName("$ne / $nin / $gte null on an empty collection")
        void emptyCollection() {
            ParityHarness.assertSame("empty $ne 18", h.find(EMPTIES, field("age", "$ne", 18)), false);
            ParityHarness.assertSame("empty $nin [null]", h.find(EMPTIES, listed("age", "$nin", (Object) null)), false);
            ParityHarness.assertSame("empty count $gte null", h.count(EMPTIES, field("age", "$gte", null)), false);
        }

        @Test
        @DisplayName("a non-numeric String against a number field on an empty collection")
        void emptyCollectionGarbage() {
            ParityHarness.assertSame("empty age $eq 'abc'", h.find(EMPTIES, field("age", "$eq", "abc")), false);
        }
    }
}
