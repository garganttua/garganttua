package com.garganttua.dao.postgresql.parity;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Round-trip fidelity and compositions: what is saved on MongoDB and on PostgreSQL must come back
 * IDENTICAL — same values, same Java types, same null/empty distinctions, same reference resolution.
 *
 * <p>
 * The harness' canonical JSON comparison hides differences a caller would feel ({@code 1.50} vs
 * {@code 1.5}, a {@code Long} vs an {@code Integer} in an {@code Object} field, {@code String} map keys
 * vs {@code Integer} keys). Every scenario therefore compares a TYPED deep description of each
 * returned DTO (see {@link #describe}), handed to {@link ParityHarness#assertSame} as the outcome.
 * </p>
 *
 * <p>
 * This is a specification: every test must pass once the PostgreSQL DAO is iso-functional. Today,
 * the failing ones are the divergences found. Note that the MongoDB DAO OMITS null fields on write
 * ({@code MongoDocumentWriter#writeField}): a null field is absent from the document, never stored
 * as a BSON null.
 * </p>
 */
@DisplayName("Parity — round-trip fidelity and compositions")
class ParityTypesCompositionTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ------------------------------------------------------------------ DTOs

    public enum Status {
        ACTIVE, SUSPENDED, CLOSED
    }

    public static class Text {
        String uuid;
        String value;
    }

    public static class Chars {
        String uuid;
        char c;
        Character boxed;
    }

    public static class Smalls {
        String uuid;
        byte b;
        short s;
        Byte boxedB;
        Short boxedS;
    }

    public static class Wholes {
        String uuid;
        int i;
        long l;
        Integer boxedI;
        Long boxedL;
    }

    public static class Doubles {
        String uuid;
        double d;
        Double boxed;
    }

    public static class Floats {
        String uuid;
        float f;
        Float boxed;
    }

    public static class Bools {
        String uuid;
        boolean b;
        Boolean boxed;
    }

    public static class Decimals {
        String uuid;
        BigDecimal amount;
    }

    public static class BigInts {
        String uuid;
        BigInteger big;
    }

    public static class Instants {
        String uuid;
        Instant at;
    }

    public static class Dates {
        String uuid;
        java.util.Date at;
    }

    public static class LocalDates {
        String uuid;
        LocalDate day;
    }

    public static class LocalDateTimes {
        String uuid;
        LocalDateTime at;
    }

    public static class LocalTimes {
        String uuid;
        LocalTime at;
    }

    public static class Uuids {
        String uuid;
        UUID ref;
    }

    public static class Blobs {
        String uuid;
        byte[] data;
    }

    public static class Enums {
        String uuid;
        Status status;
    }

    public static class Lists {
        String uuid;
        List<String> tags;
    }

    public static class Sets {
        String uuid;
        Set<String> tags;
    }

    public static class EnumKeyMaps {
        String uuid;
        Map<Status, Integer> counts;
    }

    public static class IntKeyMaps {
        String uuid;
        Map<Integer, String> labels;
    }

    public static class StringMaps {
        String uuid;
        Map<String, String> entries;
    }

    public static class Nested2 {
        String uuid;
        List<List<String>> grid;
    }

    public static class Untyped {
        String uuid;
        Object any;
    }

    public static class Node {
        String label;
        Node next;
    }

    public static class Chain {
        String uuid;
        Node head;
    }

    public static class Address {
        String city;
        String zip;
    }

    public static class Person {
        String uuid;
        String name;
        Address address;
    }

    public static class People {
        String uuid;
        List<Address> addresses;
    }

    public static class Customer {
        String uuid;
        String name;
    }

    public static class Order {
        String uuid;
        String label;
        Customer customer;
    }

    public static class Basket {
        String uuid;
        List<Customer> customers;
    }

    public static class RefByString {
        String uuid;
        String customer;
    }

    public static class Street {
        String uuid;
        String name;
    }

    public static class Resident {
        String uuid;
        String name;
        Street street;
    }

    public static class Letter {
        String uuid;
        Resident to;
    }

    // ------------------------------------------------------------------ helpers

    private static final String PARITY_PACKAGE = ParityTypesCompositionTest.class.getPackageName();

    /** Every row of a domain, sorted by uuid — the order is part of the answer. */
    /** The value of an {@link Untyped}'s Object field. */
    private static Object valueOf(Object untyped) {
        return ((Untyped) untyped).any;
    }

    private static Outcome all(ParityHarness h, String domain) {
        return typed(h.find(domain, Optional.empty(), Optional.empty(),
                Optional.of(ParityFilter.sort("uuid", SortDirection.asc)), Optional.empty()));
    }

    /** Both answers replaced by their typed deep description; errors kept as they are. */
    private static Outcome typed(Outcome o) {
        return new Outcome(describeResult(o.mongo()), describeResult(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object describeResult(Object result) {
        if (result instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(describe(item));
            }
            return out;
        }
        return result == null ? null : describe(result);
    }

    /**
     * A deep description of a value that keeps what a caller would see: the runtime type of every
     * scalar, the scale of a BigDecimal, the sign of a zero, the type of each map key, null vs empty.
     */
    static String describe(Object value) {
        return describe(value, new IdentityHashMap<>());
    }

    @SuppressWarnings("PMD.CognitiveComplexity")
    private static String describe(Object v, IdentityHashMap<Object, Boolean> seen) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (v instanceof Character c) {
            return "char'" + escape(String.valueOf(c)) + "'";
        }
        if (v instanceof Enum<?> e) {
            return "enum:" + e.name();
        }
        if (v instanceof BigDecimal d) {
            return "BigDecimal:" + d.toString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.getClass().getSimpleName() + ":" + v;
        }
        if (v instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]#" + Arrays.hashCode(bytes);
        }
        if (v instanceof java.util.Date date) {
            return "Date:" + date.getTime();
        }
        if (v instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(describe(entry.getKey(), seen) + "=" + describe(entry.getValue(), seen));
            }
            entries.sort(String::compareTo);
            return "Map" + entries;
        }
        if (v instanceof Collection<?> collection) {
            StringJoiner items = new StringJoiner(", ", (v instanceof Set ? "Set" : "List") + "[", "]");
            for (Object item : collection) {
                items.add(describe(item, seen));
            }
            return items.toString();
        }
        if (v.getClass().isArray()) {
            return "Array" + v.getClass().getComponentType().getSimpleName();
        }
        if (!v.getClass().getName().startsWith(PARITY_PACKAGE)) {
            return v.getClass().getSimpleName() + ":" + v;
        }
        return describePojo(v, seen);
    }

    private static String describePojo(Object v, IdentityHashMap<Object, Boolean> seen) {
        if (seen.containsKey(v)) {
            return "<cycle>";
        }
        seen.put(v, Boolean.TRUE);
        StringJoiner fields = new StringJoiner(", ", v.getClass().getSimpleName() + "{", "}");
        IClass<?> current = IClass.getClass(v.getClass());
        while (current != null && !current.represents(Object.class)) {
            for (IField field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    fields.add(field.getName() + "=" + describe(field.get(v), seen));
                } catch (IllegalAccessException e) {
                    fields.add(field.getName() + "=<unreadable>");
                }
            }
            current = current.getSuperclass();
        }
        seen.remove(v);
        return fields.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp > 0x7e) {
                out.append(String.format("\\u{%x}", cp));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    private static <T> T with(T dto, java.util.function.Consumer<T> init) {
        init.accept(dto);
        return dto;
    }

    private static Text text(String uuid, String value) {
        return with(new Text(), t -> {
            t.uuid = uuid;
            t.value = value;
        });
    }

    private static Customer customer(String uuid, String name) {
        return with(new Customer(), c -> {
            c.uuid = uuid;
            c.name = name;
        });
    }

    private static Order order(String uuid, String label, Customer customer) {
        return with(new Order(), o -> {
            o.uuid = uuid;
            o.label = label;
            o.customer = customer;
        });
    }

    private static Basket basket(String uuid, List<Customer> customers) {
        return with(new Basket(), b -> {
            b.uuid = uuid;
            b.customers = customers;
        });
    }

    private static ParityHarness ordersAndCustomers() {
        return ParityHarness.of(new Domain("orders", Order.class, Map.of("customer", "customers")),
                Domain.of("customers", Customer.class));
    }

    private static ParityHarness basketsAndCustomers() {
        return ParityHarness.of(new Domain("baskets", Basket.class, Map.of("customers", "customers")),
                Domain.of("customers", Customer.class));
    }

    // ------------------------------------------------------------------ strings and characters

    @Nested
    @DisplayName("Strings and characters")
    class Strings {

        @Test
        @DisplayName("unicode, emoji and plain ASCII strings come back byte for byte")
        void unicodeAndEmoji() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text("t1", "Crème brûlée — ÅΩ 漢字"), text("t2", "🚀👩‍👩‍👧 zero​width"),
                    text("t3", "plain"), text("t4", "line\nbreak\ttab"));
            ParityHarness.assertSame("unicode/emoji round-trip", all(h, "texts"), true);
        }

        @Test
        @DisplayName("an empty string stays empty, a null string stays null")
        void emptyVersusNull() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text("t1", ""), text("t2", null), text("t3", " "));
            ParityHarness.assertSame("empty vs null string", all(h, "texts"), true);
        }

        @Test
        @DisplayName("an empty string and a null string are told apart by a filter on null")
        void emptyVersusNullFilter() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text("t1", ""), text("t2", null), text("t3", "x"));
            ParityHarness.assertSame("value = \"\"", typed(h.find("texts", ParityFilter.field("value", "$eq", ""))), false);
        }

        /**
         * DOCUMENTED RESIDUAL — not parity, and pinned so it cannot change unnoticed. PostgreSQL's
         * {@code TEXT} and {@code JSONB} cannot hold U+0000 at all. MongoDB stores it; PostgreSQL refuses
         * the save with an error naming the field. Escaping it would be faithful for storage only: every
         * regex, {@code $text}, sort and prefix comparison would then see the escape, not the character.
         */
        @Test
        @DisplayName("RESIDUAL: a NUL character is stored by MongoDB, refused by PostgreSQL with the field named")
        void nulCharacter() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            Outcome saved = h.saveEach("texts", text("t1", "before\u0000after"));
            org.junit.jupiter.api.Assertions.assertNull(saved.mongoError(), "MongoDB stores U+0000");
            org.junit.jupiter.api.Assertions.assertNotNull(saved.pgError(), "PostgreSQL cannot");
            org.junit.jupiter.api.Assertions.assertTrue(saved.pgError().getMessage().contains("NUL"),
                    () -> "the refusal must say why: " + saved.pgError().getMessage());
        }

        @Test
        @DisplayName("char and Character come back as the same character")
        void chars() {
            ParityHarness h = ParityHarness.of(Domain.of("chars", Chars.class));
            h.save("chars", with(new Chars(), c -> {
                c.uuid = "c1";
                c.c = 'x';
                c.boxed = 'é';
            }), with(new Chars(), c -> {
                c.uuid = "c2";
                c.c = '€';
                c.boxed = null;
            }));
            ParityHarness.assertSame("char round-trip", all(h, "chars"), true);
        }
    }

    // ------------------------------------------------------------------ numbers

    @Nested
    @DisplayName("Numbers")
    class Numbers {

        @Test
        @DisplayName("byte and short keep their bounds and their type")
        void bytesAndShorts() {
            ParityHarness h = ParityHarness.of(Domain.of("smalls", Smalls.class));
            h.save("smalls", with(new Smalls(), s -> {
                s.uuid = "s1";
                s.b = Byte.MIN_VALUE;
                s.s = Short.MAX_VALUE;
                s.boxedB = Byte.MAX_VALUE;
                s.boxedS = Short.MIN_VALUE;
            }), with(new Smalls(), s -> {
                s.uuid = "s2";
                s.b = 0;
                s.s = -1;
            }));
            ParityHarness.assertSame("byte/short round-trip", all(h, "smalls"), true);
        }

        @Test
        @DisplayName("int and long keep their min/max, primitives never set stay 0")
        void intsAndLongs() {
            ParityHarness h = ParityHarness.of(Domain.of("wholes", Wholes.class));
            h.save("wholes", with(new Wholes(), w -> {
                w.uuid = "w1";
                w.i = Integer.MIN_VALUE;
                w.l = Long.MAX_VALUE;
                w.boxedI = Integer.MAX_VALUE;
                w.boxedL = Long.MIN_VALUE;
            }), with(new Wholes(), w -> w.uuid = "w2"));
            ParityHarness.assertSame("int/long round-trip", all(h, "wholes"), true);
        }

        @Test
        @DisplayName("a long beyond 2^53 is found by an exact filter on both engines")
        void longPrecisionFilter() {
            ParityHarness h = ParityHarness.of(Domain.of("wholes", Wholes.class));
            h.save("wholes", with(new Wholes(), w -> {
                w.uuid = "w1";
                w.boxedL = 9_007_199_254_740_993L;
            }), with(new Wholes(), w -> {
                w.uuid = "w2";
                w.boxedL = 9_007_199_254_740_992L;
            }));
            ParityHarness.assertSame("boxedL = 2^53+1",
                    typed(h.find("wholes", ParityFilter.field("boxedL", "$eq", 9_007_199_254_740_993L))), false);
        }

        @Test
        @DisplayName("a long filter value sent as a JSON STRING (\"9007199254740993\") matches alike")
        void longFilterAsString() {
            ParityHarness h = ParityHarness.of(Domain.of("wholes", Wholes.class));
            h.save("wholes", with(new Wholes(), w -> {
                w.uuid = "w1";
                w.boxedL = 9_007_199_254_740_993L;
            }), with(new Wholes(), w -> {
                w.uuid = "w2";
                w.boxedL = 1L;
            }));
            ParityHarness.assertSame("boxedL = \"2^53+1\"",
                    typed(h.find("wholes", ParityFilter.field("boxedL", "$eq", "9007199254740993"))), false);
        }

        @Test
        @DisplayName("double NaN, infinities, -0.0 and subnormals survive")
        void doubleSpecials() {
            ParityHarness h = ParityHarness.of(Domain.of("doubles", Doubles.class));
            double[] specials = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0,
                    Double.MIN_VALUE, Double.MAX_VALUE, 0.1 + 0.2 };
            for (int i = 0; i < specials.length; i++) {
                double d = specials[i];
                String id = "d" + i;
                h.save("doubles", with(new Doubles(), x -> {
                    x.uuid = id;
                    x.d = d;
                    x.boxed = d;
                }));
            }
            h.save("doubles", with(new Doubles(), x -> x.uuid = "dz"));
            ParityHarness.assertSame("double specials", all(h, "doubles"), true);
        }

        @Test
        @DisplayName("float NaN, -0.0f, subnormal and infinity survive, as floats")
        void floatSpecials() {
            ParityHarness h = ParityHarness.of(Domain.of("floats", Floats.class));
            float[] specials = { Float.NaN, -0.0f, Float.MIN_VALUE, Float.POSITIVE_INFINITY, 3.1415927f };
            for (int i = 0; i < specials.length; i++) {
                float f = specials[i];
                String id = "f" + i;
                h.save("floats", with(new Floats(), x -> {
                    x.uuid = id;
                    x.f = f;
                    x.boxed = f;
                }));
            }
            ParityHarness.assertSame("float specials", all(h, "floats"), true);
        }

        @Test
        @DisplayName("boolean and Boolean: true, false, null")
        void booleans() {
            ParityHarness h = ParityHarness.of(Domain.of("bools", Bools.class));
            h.save("bools", with(new Bools(), b -> {
                b.uuid = "b1";
                b.b = true;
                b.boxed = false;
            }), with(new Bools(), b -> {
                b.uuid = "b2";
                b.boxed = true;
            }), with(new Bools(), b -> b.uuid = "b3"));
            ParityHarness.assertSame("booleans", all(h, "bools"), true);
        }

        @Test
        @DisplayName("BigDecimal keeps its scale (1.50 vs 1.500) and its type")
        void bigDecimalScale() {
            ParityHarness h = ParityHarness.of(Domain.of("decimals", Decimals.class));
            h.save("decimals", with(new Decimals(), d -> {
                d.uuid = "m1";
                d.amount = new BigDecimal("1.50");
            }), with(new Decimals(), d -> {
                d.uuid = "m2";
                d.amount = new BigDecimal("1.500");
            }), with(new Decimals(), d -> d.uuid = "m3"));
            ParityHarness.assertSame("BigDecimal scale", all(h, "decimals"), true);
        }

        @Test
        @DisplayName("a BigDecimal of 40 significant digits is stored (or refused) alike")
        void bigDecimalHuge() {
            ParityHarness h = ParityHarness.of(Domain.of("decimals", Decimals.class));
            h.save("decimals", with(new Decimals(), d -> {
                d.uuid = "m1";
                d.amount = new BigDecimal("1234567890123456789012345678901234567890.0123456789");
            }));
            ParityHarness.assertSame("huge BigDecimal", all(h, "decimals"), true);
        }

        @Test
        @DisplayName("a BigInteger of 2^100 is stored (or refused) alike")
        void bigIntegerHuge() {
            ParityHarness h = ParityHarness.of(Domain.of("bigints", BigInts.class));
            h.save("bigints", with(new BigInts(), b -> {
                b.uuid = "g1";
                b.big = BigInteger.TWO.pow(100);
            }));
            ParityHarness.assertSame("huge BigInteger", all(h, "bigints"), true);
        }
    }

    // ------------------------------------------------------------------ temporal and identity

    @Nested
    @DisplayName("Temporal values, UUID, byte[], enum")
    class Temporal {

        private static final Instant MILLIS = Instant.parse("2024-01-02T03:04:05.123Z");
        private static final Instant MICROS = Instant.parse("2024-01-02T03:04:05.123456Z");
        private static final Instant NANOS = Instant.parse("2024-01-02T03:04:05.123456789Z");

        private Instants instant(String uuid, Instant at) {
            return with(new Instants(), i -> {
                i.uuid = uuid;
                i.at = at;
            });
        }

        @Test
        @DisplayName("an Instant at millisecond precision comes back identical (control)")
        void instantMillis() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", MILLIS), instant("i2", null), instant("i3", Instant.EPOCH));
            ParityHarness.assertSame("Instant ms", all(h, "instants"), true);
        }

        @Test
        @DisplayName("an Instant with MICROsecond precision comes back with the same precision")
        void instantMicros() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", MICROS));
            ParityHarness.assertSame("Instant µs", all(h, "instants"), true);
        }

        @Test
        @DisplayName("an Instant with NANOsecond precision comes back with the same precision")
        void instantNanos() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", NANOS));
            ParityHarness.assertSame("Instant ns", all(h, "instants"), true);
        }

        @Test
        @DisplayName("a µs Instant is found by an $eq on its millisecond truncation on both engines, or on neither")
        void instantEqualityAtMillis() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", MICROS), instant("i2", MICROS.plusSeconds(1)));
            ParityHarness.assertSame("at = truncatedTo(ms)", typed(h.find("instants",
                    ParityFilter.field("at", "$eq", MICROS.truncatedTo(ChronoUnit.MILLIS)))), false);
        }

        @Test
        @DisplayName("a µs Instant is found by an $eq on its own full value on both engines")
        void instantEqualityExact() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", MICROS), instant("i2", MICROS.plusSeconds(1)));
            ParityHarness.assertSame("at = MICROS",
                    typed(h.find("instants", ParityFilter.field("at", "$eq", MICROS))), false);
        }

        @Test
        @DisplayName("an Instant filter value sent as an ISO STRING matches alike")
        void instantFilterAsString() {
            ParityHarness h = ParityHarness.of(Domain.of("instants", Instants.class));
            h.save("instants", instant("i1", MILLIS), instant("i2", MILLIS.plusSeconds(1)));
            ParityHarness.assertSame("at = \"2024-01-02T03:04:05.123Z\"",
                    typed(h.find("instants", ParityFilter.field("at", "$eq", MILLIS.toString()))), false);
        }

        @Test
        @DisplayName("java.util.Date comes back as the same Date")
        void utilDate() {
            ParityHarness h = ParityHarness.of(Domain.of("dates", Dates.class));
            h.save("dates", with(new Dates(), d -> {
                d.uuid = "d1";
                d.at = new java.util.Date(1_704_164_645_123L);
            }), with(new Dates(), d -> d.uuid = "d2"));
            ParityHarness.assertSame("java.util.Date", all(h, "dates"), true);
        }

        @Test
        @DisplayName("LocalDate comes back as the same day (no zone shift)")
        void localDate() {
            ParityHarness h = ParityHarness.of(Domain.of("days", LocalDates.class));
            h.save("days", with(new LocalDates(), d -> {
                d.uuid = "d1";
                d.day = LocalDate.of(2024, 2, 29);
            }), with(new LocalDates(), d -> {
                d.uuid = "d2";
                d.day = LocalDate.of(1, 1, 1);
            }));
            ParityHarness.assertSame("LocalDate", all(h, "days"), true);
        }

        @Test
        @DisplayName("LocalDateTime with microseconds comes back with the same precision")
        void localDateTime() {
            ParityHarness h = ParityHarness.of(Domain.of("stamps", LocalDateTimes.class));
            h.save("stamps", with(new LocalDateTimes(), d -> {
                d.uuid = "d1";
                d.at = LocalDateTime.of(2024, 1, 2, 3, 4, 5, 123_456_000);
            }), with(new LocalDateTimes(), d -> {
                d.uuid = "d2";
                d.at = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
            }));
            ParityHarness.assertSame("LocalDateTime µs", all(h, "stamps"), true);
        }

        @Test
        @DisplayName("LocalTime comes back as the same time of day")
        void localTime() {
            ParityHarness h = ParityHarness.of(Domain.of("times", LocalTimes.class));
            h.save("times", with(new LocalTimes(), d -> {
                d.uuid = "t1";
                d.at = LocalTime.of(23, 59, 58);
            }));
            ParityHarness.assertSame("LocalTime", all(h, "times"), true);
        }

        @Test
        @DisplayName("a java.util.UUID field is stored (or refused) alike and comes back a UUID")
        void uuidField() {
            ParityHarness h = ParityHarness.of(Domain.of("uuids", Uuids.class));
            h.save("uuids", with(new Uuids(), u -> {
                u.uuid = "u1";
                u.ref = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            }));
            ParityHarness.assertSame("UUID field", all(h, "uuids"), true);
        }

        @Test
        @DisplayName("byte[]: empty, 256 KiB and null")
        void bytes() {
            ParityHarness h = ParityHarness.of(Domain.of("blobs", Blobs.class));
            byte[] large = new byte[256 * 1024];
            for (int i = 0; i < large.length; i++) {
                large[i] = (byte) (i * 31);
            }
            h.save("blobs", with(new Blobs(), b -> {
                b.uuid = "b1";
                b.data = new byte[0];
            }), with(new Blobs(), b -> {
                b.uuid = "b2";
                b.data = large;
            }), with(new Blobs(), b -> b.uuid = "b3"));
            ParityHarness.assertSame("byte[]", all(h, "blobs"), true);
        }

        @Test
        @DisplayName("an enum comes back as the same constant; a null enum stays null")
        void enums() {
            ParityHarness h = ParityHarness.of(Domain.of("enums", Enums.class));
            h.save("enums", with(new Enums(), e -> {
                e.uuid = "e1";
                e.status = Status.SUSPENDED;
            }), with(new Enums(), e -> e.uuid = "e2"));
            ParityHarness.assertSame("enum", all(h, "enums"), true);
        }
    }

    @Nested
    @DisplayName("Enum filters, typed and as text")
    class EnumFilters {

        private ParityHarness seeded() {
            ParityHarness h = ParityHarness.of(Domain.of("enums", Enums.class));
            h.save("enums", with(new Enums(), e -> {
                e.uuid = "e1";
                e.status = Status.SUSPENDED;
            }), with(new Enums(), e -> {
                e.uuid = "e2";
                e.status = Status.ACTIVE;
            }), with(new Enums(), e -> e.uuid = "e3"));
            return h;
        }

        @Test
        @DisplayName("an enum filter value passed as the enum constant matches alike")
        void enumConstant() {
            ParityHarness.assertSame("status = SUSPENDED (enum)",
                    typed(seeded().find("enums", ParityFilter.field("status", "$eq", Status.SUSPENDED))), false);
        }

        @Test
        @DisplayName("an enum filter value passed as its name (JSON string) matches alike")
        void enumName() {
            ParityHarness.assertSame("status = \"SUSPENDED\"",
                    typed(seeded().find("enums", ParityFilter.field("status", "$eq", "SUSPENDED"))), false);
        }
    }

    // ------------------------------------------------------------------ collections and maps

    @Nested
    @DisplayName("Collections and maps")
    class Collections {

        private Lists list(String uuid, List<String> tags) {
            return with(new Lists(), l -> {
                l.uuid = uuid;
                l.tags = tags;
            });
        }

        @Test
        @DisplayName("a null list reads back null, an empty list reads back empty")
        void nullVersusEmptyList() {
            ParityHarness h = ParityHarness.of(Domain.of("lists", Lists.class));
            h.save("lists", list("l1", null), list("l2", new ArrayList<>()), list("l3", List.of("a", "b")));
            ParityHarness.assertSame("null vs empty list", all(h, "lists"), true);
        }

        @Test
        @DisplayName("a list keeps its order and its duplicates")
        void listOrder() {
            ParityHarness h = ParityHarness.of(Domain.of("lists", Lists.class));
            h.save("lists", list("l1", List.of("z", "a", "m", "a")));
            ParityHarness.assertSame("list order", all(h, "lists"), true);
        }

        @Test
        @DisplayName("a list with null elements keeps them, in place")
        void nullElements() {
            ParityHarness h = ParityHarness.of(Domain.of("lists", Lists.class));
            h.save("lists", list("l1", Arrays.asList("a", null, "b")));
            ParityHarness.assertSame("null list elements", all(h, "lists"), true);
        }

        @Test
        @DisplayName("a Set<String> round-trips, keeping its insertion order")
        void setOrder() {
            ParityHarness h = ParityHarness.of(Domain.of("sets", Sets.class));
            h.save("sets", with(new Sets(), s -> {
                s.uuid = "s1";
                s.tags = new LinkedHashSet<>(List.of("c", "a", "b"));
            }));
            ParityHarness.assertSame("Set round-trip", all(h, "sets"), true);
        }

        @Test
        @DisplayName("a Map with ENUM keys comes back with enum keys")
        void enumKeyedMap() {
            ParityHarness h = ParityHarness.of(Domain.of("ekm", EnumKeyMaps.class));
            h.save("ekm", with(new EnumKeyMaps(), m -> {
                m.uuid = "m1";
                m.counts = new LinkedHashMap<>(Map.of(Status.ACTIVE, 3, Status.CLOSED, 0));
            }));
            ParityHarness.assertSame("Map<enum,Integer>", all(h, "ekm"), true);
        }

        @Test
        @DisplayName("a Map with INTEGER keys comes back with Integer keys")
        void integerKeyedMap() {
            ParityHarness h = ParityHarness.of(Domain.of("ikm", IntKeyMaps.class));
            h.save("ikm", with(new IntKeyMaps(), m -> {
                m.uuid = "m1";
                m.labels = new LinkedHashMap<>(Map.of(1, "one", -20, "minus twenty"));
            }));
            ParityHarness.assertSame("Map<Integer,String>", all(h, "ikm"), true);
        }

        @Test
        @DisplayName("a Map whose keys contain dots and dollars is stored (or refused) alike")
        void dottedKeys() {
            ParityHarness h = ParityHarness.of(Domain.of("smaps", StringMaps.class));
            h.save("smaps", with(new StringMaps(), m -> {
                m.uuid = "m1";
                m.entries = new LinkedHashMap<>(Map.of("a.b", "x", "c.d.e", "y", "", "empty key"));
            }));
            ParityHarness.assertSame("dotted map keys", all(h, "smaps"), true);
        }

        @Test
        @DisplayName("a Map with a null value is stored (or refused) alike")
        void nullMapValue() {
            ParityHarness h = ParityHarness.of(Domain.of("smaps", StringMaps.class));
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("k", null);
            entries.put("v", "value");
            h.save("smaps", with(new StringMaps(), m -> {
                m.uuid = "m1";
                m.entries = entries;
            }));
            ParityHarness.assertSame("null map value", all(h, "smaps"), true);
        }

        @Test
        @DisplayName("a null map reads back null, an empty map reads back empty")
        void nullVersusEmptyMap() {
            ParityHarness h = ParityHarness.of(Domain.of("smaps", StringMaps.class));
            h.save("smaps", with(new StringMaps(), m -> m.uuid = "m1"), with(new StringMaps(), m -> {
                m.uuid = "m2";
                m.entries = new LinkedHashMap<>();
            }), with(new StringMaps(), m -> {
                m.uuid = "m3";
                m.entries = new LinkedHashMap<>(Map.of("k", "v"));
            }));
            ParityHarness.assertSame("null vs empty map", all(h, "smaps"), true);
        }

        @Test
        @DisplayName("a List<List<String>> keeps its shape, its empty inner lists and its order")
        void nestedLists() {
            ParityHarness h = ParityHarness.of(Domain.of("grids", Nested2.class));
            h.save("grids", with(new Nested2(), n -> {
                n.uuid = "g1";
                n.grid = List.of(List.of("a", "b"), List.of(), List.of("c"));
            }), with(new Nested2(), n -> n.uuid = "g2"));
            ParityHarness.assertSame("List<List<String>>", all(h, "grids"), true);
        }
    }

    // ------------------------------------------------------------------ untyped and embedded objects

    @Nested
    @DisplayName("Object fields and embedded POJOs")
    class Embedded {

        private Untyped untyped(String uuid, Object any) {
            return with(new Untyped(), u -> {
                u.uuid = uuid;
                u.any = any;
            });
        }

        @Test
        @DisplayName("an Object field holding a Map comes back as a Map")
        void objectHoldingMap() {
            ParityHarness h = ParityHarness.of(Domain.of("untyped", Untyped.class));
            h.save("untyped", untyped("u1", new LinkedHashMap<>(Map.of("k", "v", "n", 2))));
            ParityHarness.assertSame("Object = Map", all(h, "untyped"), true);
        }

        @Test
        @DisplayName("an Object field holding a List comes back as a List")
        void objectHoldingList() {
            ParityHarness h = ParityHarness.of(Domain.of("untyped", Untyped.class));
            h.save("untyped", untyped("u1", new ArrayList<>(List.of("a", 1, true))));
            ParityHarness.assertSame("Object = List", all(h, "untyped"), true);
        }

        @Test
        @DisplayName("an Object field holding a String or an Integer keeps value and type")
        void objectHoldingStringOrInteger() {
            ParityHarness h = ParityHarness.of(Domain.of("untyped", Untyped.class));
            h.save("untyped", untyped("u1", "text"), untyped("u2", 42), untyped("u3", null));
            ParityHarness.assertSame("Object = String/Integer", all(h, "untyped"), true);
        }

        /**
         * DOCUMENTED RESIDUAL — pinned. An {@code Object}-typed field is stored as {@code JSONB}, and JSON has
         * one number type: a Long that fits an int comes back an Integer, where BSON's int64 tag keeps it a
         * Long. Tagging the type inside the JSONB would restore it, but every filter on that column would
         * then compare against the tag instead of the number — one divergence traded for a worse one.
         * Typed fields ({@code long}, {@code Long}) are unaffected: their column is {@code BIGINT}.
         */
        @Test
        @DisplayName("RESIDUAL: an untyped Object field holding a small Long reads back as Integer on PostgreSQL")
        void objectHoldingLong() {
            ParityHarness h = ParityHarness.of(Domain.of("untyped", Untyped.class));
            h.save("untyped", untyped("u1", 5L));
            Outcome read = h.find("untyped", null);
            Object pg = ((java.util.List<?>) read.pg()).get(0);
            Object mongo = ((java.util.List<?>) read.mongo()).get(0);
            org.junit.jupiter.api.Assertions.assertEquals(Long.class, valueOf(mongo).getClass(), "MongoDB keeps int64");
            org.junit.jupiter.api.Assertions.assertEquals(Integer.class, valueOf(pg).getClass(),
                    "PostgreSQL's JSONB has one number type — if this starts failing, the residual is gone: turn it back into a parity test");
        }

        @Test
        @DisplayName("an Object field holding a Double comes back a Double")
        void objectHoldingDouble() {
            ParityHarness h = ParityHarness.of(Domain.of("untyped", Untyped.class));
            h.save("untyped", untyped("u1", 2.0), untyped("u2", 1.5));
            ParityHarness.assertSame("Object = Double", all(h, "untyped"), true);
        }

        @Test
        @DisplayName("a recursive POJO (Node -> next) keeps every level")
        void recursivePojo() {
            ParityHarness h = ParityHarness.of(Domain.of("chains", Chain.class));
            Node third = with(new Node(), n -> n.label = "c");
            Node second = with(new Node(), n -> {
                n.label = "b";
                n.next = third;
            });
            Node first = with(new Node(), n -> {
                n.label = "a";
                n.next = second;
            });
            h.save("chains", with(new Chain(), c -> {
                c.uuid = "c1";
                c.head = first;
            }), with(new Chain(), c -> c.uuid = "c2"), with(new Chain(), c -> {
                c.uuid = "c3";
                c.head = new Node();
            }));
            ParityHarness.assertSame("recursive POJO", all(h, "chains"), true);
        }

        @Test
        @DisplayName("an embedded POJO: populated, null, and present-with-all-fields-null")
        void embeddedNullVersusEmpty() {
            ParityHarness h = ParityHarness.of(Domain.of("people", Person.class));
            h.save("people", with(new Person(), p -> {
                p.uuid = "p1";
                p.address = with(new Address(), a -> a.city = "Dole");
            }), with(new Person(), p -> {
                p.uuid = "p2";
                p.name = "no address";
            }), with(new Person(), p -> {
                p.uuid = "p3";
                p.address = new Address();
            }));
            ParityHarness.assertSame("embedded POJO null vs empty", all(h, "people"), true);
        }

        @Test
        @DisplayName("a populated embedded POJO comes back populated (control)")
        void embeddedPopulated() {
            ParityHarness h = ParityHarness.of(Domain.of("people", Person.class));
            h.save("people", with(new Person(), p -> {
                p.uuid = "p1";
                p.name = "n";
                p.address = with(new Address(), a -> {
                    a.city = "Dole";
                    a.zip = "39100";
                });
            }));
            ParityHarness.assertSame("embedded POJO populated", all(h, "people"), true);
        }

        @Test
        @DisplayName("a List<POJO> keeps an element whose fields are all null, as an object")
        void pojoListWithEmptyElement() {
            ParityHarness h = ParityHarness.of(Domain.of("peoples", People.class));
            h.save("peoples", with(new People(), p -> {
                p.uuid = "p1";
                p.addresses = List.of(with(new Address(), a -> a.city = "Dole"), new Address(),
                        with(new Address(), a -> a.zip = "39"));
            }));
            ParityHarness.assertSame("List<POJO> with an all-null element", all(h, "peoples"), true);
        }
    }

    // ------------------------------------------------------------------ compositions

    @Nested
    @DisplayName("Compositions")
    class Compositions {

        @Test
        @DisplayName("a single reference is resolved to the stored target")
        void singleResolved() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c1", "Alice"), customer("c2", "Bob"));
            h.save("orders", order("o1", "first", customer("c1", "Alice")), order("o2", "none", null),
                    order("o3", "bob", customer("c2", "Bob")));
            ParityHarness.assertSame("single composition", all(h, "orders"), true);
        }

        @Test
        @DisplayName("a target saved AFTER its owner is resolved at read time")
        void targetSavedAfter() {
            ParityHarness h = ordersAndCustomers();
            h.save("orders", order("o1", "early", customer("c1", "Alice")));
            h.save("customers", customer("c1", "Alice"));
            ParityHarness.assertSame("target saved after owner", all(h, "orders"), true);
        }

        @Test
        @DisplayName("the STORED target wins over the stale copy the owner held when it was saved")
        void storedTargetWins() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c1", "fresh"));
            h.save("orders", order("o1", "x", customer("c1", "stale")));
            ParityHarness.assertSame("stored target wins", all(h, "orders"), true);
        }

        @Test
        @DisplayName("a dangling reference (target never saved) reads back null")
        void dangling() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c2", "Bob"));
            h.save("orders", order("o1", "ghost", customer("c404", "nobody")),
                    order("o2", "real", customer("c2", "Bob")));
            ParityHarness.assertSame("dangling reference", all(h, "orders"), true);
        }

        @Test
        @DisplayName("a target deleted after the owner was saved reads back null")
        void targetDeleted() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c1", "Alice"), customer("c2", "Bob"));
            h.save("orders", order("o1", "a", customer("c1", "Alice")), order("o2", "b", customer("c2", "Bob")));
            ParityHarness.assertSame("delete target", h.delete("customers", customer("c1", null)), false);
            ParityHarness.assertSame("target deleted after", all(h, "orders"), true);
        }

        @Test
        @DisplayName("a composed object with a null uuid is stored (or refused) alike")
        void composedNullUuid() {
            ParityHarness h = ordersAndCustomers();
            h.save("orders", order("o1", "anonymous", customer(null, "no id")));
            ParityHarness.assertSame("composed null uuid", all(h, "orders"), true);
        }

        @Test
        @DisplayName("a reference list is resolved in order, a dangling element skipped")
        void listResolvedInOrder() {
            ParityHarness h = basketsAndCustomers();
            h.save("customers", customer("c1", "Alice"), customer("c2", "Bob"), customer("c3", "Carol"));
            h.save("baskets", basket("b1", List.of(customer("c3", "Carol"), customer("c404", "ghost"),
                    customer("c1", "Alice"))));
            ParityHarness.assertSame("reference list", all(h, "baskets"), true);
        }

        @Test
        @DisplayName("a reference list with a null element skips it")
        void listWithNullElement() {
            ParityHarness h = basketsAndCustomers();
            h.save("customers", customer("c1", "Alice"));
            h.save("baskets", basket("b1", Arrays.asList(null, customer("c1", "Alice"), null)));
            ParityHarness.assertSame("reference list with null", all(h, "baskets"), true);
        }

        @Test
        @DisplayName("a null reference list reads back null, an empty one empty")
        void listNullVersusEmpty() {
            ParityHarness h = basketsAndCustomers();
            h.save("customers", customer("c1", "Alice"));
            h.save("baskets", basket("b1", null), basket("b2", new ArrayList<>()),
                    basket("b3", List.of(customer("c1", "Alice"))));
            ParityHarness.assertSame("null vs empty reference list", all(h, "baskets"), true);
        }

        @Test
        @DisplayName("a reference list whose targets are all dangling reads back empty")
        void listAllDangling() {
            ParityHarness h = basketsAndCustomers();
            h.save("baskets", basket("b1", List.of(customer("x1", "ghost"), customer("x2", "ghost"))));
            ParityHarness.assertSame("all-dangling reference list", all(h, "baskets"), true);
        }

        @Test
        @DisplayName("A -> B -> C: only one level is resolved, the second-level reference stays null")
        void oneLevelOnly() {
            ParityHarness h = ParityHarness.of(new Domain("letters", Letter.class, Map.of("to", "residents")),
                    new Domain("residents", Resident.class, Map.of("street", "streets")),
                    Domain.of("streets", Street.class));
            Street street = with(new Street(), s -> {
                s.uuid = "s1";
                s.name = "Grande Rue";
            });
            Resident resident = with(new Resident(), r -> {
                r.uuid = "r1";
                r.name = "Alice";
                r.street = street;
            });
            h.save("streets", street);
            h.save("residents", resident);
            h.save("letters", with(new Letter(), l -> {
                l.uuid = "l1";
                l.to = resident;
            }));
            ParityHarness.assertSame("letters (A->B->C)", all(h, "letters"), true);
            ParityHarness.assertSame("residents (B->C)", all(h, "residents"), true);
        }

        @Test
        @DisplayName("a composition field declared as String holds the uuid itself")
        void compositionByUuidString() {
            ParityHarness h = ParityHarness.of(
                    new Domain("refs", RefByString.class, Map.of("customer", "customers")),
                    Domain.of("customers", Customer.class));
            h.save("customers", customer("c1", "Alice"));
            h.save("refs", with(new RefByString(), r -> {
                r.uuid = "r1";
                r.customer = "c1";
            }));
            ParityHarness.assertSame("String-typed composition", all(h, "refs"), true);
        }

        @Test
        @DisplayName("a filter on the reference's stored id ('customer.$id', the DBRef path) counts alike")
        void filterOnReferenceDbRefPath() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c1", "Alice"), customer("c2", "Bob"));
            h.save("orders", order("o1", "a", customer("c1", "Alice")), order("o2", "b", customer("c2", "Bob")),
                    order("o3", "none", null));
            ParityHarness.assertSame("count customer.$id = c1",
                    h.count("orders", ParityFilter.field("customer.$id", "$eq", "c1")), false);
        }

        @Test
        @DisplayName("a filter on the reference field itself ('customer' = uuid) counts alike")
        void filterOnReferenceField() {
            ParityHarness h = ordersAndCustomers();
            h.save("customers", customer("c1", "Alice"), customer("c2", "Bob"));
            h.save("orders", order("o1", "a", customer("c1", "Alice")), order("o2", "b", customer("c2", "Bob")),
                    order("o3", "none", null));
            ParityHarness.assertSame("count customer = c1",
                    h.count("orders", ParityFilter.field("customer", "$eq", "c1")), false);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Nested
    @DisplayName("Update, delete, missing uuid")
    class Lifecycle {

        @Test
        @DisplayName("saving twice REPLACES: changed fields, removed list elements, fields set back to null")
        void saveTwice() {
            ParityHarness h = ParityHarness.of(Domain.of("people", Person.class));
            h.save("people", with(new Person(), p -> {
                p.uuid = "p1";
                p.name = "before";
                p.address = with(new Address(), a -> a.city = "Dole");
            }));
            h.save("people", with(new Person(), p -> {
                p.uuid = "p1";
                p.name = null;
                p.address = with(new Address(), a -> a.zip = "39100");
            }));
            ParityHarness.assertSame("count after double save", h.count("people", null), false);
            ParityHarness.assertSame("save twice", all(h, "people"), true);
        }

        @Test
        @DisplayName("saving a list twice replaces it (shrinks, then empties)")
        void saveListTwice() {
            ParityHarness h = ParityHarness.of(Domain.of("lists", Lists.class));
            h.save("lists", with(new Lists(), l -> {
                l.uuid = "l1";
                l.tags = List.of("a", "b", "c");
            }), with(new Lists(), l -> {
                l.uuid = "l2";
                l.tags = List.of("x");
            }));
            h.save("lists", with(new Lists(), l -> {
                l.uuid = "l1";
                l.tags = List.of("b");
            }), with(new Lists(), l -> {
                l.uuid = "l2";
                l.tags = new ArrayList<>();
            }));
            ParityHarness.assertSame("list replaced", all(h, "lists"), true);
        }

        @Test
        @DisplayName("deleting an entity that was never saved fails on both engines")
        void deleteMissing() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text("t1", "x"));
            ParityHarness.assertSame("delete missing", h.delete("texts", text("nope", "x")), false);
            ParityHarness.assertSame("rows untouched", all(h, "texts"), true);
        }

        @Test
        @DisplayName("deleting an entity with a null uuid fails on both engines")
        void deleteNullUuid() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text("t1", "x"));
            ParityHarness.assertSame("delete null uuid", h.delete("texts", text(null, "x")), false);
        }

        @Test
        @DisplayName("saving an entity with a null uuid is accepted (or refused) by both engines alike")
        void saveNullUuid() {
            ParityHarness h = ParityHarness.of(Domain.of("texts", Text.class));
            h.save("texts", text(null, "orphan"));
            ParityHarness.assertSame("null-uuid entity readable", all(h, "texts"), true);
        }
    }
}
