package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

import org.geojson.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The read side, proven against a real PostgreSQL. Rows are inserted with PLAIN SQL — the reader
 * must understand what is in the tables, whoever wrote it.
 */
@DisplayName("PgReader")
class PgReaderTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ---------------------------------------------------------------- DTOs

    public enum Color { RED, GREEN }

    public static class Scalars {
        private String uuid;
        private String text;
        private char letter;
        private int i;
        private Integer boxedI;
        private long l;
        private Long boxedL;
        private short s;
        private byte b;
        private double d;
        private Float f;
        private boolean flag;
        private BigDecimal amount;
        private BigInteger big;
        private Instant at;
        private Date date;
        private OffsetDateTime odt;
        private ZonedDateTime zdt;
        private LocalDateTime ldt;
        private LocalDate ld;
        private LocalTime lt;
        private UUID token;
        private byte[] bytes;
        private Color color;
    }

    public static class Widened {
        private String uuid;
        private Long count;
        private double ratio;
    }

    public static class Address {
        private String city;
        private Integer zip;
    }

    public static class Person {
        private String uuid;
        private String name;
        private Address address;
        private Address home = new Address();
    }

    public static class Node {
        private String label;
        private Node self;
    }

    public static class Tree {
        private String uuid;
        private Node root;
        private List<Object> coords;
        private Map<String, List<Node>> forest;
    }

    public static class Line {
        private String sku;
        private BigDecimal price;
    }

    public static class Bag {
        private String uuid;
        private String name;
        private List<String> tags;
        private Set<Integer> numbers;
        private SortedSet<String> sorted;
        private int[] codes;
        private Map<String, Integer> stock;
        private List<Line> lines;
        private Map<String, Line> byCode;
    }

    public static class Customer {
        private String uuid;
        private String name;
        private Customer sponsor;
    }

    public static class Order {
        private String uuid;
        private Customer customer;
        private List<Customer> customers;
        private String customerId;
    }

    public static class Ranked {
        private String uuid;
        private String name;
        private int rank;
    }

    public static class Located {
        private String uuid;
        private Point location;
    }

    // ---------------------------------------------------------------- helpers

    private Connection connection;

    @AfterEach
    void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    private static PgTable model(String domain, Class<?> dto, Map<String, String> compositions) {
        return PgSchemaModel.of(domain, IClass.getClass(dto), "uuid", compositions);
    }

    private Connection db(PgTable... tables) throws SQLException {
        connection = PgTestDatabase.freshDatabase().getConnection();
        for (PgTable table : tables) {
            for (String ddl : PgDdl.create(table)) {
                exec(ddl);
            }
        }
        return connection;
    }

    private void exec(String... sql) throws SQLException {
        try (Statement s = connection.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private static <T> T only(List<Object> found, Class<T> type) {
        assertEquals(1, found.size(), "exactly one row was inserted, so exactly one DTO must be read");
        return type.cast(found.get(0));
    }

    private static PgReader reader(PgTable table, Class<?> dto) {
        return new PgReader(table, IClass.getClass(dto), new PgSchemaRegistry());
    }

    // ---------------------------------------------------------------- tests

    @Nested
    @DisplayName("scalars")
    class ScalarsRead {

        @Test
        @DisplayName("read every scalar type back into the field's Java type")
        void everyScalarType() throws Exception {
            PgTable table = model("scalars", Scalars.class, Map.of());
            db(table);
            exec("INSERT INTO scalars VALUES ('s1', 'hello', 'Z', 7, 8, 9000000000, 10, 11, 12, 1.5, 2.5, true, "
                    + "123.45, 99999999999999999999, '2026-09-21T10:15:30.123456Z', '2026-01-02T03:04:05Z', "
                    + "'2026-01-02T03:04:05+02:00', '2026-01-02T03:04:05Z', '2026-01-02T03:04:05', '2026-01-02', "
                    + "'03:04:05', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '\\x0102ff', 'GREEN')");

            Scalars s = only(reader(table, Scalars.class).find(connection, PgQuery.all()), Scalars.class);

            assertEquals("s1", s.uuid, "id");
            assertEquals("hello", s.text, "TEXT -> String");
            assertEquals('Z', s.letter, "TEXT -> char");
            assertEquals(7, s.i, "INTEGER -> int");
            assertEquals(Integer.valueOf(8), s.boxedI, "INTEGER -> Integer");
            assertEquals(9_000_000_000L, s.l, "BIGINT -> long");
            assertEquals(Long.valueOf(10), s.boxedL, "BIGINT -> Long");
            assertEquals((short) 11, s.s, "SMALLINT -> short");
            assertEquals((byte) 12, s.b, "SMALLINT -> byte");
            assertEquals(1.5, s.d, "DOUBLE PRECISION -> double");
            assertEquals(Float.valueOf(2.5f), s.f, "REAL -> Float");
            assertTrue(s.flag, "BOOLEAN -> boolean");
            assertEquals(new BigDecimal("123.45"), s.amount, "NUMERIC -> BigDecimal");
            assertEquals(new BigInteger("99999999999999999999"), s.big, "NUMERIC -> BigInteger");
            assertEquals(Instant.parse("2026-09-21T10:15:30.123456Z"), s.at, "TIMESTAMPTZ -> Instant, micros kept");
            assertEquals(Date.from(Instant.parse("2026-01-02T03:04:05Z")), s.date, "TIMESTAMPTZ -> Date");
            assertEquals(Instant.parse("2026-01-02T01:04:05Z"), s.odt.toInstant(), "TIMESTAMPTZ -> OffsetDateTime");
            assertEquals(Instant.parse("2026-01-02T03:04:05Z"), s.zdt.toInstant(), "TIMESTAMPTZ -> ZonedDateTime");
            assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), s.ldt, "TIMESTAMP -> LocalDateTime, no zone shift");
            assertEquals(LocalDate.of(2026, 1, 2), s.ld, "DATE -> LocalDate");
            assertEquals(LocalTime.of(3, 4, 5), s.lt, "TIME -> LocalTime");
            assertEquals(UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"), s.token, "UUID -> UUID");
            assertArrayEquals(new byte[] { 1, 2, (byte) 0xff }, s.bytes, "BYTEA -> byte[]");
            assertEquals(Color.GREEN, s.color, "TEXT -> enum by name");
        }

        @Test
        @DisplayName("leave a primitive at its default and a boxed field null on SQL NULL")
        void nullsOnPrimitives() throws Exception {
            PgTable table = model("scalars", Scalars.class, Map.of());
            db(table);
            exec("INSERT INTO scalars (uuid) VALUES ('s1')");
            Scalars s = only(reader(table, Scalars.class).find(connection, PgQuery.all()), Scalars.class);
            assertEquals(0, s.i, "a NULL must not fail a primitive field");
            assertNull(s.boxedI, "a NULL reads as null into a boxed field");
            assertNull(s.color, "a NULL enum reads as null");
        }

        @Test
        @DisplayName("coerce a column narrower than the field: INTEGER into Long, NUMERIC into double")
        void coercion() throws Exception {
            PgTable table = model("widened", Widened.class, Map.of());
            db();
            exec("CREATE TABLE widened (uuid TEXT PRIMARY KEY, count INTEGER, ratio NUMERIC)",
                    "INSERT INTO widened VALUES ('w1', 42, 0.25)");
            Widened w = only(reader(table, Widened.class).find(connection, PgQuery.all()), Widened.class);
            assertEquals(Long.valueOf(42), w.count, "an INTEGER column must be widened into a Long field");
            assertEquals(0.25, w.ratio, "a NUMERIC column must read into a double field");
        }

        @Test
        @DisplayName("refuse an enum constant that no longer exists, naming the column")
        void unknownEnum() throws Exception {
            PgTable table = model("scalars", Scalars.class, Map.of());
            db(table);
            exec("INSERT INTO scalars (uuid, color) VALUES ('s1', 'PURPLE')");
            ApiException e = assertThrows(ApiException.class,
                    () -> reader(table, Scalars.class).find(connection, PgQuery.all()));
            assertTrue(e.getMessage().contains("color"), () -> "the message must name the column: " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("embedded POJOs")
    class Embedded {

        @Test
        @DisplayName("rebuild a flattened POJO from its prefixed columns")
        void flattened() throws Exception {
            PgTable table = model("persons", Person.class, Map.of());
            db(table);
            exec("INSERT INTO persons (uuid, name, address__city, address__zip) VALUES ('p1', 'Ann', 'Dole', 39100)");
            Person p = only(reader(table, Person.class).find(connection, PgQuery.all()), Person.class);
            assertNotNull(p.address, "address columns hold values, so address must exist");
            assertEquals("Dole", p.address.city);
            assertEquals(Integer.valueOf(39100), p.address.zip);
        }

        @Test
        @DisplayName("read a POJO whose columns are all NULL as null — even over a field initialiser")
        void allNullIsNull() throws Exception {
            PgTable table = model("persons", Person.class, Map.of());
            db(table);
            exec("INSERT INTO persons (uuid, name) VALUES ('p1', 'Ann')");
            Person p = only(reader(table, Person.class).find(connection, PgQuery.all()), Person.class);
            assertNull(p.address, "all-NULL address columns must read as a null address");
            assertNull(p.home, "home is built by the field initialiser, but was stored as all-NULL: must be null");
        }

        @Test
        @DisplayName("keep a POJO with only some columns set, the others null")
        void partlyNull() throws Exception {
            PgTable table = model("persons", Person.class, Map.of());
            db(table);
            exec("INSERT INTO persons (uuid, home__city) VALUES ('p1', 'Dole')");
            Person p = only(reader(table, Person.class).find(connection, PgQuery.all()), Person.class);
            assertNotNull(p.home, "one non-NULL column is enough for the POJO to exist");
            assertEquals("Dole", p.home.city);
            assertNull(p.home.zip);
        }
    }

    @Nested
    @DisplayName("JSONB")
    class Json {

        @Test
        @DisplayName("rebuild a recursive POJO, an untyped list and a generic map of lists")
        void jsonb() throws Exception {
            PgTable table = model("trees", Tree.class, Map.of());
            db(table);
            exec("INSERT INTO trees VALUES ('t1', '{\"label\":\"a\",\"self\":{\"label\":\"b\",\"self\":null}}', "
                    + "'[[1.5,2],[3,4]]', '{\"k\":[{\"label\":\"x\",\"self\":null}]}')");
            Tree t = only(reader(table, Tree.class).find(connection, PgQuery.all()), Tree.class);
            assertEquals("a", t.root.label);
            assertEquals("b", t.root.self.label, "the recursive POJO must be rebuilt at every depth");
            assertNull(t.root.self.self);
            assertEquals(List.of(List.of(1.5, 2), List.of(3, 4)), t.coords, "untyped nested list");
            assertInstanceOf(Node.class, t.forest.get("k").get(0),
                    "the field's GENERIC type must drive JSON reading: List<Node>, not a list of maps");
            assertEquals("x", t.forest.get("k").get(0).label);
        }
    }

    @Nested
    @DisplayName("child tables")
    class Children {

        private PgTable bags() {
            return model("bags", Bag.class, Map.of());
        }

        @Test
        @DisplayName("preserve list order, build the declared collection types, rebuild POJO lists and maps")
        void collections() throws Exception {
            PgTable table = bags();
            db(table);
            exec("INSERT INTO bags (uuid) VALUES ('b1')",
                    "INSERT INTO bags__tags VALUES ('b1', 2, 'c'), ('b1', 0, 'a'), ('b1', 1, 'b')",
                    "INSERT INTO bags__numbers VALUES ('b1', 0, 30), ('b1', 1, 10)",
                    "INSERT INTO bags__sorted VALUES ('b1', 0, 'z'), ('b1', 1, 'm')",
                    "INSERT INTO bags__codes VALUES ('b1', 0, 5), ('b1', 1, 6)",
                    "INSERT INTO bags__stock VALUES ('b1', 'x', 1), ('b1', 'y', 2)",
                    "INSERT INTO bags__lines VALUES ('b1', 1, 'B', 2.00), ('b1', 0, 'A', 1.00)",
                    "INSERT INTO \"bags__byCode\" VALUES ('b1', 'k', 'K', 9.99)");
            Bag b = only(reader(table, Bag.class).find(connection, PgQuery.all()), Bag.class);

            assertEquals(List.of("a", "b", "c"), b.tags, "elements must come back in _ord order");
            assertInstanceOf(LinkedHashSet.class, b.numbers, "a Set field reads as an order-keeping LinkedHashSet");
            assertEquals(List.of(30, 10), new ArrayList<>(b.numbers), "set iteration order is the stored order");
            assertInstanceOf(TreeSet.class, b.sorted, "a SortedSet field must read as a TreeSet");
            assertArrayEquals(new int[] { 5, 6 }, b.codes, "an int[] field reads as an int[]");
            assertEquals(Map.of("x", 1, "y", 2), b.stock);
            assertInstanceOf(LinkedHashMap.class, b.stock);
            assertEquals(2, b.lines.size());
            assertEquals("A", b.lines.get(0).sku, "POJO elements are ordered by _ord too");
            assertEquals(new BigDecimal("2.00"), b.lines.get(1).price);
            assertEquals("K", b.byCode.get("k").sku, "a map of POJOs rebuilds its values");
        }

        @Test
        @DisplayName("read zero child rows as EMPTY collections of the declared type, never null")
        void zeroRowsIsEmpty() throws Exception {
            PgTable table = bags();
            db(table);
            exec("INSERT INTO bags (uuid) VALUES ('b1')");
            Bag b = only(reader(table, Bag.class).find(connection, PgQuery.all()), Bag.class);
            assertNotNull(b.tags, "list must be empty, not null");
            assertTrue(b.tags.isEmpty());
            assertNotNull(b.numbers, "set must be empty, not null");
            assertTrue(b.numbers.isEmpty());
            assertNotNull(b.stock, "map must be empty, not null");
            assertTrue(b.stock.isEmpty());
            assertNotNull(b.codes, "array must be empty, not null");
            assertEquals(0, b.codes.length);
            assertNotNull(b.lines);
        }

        @Test
        @DisplayName("give each owner of a page its own elements")
        void groupedByOwner() throws Exception {
            PgTable table = bags();
            db(table);
            exec("INSERT INTO bags (uuid, name) VALUES ('b1', '1'), ('b2', '2'), ('b3', '3')",
                    "INSERT INTO bags__tags VALUES ('b1', 0, 'one'), ('b3', 0, 'three'), ('b3', 1, 'tres')");
            PgQuery byName = new PgQuery("TRUE", List.of(), "ORDER BY t.\"name\"", null, null, null);
            List<Object> found = reader(table, Bag.class).find(connection, byName);
            assertEquals(List.of("one"), ((Bag) found.get(0)).tags);
            assertEquals(List.of(), ((Bag) found.get(1)).tags, "b2 has no rows: its list is empty, not b1's or b3's");
            assertEquals(List.of("three", "tres"), ((Bag) found.get(2)).tags);
        }
    }

    @Nested
    @DisplayName("projection")
    class Projection {

        @Test
        @DisplayName("load only the projected fields, but always the id")
        void projected() throws Exception {
            PgTable table = model("persons", Person.class, Map.of());
            db(table);
            exec("INSERT INTO persons (uuid, name, address__city, address__zip) VALUES ('p1', 'Ann', 'Dole', 39100)");
            PgQuery query = new PgQuery("TRUE", List.of(), "", null, null, List.of("address"));
            Person p = only(reader(table, Person.class).find(connection, query), Person.class);
            assertEquals("p1", p.uuid, "the id is always read");
            assertNull(p.name, "name was not projected and must not be read");
            assertEquals("Dole", p.address.city, "'address' projects every address column");
            assertEquals(Integer.valueOf(39100), p.address.zip);
        }

        @Test
        @DisplayName("select only the projected columns and read only the projected child tables")
        void projectedChildren() throws Exception {
            PgTable table = model("bags", Bag.class, Map.of());
            db(table);
            exec("INSERT INTO bags (uuid, name) VALUES ('b1', 'n')",
                    "INSERT INTO bags__tags VALUES ('b1', 0, 'a')",
                    "INSERT INTO bags__lines VALUES ('b1', 0, 'A', 1)");
            PgReader reader = reader(table, Bag.class);
            assertEquals("t.\"uuid\"", reader.selectList(List.of("tags")), "only the id column for a child projection");
            Bag b = only(reader.find(connection, new PgQuery("TRUE", List.of(), "", null, null, List.of("tags"))),
                    Bag.class);
            assertEquals(List.of("a"), b.tags);
            assertNull(b.lines, "lines was not projected: its child table must not be read");
            assertNull(b.name);
        }
    }

    @Nested
    @DisplayName("compositions")
    class Compositions {

        private final PgTable customers = model("customers", Customer.class, Map.of("sponsor", "customers"));
        private final PgTable orders = model("orders", Order.class,
                Map.of("customer", "customers", "customers", "customers", "customerId", "customers"));

        private void rows() throws SQLException {
            db(customers, orders);
            exec("INSERT INTO customers VALUES ('c0', 'Root', NULL), ('c1', 'Ann', 'c0'), ('c2', 'Bob', 'c0')",
                    "INSERT INTO orders VALUES ('o1', 'c1', 'c2')",
                    "INSERT INTO orders__customers VALUES ('o1', 0, 'c2'), ('o1', 1, 'ghost'), ('o1', 2, 'c1')");
        }

        @Test
        @DisplayName("resolve references one level deep, and NOT two")
        void oneLevel() throws Exception {
            rows();
            PgSchemaRegistry registry = new PgSchemaRegistry();
            registry.register("customers", customers, IClass.getClass(Customer.class));
            registry.register("orders", orders, IClass.getClass(Order.class));
            Order o = only(new PgReader(orders, IClass.getClass(Order.class), registry)
                    .find(connection, PgQuery.all()), Order.class);

            assertNotNull(o.customer, "the referenced customer must be resolved");
            assertEquals("Ann", o.customer.name);
            assertNull(o.customer.sponsor, "the customer's own reference is one level too deep: it must stay null");
            assertEquals("c2", o.customerId, "a String reference field holds the uuid itself");
            assertEquals(List.of("Bob", "Ann"), o.customers.stream().map(c -> c.name).toList(),
                    "a reference list keeps its order and skips the dangling 'ghost' uuid, as MongoDB does");
        }

        @Test
        @DisplayName("resolve a dangling single reference to null")
        void dangling() throws Exception {
            rows();
            exec("UPDATE orders SET customer = 'ghost'");
            PgSchemaRegistry registry = new PgSchemaRegistry();
            registry.register("customers", customers, IClass.getClass(Customer.class));
            Order o = only(new PgReader(orders, IClass.getClass(Order.class), registry)
                    .find(connection, PgQuery.all()), Order.class);
            assertNull(o.customer, "a reference to a missing row reads as null");
        }

        @Test
        @DisplayName("leave references to an unregistered domain null, without failing")
        void unregistered() throws Exception {
            rows();
            Order o = only(reader(orders, Order.class).find(connection, PgQuery.all()), Order.class);
            assertNull(o.customer, "an unregistered target cannot be resolved");
            assertNull(o.customers, "an unregistered target collection cannot be resolved");
            assertEquals("c2", o.customerId, "a uuid-typed reference needs no target to be read");
        }
    }

    @Nested
    @DisplayName("query execution")
    class Execution {

        private PgTable ranked() throws SQLException {
            PgTable table = model("ranked", Ranked.class, Map.of());
            db(table);
            exec("INSERT INTO ranked VALUES ('r1', 'a', 1), ('r2', 'b', 2), ('r3', 'c', 3), ('r4', 'd', 4), "
                    + "('r5', 'e', 5)");
            return table;
        }

        @Test
        @DisplayName("apply where, params, order by, limit and offset")
        void paging() throws Exception {
            PgTable table = ranked();
            PgQuery query = new PgQuery("t.\"rank\" >= ?", List.of(2), "ORDER BY t.\"rank\" DESC", 2, 1L, null);
            List<Object> found = reader(table, Ranked.class).find(connection, query);
            assertEquals(List.of(4, 3), found.stream().map(r -> ((Ranked) r).rank).toList(),
                    "ranks >= 2, descending, skip 1, take 2");
        }

        @Test
        @DisplayName("count the rows matching the filter")
        void count() throws Exception {
            PgTable table = ranked();
            PgReader reader = reader(table, Ranked.class);
            assertEquals(4, reader.count(connection, new PgQuery("t.\"rank\" >= ?", List.of(2), "", 1, 0L, null)),
                    "count ignores the page");
            assertEquals(5, reader.count(connection, PgQuery.all()));
        }

        @Test
        @DisplayName("return an empty list when nothing matches")
        void nothing() throws Exception {
            PgTable table = ranked();
            List<Object> found = reader(table, Ranked.class)
                    .find(connection, new PgQuery("t.\"rank\" > ?", List.of(99), "", null, null, null));
            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("refuse a negative page size")
        void negativeLimit() throws Exception {
            PgTable table = ranked();
            assertThrows(ApiException.class, () -> reader(table, Ranked.class)
                    .find(connection, new PgQuery("TRUE", List.of(), "", -1, null, null)));
        }
    }

    @Nested
    @DisplayName("geometry (SQL shape only: the embedded engine has no PostGIS)")
    class Geometry {

        @Test
        @DisplayName("select a geometry column as GeoJSON text")
        void selectedAsGeoJson() {
            PgTable table = model("located", Located.class, Map.of());
            String list = reader(table, Located.class).selectList(null);
            assertTrue(list.contains("ST_AsGeoJSON(t.\"location\") AS \"location\""),
                    () -> "geometry must be selected through ST_AsGeoJSON: " + list);
            assertFalse(list.contains(", t.\"location\""), "the raw WKB column must not be selected as well");
        }
    }
}
