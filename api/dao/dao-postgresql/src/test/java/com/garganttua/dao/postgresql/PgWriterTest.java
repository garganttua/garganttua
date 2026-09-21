package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyType;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The write side, proven against a real PostgreSQL: what {@link PgWriter} stores is read back with
 * plain JDBC, never through the DAO's own reader — a writer checked by its own reader would pass
 * with both sides wrong in the same way.
 */
@DisplayName("The writer")
class PgWriterTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ------------------------------------------------------------------ DTOs

    public enum Level { LOW, HIGH }

    public static class Scalars {
        private String uuid;
        private String text;
        private Level level;
        private int count;
        private long big;
        private short small;
        private byte tiny;
        private double ratio;
        private float fraction;
        private boolean flag;
        private Boolean boxed;
        private char letter;
        private BigDecimal amount;
        private BigInteger huge;
        private Instant at;
        private Date date;
        private LocalDate day;
        private LocalDateTime local;
        private LocalTime time;
        private OffsetDateTime offset;
        private UUID ref;
        private byte[] blob;
    }

    public static class Address {
        private String street;
        private String city;
        private int zip;
    }

    public static class Line {
        private String sku;
        private BigDecimal price;

        Line() {
        }

        Line(String sku, String price) {
            this.sku = sku;
            this.price = new BigDecimal(price);
        }
    }

    /** Contains itself: stored as JSONB. */
    public static class Node {
        private String label;
        private Node self;
    }

    public static class Rich {
        private String uuid;
        private String name;
        private Address address;
        private List<String> tags;
        private List<Line> lines;
        private Map<String, Integer> stock;
        private int[] scores;
        private Node node;
        private Object anything;
        private IKey key;
    }

    /** Referenced domain whose uuid field is NOT named like the owner's — proves the target's id is used. */
    public static class Customer {
        private String id;
        private String name;

        Customer() {
        }

        Customer(String id) {
            this.id = id;
        }
    }

    public static class Invoice {
        private String uuid;
        private Customer customer;
        private List<Customer> payers;
    }

    public static class Legacy {
        private String uuid;
    }

    public static class Holder {
        private String uuid;
        private Legacy legacy;
        private String ticket;
    }

    public static class Place {
        private String uuid;
        private org.geojson.Point where;
    }

    // --------------------------------------------------------------- helpers

    private static PgTable model(String domain, IClass<?> dto, Map<String, String> compositions) {
        return PgSchemaModel.of(domain, dto, "uuid", compositions);
    }

    private static DataSource create(PgTable table) throws SQLException {
        DataSource db = PgTestDatabase.freshDatabase();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String ddl : PgDdl.create(table)) {
                s.execute(ddl);
            }
        }
        return db;
    }

    /** Writes in a caller-owned transaction, as the DAO will. */
    private static void save(DataSource db, PgWriter writer, Object dto) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            writer.upsert(c, dto);
            c.commit();
        }
    }

    private static void remove(DataSource db, PgWriter writer, Object dto) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            writer.delete(c, dto);
            c.commit();
        }
    }

    /** Runs a query and returns every row as a list of column values. */
    private static List<List<Object>> rows(DataSource db, String sql, Object... params) throws SQLException {
        try (Connection c = db.getConnection()) {
            return rows(c, sql, params);
        }
    }

    private static List<List<Object>> rows(Connection c, String sql, Object... params) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                p.setObject(i + 1, params[i]);
            }
            try (ResultSet r = p.executeQuery()) {
                int n = r.getMetaData().getColumnCount();
                while (r.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        row.add(r.getObject(i));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static long count(DataSource db, String table) throws SQLException {
        return ((Number) rows(db, "SELECT count(*) FROM \"" + table + "\"").get(0).get(0)).longValue();
    }

    private static Rich rich(String uuid) {
        Rich r = new Rich();
        r.uuid = uuid;
        r.name = "first";
        r.address = new Address();
        r.address.street = "1 rue de la Paix";
        r.address.city = "Dole";
        r.address.zip = 39100;
        r.tags = new ArrayList<>(List.of("b", "a", "c"));
        r.lines = new ArrayList<>(List.of(new Line("A", "1.50"), new Line("B", "2")));
        r.stock = new LinkedHashMap<>(Map.of("x", 1, "y", 2));
        r.scores = new int[] { 7, 3 };
        return r;
    }

    private static IKey signingKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] material = generator.generateKeyPair().getPrivate().getEncoded();
        return com.garganttua.core.crypto.Key.fromSigningMaterial(KeyType.PRIVATE,
                KeyAlgorithm.validateKeyAlgorithm("EC-256"), SignatureAlgorithm.SHA256, material);
    }

    // ----------------------------------------------------------------- tests

    @Nested
    @DisplayName("scalars")
    class ScalarColumns {

        private final PgTable table = model("scalars", IClass.getClass(Scalars.class), Map.of());

        @Test
        @DisplayName("land in their typed columns with the value the DTO held")
        void everyScalarLands() throws Exception {
            DataSource db = create(table);
            Scalars s = new Scalars();
            s.uuid = "s1";
            s.text = "héllo";
            s.level = Level.HIGH;
            s.count = 42;
            s.big = 9_000_000_000L;
            s.small = 12;
            s.tiny = 7;
            s.ratio = 3.25;
            s.fraction = 1.5f;
            s.flag = true;
            s.boxed = Boolean.FALSE;
            s.letter = 'x';
            s.amount = new BigDecimal("1234.5678");
            s.huge = new BigInteger("123456789012345678901234567890");
            s.at = Instant.parse("2026-09-21T10:15:30.123456Z");
            s.date = Date.from(Instant.parse("2020-01-02T03:04:05.678Z"));
            s.day = LocalDate.of(2026, 9, 21);
            s.local = LocalDateTime.of(2026, 9, 21, 8, 30, 15);
            s.time = LocalTime.of(23, 59, 1);
            s.offset = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2));
            s.ref = UUID.randomUUID();
            s.blob = new byte[] { 0, 1, (byte) 0xFF };

            save(db, new PgWriter(table, new PgSchemaRegistry()), s);

            try (Connection c = db.getConnection(); Statement st = c.createStatement();
                    ResultSet r = st.executeQuery("SELECT * FROM \"scalars\"")) {
                assertTrue(r.next(), "the row must have been written");
                assertEquals("s1", r.getString("uuid"));
                assertEquals("héllo", r.getString("text"));
                assertEquals("HIGH", r.getString("level"), "an enum is stored by its name");
                assertEquals(42, r.getInt("count"));
                assertEquals(9_000_000_000L, r.getLong("big"));
                assertEquals(12, r.getShort("small"));
                assertEquals(7, r.getShort("tiny"));
                assertEquals(3.25, r.getDouble("ratio"));
                assertEquals(1.5f, r.getFloat("fraction"));
                assertTrue(r.getBoolean("flag"));
                assertEquals(Boolean.FALSE, r.getObject("boxed"));
                assertEquals("x", r.getString("letter"));
                assertEquals(0, s.amount.compareTo(r.getBigDecimal("amount")), "NUMERIC must keep every digit");
                assertEquals(s.huge, r.getBigDecimal("huge").toBigIntegerExact());
                assertEquals(Instant.parse("2026-09-21T10:15:30.123Z"), r.getObject("at", OffsetDateTime.class).toInstant(),
                        "truncated to the millisecond a BSON date keeps");
                assertEquals(s.date.toInstant(), r.getObject("date", OffsetDateTime.class).toInstant());
                assertEquals(s.day, r.getObject("day", LocalDate.class));
                assertEquals(s.local, r.getObject("local", LocalDateTime.class));
                assertEquals(s.time, r.getObject("time", LocalTime.class));
                assertEquals(s.offset.toInstant(), r.getObject("offset", OffsetDateTime.class).toInstant());
                assertEquals(s.ref, r.getObject("ref", UUID.class));
                assertArrayEquals(s.blob, r.getBytes("blob"));
                assertFalse(r.next(), "one entity is one row");
            }
        }

        @Test
        @DisplayName("store NULL for every null value, whatever the column type")
        void nullsAreNull() throws Exception {
            DataSource db = create(table);
            Scalars s = new Scalars();
            s.uuid = "s2";
            // A primitive char defaults to U+0000, which PostgreSQL TEXT cannot hold (see the report).
            s.letter = 'y';
            save(db, new PgWriter(table, new PgSchemaRegistry()), s);

            List<Object> row = rows(db, "SELECT \"text\", \"level\", \"boxed\", \"amount\", \"huge\", \"at\", "
                    + "\"date\", \"day\", \"local\", \"time\", \"offset\", \"ref\", \"blob\" FROM \"scalars\"").get(0);
            for (int i = 0; i < row.size(); i++) {
                assertNull(row.get(i), "column #" + (i + 1) + " of a null field must be NULL");
            }
        }
    }

    @Nested
    @DisplayName("MongoDB's limits, kept for parity")
    class Limits {

        private final PgTable table = model("scalars", IClass.getClass(Scalars.class), Map.of());

        private Scalars scalars() {
            Scalars s = new Scalars();
            s.uuid = "s1";
            s.letter = 'x';
            return s;
        }

        @Test
        @DisplayName("truncate instants and local date-times to the millisecond of a BSON date")
        void millis() throws Exception {
            DataSource db = create(table);
            Scalars s = scalars();
            s.local = LocalDateTime.parse("2024-01-02T03:04:05.123456");
            s.time = LocalTime.parse("23:59:58.999999");
            s.offset = OffsetDateTime.parse("2024-01-02T03:04:05.123999+02:00");
            save(db, new PgWriter(table, new PgSchemaRegistry()), s);
            try (Connection c = db.getConnection(); Statement st = c.createStatement();
                    ResultSet r = st.executeQuery("SELECT \"local\", \"time\", \"offset\" FROM \"scalars\"")) {
                r.next();
                assertEquals(LocalDateTime.parse("2024-01-02T03:04:05.123"), r.getObject(1, LocalDateTime.class));
                assertEquals(LocalTime.parse("23:59:58.999"), r.getObject(2, LocalTime.class));
                assertEquals(Instant.parse("2024-01-02T01:04:05.123Z"), r.getObject(3, OffsetDateTime.class).toInstant(),
                        "truncated, never rounded up");
            }
        }

        @Test
        @DisplayName("accept 34 significant digits and refuse 35, naming the field")
        void decimal128() throws Exception {
            DataSource db = create(table);
            PgWriter writer = new PgWriter(table, new PgSchemaRegistry());
            Scalars s = scalars();
            s.amount = new BigDecimal("1234567890123456789012345678901.234");
            save(db, writer, s);
            s.amount = new BigDecimal("1234567890123456789012345678901.2345");
            ApiException e = assertThrows(ApiException.class, () -> save(db, writer, s));
            assertTrue(e.getMessage().contains("amount") && e.getMessage().contains("34"), e::getMessage);
            s.amount = null;
            s.huge = BigInteger.TEN.pow(40);
            assertThrows(ApiException.class, () -> save(db, writer, s), "a BigInteger is held to the same limit");
        }

        @Test
        @DisplayName("refuse the NUL character with a message naming the field")
        void nul() throws Exception {
            DataSource db = create(table);
            Scalars s = scalars();
            s.text = "a\0b";
            ApiException e = assertThrows(ApiException.class,
                    () -> save(db, new PgWriter(table, new PgSchemaRegistry()), s));
            assertTrue(e.getMessage().contains("text") && e.getMessage().contains("NUL"), e::getMessage);
        }

        @Test
        @DisplayName("tell an escaped NUL in JSON from an escaped backslash followed by 'u0000'")
        void escapedNul() {
            assertTrue(PgWriteSupport.escapesNul("{\"a\":\"x\\u0000\"}"));
            assertFalse(PgWriteSupport.escapesNul("{\"a\":\"x\\\\u0000\"}"));
        }
    }

    @Nested
    @DisplayName("structures")
    class Structures {

        private final PgTable table = model("riches", IClass.getClass(Rich.class), Map.of());

        @Test
        @DisplayName("flatten an embedded POJO into its prefixed columns")
        void pojoIsFlattened() throws Exception {
            DataSource db = create(table);
            save(db, new PgWriter(table, new PgSchemaRegistry()), rich("r1"));
            assertEquals(List.of(List.of("1 rue de la Paix", "Dole", 39100)),
                    rows(db, "SELECT \"address__street\", \"address__city\", \"address__zip\" FROM \"riches\""));
        }

        @Test
        @DisplayName("write every column of a null POJO as NULL — even a primitive one")
        void nullPojoIsAllNull() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.address = null;
            save(db, new PgWriter(table, new PgSchemaRegistry()), r);
            assertEquals(Arrays.asList(null, null, null),
                    rows(db, "SELECT \"address__street\", \"address__city\", \"address__zip\" FROM \"riches\"").get(0),
                    "a missing POJO must not become an int 0 the reader would turn into an empty object");
        }

        @Test
        @DisplayName("write a scalar list as rows ordered by _ord")
        void scalarListRows() throws Exception {
            DataSource db = create(table);
            save(db, new PgWriter(table, new PgSchemaRegistry()), rich("r1"));
            assertEquals(List.of(List.of("r1", 0, "b"), List.of("r1", 1, "a"), List.of("r1", 2, "c")),
                    rows(db, "SELECT \"_owner\", \"_ord\", \"value\" FROM \"riches__tags\" ORDER BY \"_ord\""));
        }

        @Test
        @DisplayName("keep a null element's position as a NULL value")
        void nullElementKeepsPosition() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.tags = Arrays.asList("b", null, "c");
            r.lines = Arrays.asList(new Line("A", "1"), null);
            save(db, new PgWriter(table, new PgSchemaRegistry()), r);
            assertEquals(List.of(List.of(0, "b"), Arrays.asList(1, null), List.of(2, "c")),
                    rows(db, "SELECT \"_ord\", \"value\" FROM \"riches__tags\" ORDER BY \"_ord\""));
            assertEquals(Arrays.asList(1, null, null),
                    rows(db, "SELECT \"_ord\", \"sku\", \"price\" FROM \"riches__lines\" ORDER BY \"_ord\"").get(1));
        }

        @Test
        @DisplayName("mark each structure present (TRUE) or absent (NULL), empty ones included")
        void presenceBits() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.address = new Address();
            r.tags = new ArrayList<>();
            r.stock = null;
            r.lines = Arrays.asList(new Line(), null);
            save(db, new PgWriter(table, new PgSchemaRegistry()), r);
            assertEquals(Arrays.asList(true, true, null),
                    rows(db, "SELECT \"address\", \"tags\", \"stock\" FROM \"riches\"").get(0),
                    "an all-null POJO and an empty list exist; a null map does not");
            assertEquals(Arrays.asList(true, null),
                    rows(db, "SELECT \"_present\" FROM \"riches__lines\" ORDER BY \"_ord\"").stream()
                            .map(row -> row.get(0)).toList(),
                    "an all-null element is present, a null element is not");
        }

        @Test
        @DisplayName("write an array like a list")
        void arrayRows() throws Exception {
            DataSource db = create(table);
            save(db, new PgWriter(table, new PgSchemaRegistry()), rich("r1"));
            assertEquals(List.of(List.of(0, 7), List.of(1, 3)),
                    rows(db, "SELECT \"_ord\", \"value\" FROM \"riches__scores\" ORDER BY \"_ord\""));
        }

        @Test
        @DisplayName("write a POJO list as flattened element rows")
        void pojoListRows() throws Exception {
            DataSource db = create(table);
            save(db, new PgWriter(table, new PgSchemaRegistry()), rich("r1"));
            List<List<Object>> lines = rows(db,
                    "SELECT \"_ord\", \"sku\", \"price\" FROM \"riches__lines\" ORDER BY \"_ord\"");
            assertEquals(2, lines.size(), "one row per element");
            assertEquals("A", lines.get(0).get(1));
            assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) lines.get(0).get(2)));
            assertEquals("B", lines.get(1).get(1));
            assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) lines.get(1).get(2)));
        }

        @Test
        @DisplayName("write a map as rows keyed by _key")
        void mapRows() throws Exception {
            DataSource db = create(table);
            save(db, new PgWriter(table, new PgSchemaRegistry()), rich("r1"));
            assertEquals(List.of(List.of("x", 1), List.of("y", 2)),
                    rows(db, "SELECT \"_key\", \"value\" FROM \"riches__stock\" ORDER BY \"_key\""));
        }

        @Test
        @DisplayName("refuse a map with a null key, which no row could hold")
        void nullMapKeyIsRefused() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.stock = new HashMap<>();
            r.stock.put(null, 1);
            ApiException e = assertThrows(ApiException.class,
                    () -> save(db, new PgWriter(table, new PgSchemaRegistry()), r));
            assertTrue(e.getMessage().contains("null key"), () -> "the message must say why: " + e.getMessage());
        }

        @Test
        @DisplayName("store a recursive or untyped field as a queryable JSONB document")
        void jsonbColumns() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.node = new Node();
            r.node.label = "root";
            r.node.self = new Node();
            r.node.self.label = "leaf";
            r.anything = Map.of("k", 5);
            save(db, new PgWriter(table, new PgSchemaRegistry()), r);
            assertEquals(List.of(List.of("root", "leaf", "5")), rows(db,
                    "SELECT \"node\"->>'label', \"node\"->'self'->>'label', \"anything\"->>'k' FROM \"riches\""));
        }

        @Test
        @DisplayName("store an IKey as a self-describing descriptor")
        void ikeyDescriptor() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            r.key = signingKey();
            save(db, new PgWriter(table, new PgSchemaRegistry()), r);
            List<Object> row = rows(db, "SELECT \"key\"->>'__ikey', \"key\"->>'algorithm', "
                    + "\"key\"->>'type', length(\"key\"->>'rawKey') FROM \"riches\"").get(0);
            assertEquals("true", row.get(0), "the descriptor must carry the __ikey marker the reader looks for");
            assertEquals("EC-256", row.get(1));
            assertEquals("PRIVATE", row.get(2));
            assertTrue(((Number) row.get(3)).intValue() > 0, "the key material must be stored");
        }
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        private final PgTable table = model("riches", IClass.getClass(Rich.class), Map.of());

        @Test
        @DisplayName("twice REPLACES: the main row updated, removed elements gone, no duplicates")
        void secondWriteReplaces() throws Exception {
            DataSource db = create(table);
            PgWriter writer = new PgWriter(table, new PgSchemaRegistry());
            save(db, writer, rich("r1"));

            Rich second = rich("r1");
            second.name = "second";
            second.address = null;
            second.tags = List.of("z");
            second.lines = List.of();
            second.stock = Map.of("y", 5);
            second.scores = null;
            save(db, writer, second);

            assertEquals(List.of(Arrays.asList("second", null)),
                    rows(db, "SELECT \"name\", \"address__city\" FROM \"riches\""), "one row, updated");
            assertEquals(List.of(List.of(0, "z")), rows(db, "SELECT \"_ord\", \"value\" FROM \"riches__tags\""));
            assertEquals(0, count(db, "riches__lines"), "an empty list is zero rows");
            assertEquals(0, count(db, "riches__scores"), "a null collection is zero rows");
            assertEquals(List.of(List.of("y", 5)), rows(db, "SELECT \"_key\", \"value\" FROM \"riches__stock\""));
        }

        @Test
        @DisplayName("of one entity leaves the collections of another untouched")
        void ownersAreIsolated() throws Exception {
            DataSource db = create(table);
            PgWriter writer = new PgWriter(table, new PgSchemaRegistry());
            save(db, writer, rich("r1"));
            Rich other = rich("r2");
            other.tags = List.of("only");
            save(db, writer, other);
            assertEquals(3, rows(db, "SELECT 1 FROM \"riches__tags\" WHERE \"_owner\" = ?", "r1").size());
            assertEquals(1, rows(db, "SELECT 1 FROM \"riches__tags\" WHERE \"_owner\" = ?", "r2").size());
        }

        @Test
        @DisplayName("of a table with only an id does nothing on conflict")
        void idOnlyTable() throws Exception {
            PgTable legacy = model("legacies", IClass.getClass(Legacy.class), Map.of());
            PgWriter writer = new PgWriter(legacy, new PgSchemaRegistry());
            assertTrue(writer.upsertSql().endsWith("DO NOTHING"), writer::upsertSql);
            DataSource db = create(legacy);
            Legacy l = new Legacy();
            l.uuid = "l1";
            save(db, writer, l);
            save(db, writer, l);
            assertEquals(1, count(db, "legacies"));
        }

        @Test
        @DisplayName("refuses an entity without a uuid")
        void nullUuidIsRefused() throws Exception {
            DataSource db = create(table);
            Rich r = rich(null);
            ApiException e = assertThrows(ApiException.class,
                    () -> save(db, new PgWriter(table, new PgSchemaRegistry()), r));
            assertTrue(e.getMessage().contains("uuid"), e::getMessage);
            assertEquals(0, count(db, "riches"));
        }

        @Test
        @DisplayName("does not commit — the caller's transaction decides")
        void doesNotCommit() throws Exception {
            DataSource db = create(table);
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                new PgWriter(table, new PgSchemaRegistry()).upsert(c, rich("r1"));
                assertEquals(0, count(db, "riches"), "another connection must not see an uncommitted write");
                c.rollback();
            }
            assertEquals(0, count(db, "riches"));
        }

        @Test
        @DisplayName("that fails mid-write leaves NO partial rows once the caller rolls back")
        void failureIsAtomic() throws Exception {
            DataSource db = create(table);
            Rich r = rich("r1");
            // stock is written after tags and lines: the failure comes once other rows exist.
            r.stock = new HashMap<>();
            r.stock.put(null, 1);
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                assertThrows(ApiException.class, () -> new PgWriter(table, new PgSchemaRegistry()).upsert(c, r));
                assertEquals(3, rows(c, "SELECT 1 FROM \"riches__tags\"").size(),
                        "precondition: the failure must happen AFTER earlier rows were written");
                c.rollback();
            }
            for (String t : List.of("riches", "riches__tags", "riches__lines", "riches__stock", "riches__scores")) {
                assertEquals(0, count(db, t), () -> "rollback left rows in " + t);
            }
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        private final PgTable table = model("riches", IClass.getClass(Rich.class), Map.of());

        @Test
        @DisplayName("removes the row and cascades to its collections")
        void deleteCascades() throws Exception {
            DataSource db = create(table);
            PgWriter writer = new PgWriter(table, new PgSchemaRegistry());
            save(db, writer, rich("r1"));
            save(db, writer, rich("r2"));
            remove(db, writer, rich("r1"));
            assertEquals(List.of(List.of("r2")), rows(db, "SELECT \"uuid\" FROM \"riches\""));
            for (String t : List.of("riches__tags", "riches__lines", "riches__stock", "riches__scores")) {
                assertTrue(rows(db, "SELECT 1 FROM \"" + t + "\" WHERE \"_owner\" = 'r1'").isEmpty(),
                        () -> "orphan rows left in " + t);
                assertFalse(rows(db, "SELECT 1 FROM \"" + t + "\" WHERE \"_owner\" = 'r2'").isEmpty(),
                        () -> "the other entity's rows were deleted from " + t);
            }
        }

        @Test
        @DisplayName("of a missing id is an error, as in MongoDB")
        void missingIsAnError() throws Exception {
            DataSource db = create(table);
            ApiException e = assertThrows(ApiException.class,
                    () -> remove(db, new PgWriter(table, new PgSchemaRegistry()), rich("ghost")));
            assertTrue(e.getMessage().contains("not found for deletion"), e::getMessage);
        }

        @Test
        @DisplayName("without a uuid is an error")
        void nullUuidIsAnError() throws Exception {
            DataSource db = create(table);
            assertThrows(ApiException.class, () -> remove(db, new PgWriter(table, new PgSchemaRegistry()), rich(null)));
        }
    }

    @Nested
    @DisplayName("compositions")
    class Compositions {

        private final PgTable table = model("invoices", IClass.getClass(Invoice.class),
                Map.of("customer", "customers", "payers", "customers"));

        private PgSchemaRegistry registry() {
            PgSchemaRegistry registry = new PgSchemaRegistry();
            registry.register("customers", PgSchemaModel.of("customers", IClass.getClass(Customer.class), "id",
                    Map.of()), IClass.getClass(Customer.class));
            return registry;
        }

        @Test
        @DisplayName("store the referenced uuid, read with the TARGET's id field")
        void singleReference() throws Exception {
            DataSource db = create(table);
            Invoice i = new Invoice();
            i.uuid = "i1";
            i.customer = new Customer("c-1");
            save(db, new PgWriter(table, registry()), i);
            assertEquals(List.of(List.of("c-1")), rows(db, "SELECT \"customer\" FROM \"invoices\""));
        }

        @Test
        @DisplayName("store a null reference as NULL")
        void nullReference() throws Exception {
            DataSource db = create(table);
            Invoice i = new Invoice();
            i.uuid = "i1";
            save(db, new PgWriter(table, registry()), i);
            assertEquals(List.of(Arrays.asList((Object) null)), rows(db, "SELECT \"customer\" FROM \"invoices\""));
        }

        @Test
        @DisplayName("store one row per referenced uuid, skipping null elements as MongoDB does")
        void referenceCollection() throws Exception {
            DataSource db = create(table);
            Invoice i = new Invoice();
            i.uuid = "i1";
            i.payers = Arrays.asList(new Customer("c-2"), null, new Customer("c-3"));
            save(db, new PgWriter(table, registry()), i);
            assertEquals(List.of(List.of(0, "c-2"), List.of(1, "c-3")),
                    rows(db, "SELECT \"_ord\", \"value\" FROM \"invoices__payers\" ORDER BY \"_ord\""));
        }

        @Test
        @DisplayName("refuse a referenced entity without a uuid")
        void referenceWithoutUuid() throws Exception {
            DataSource db = create(table);
            Invoice i = new Invoice();
            i.uuid = "i1";
            i.customer = new Customer(null);
            ApiException e = assertThrows(ApiException.class, () -> save(db, new PgWriter(table, registry()), i));
            assertTrue(e.getMessage().contains("null uuid"), e::getMessage);
        }

        @Test
        @DisplayName("fall back to the owner's uuid field name for an unregistered target, as MongoDB does")
        void unregisteredTarget() throws Exception {
            PgTable holders = model("holders", IClass.getClass(Holder.class), Map.of("legacy", "legacies", "ticket", "tickets"));
            DataSource db = create(holders);
            Holder h = new Holder();
            h.uuid = "h1";
            h.legacy = new Legacy();
            h.legacy.uuid = "l-9";
            save(db, new PgWriter(holders, new PgSchemaRegistry()), h);
            assertEquals(List.of(Arrays.asList("l-9", null)), rows(db, "SELECT \"legacy\", \"ticket\" FROM \"holders\""),
                    "an unregistered target is read with 'uuid'");
        }

        @Test
        @DisplayName("refuse a bare String in a @Composed field, as MongoDB does")
        void stringReferenceRefused() throws Exception {
            PgTable holders = model("holders", IClass.getClass(Holder.class), Map.of("legacy", "legacies", "ticket", "tickets"));
            DataSource db = create(holders);
            Holder h = new Holder();
            h.uuid = "h1";
            h.ticket = "t-4";
            ApiException e = assertThrows(ApiException.class, () -> save(db, new PgWriter(holders, new PgSchemaRegistry()), h),
                    "a composition holds the referenced entity: MongoDB refuses a String there, so must PostgreSQL");
            assertTrue(e.getMessage().contains("must hold the referenced entity"), e::getMessage);
        }

        @Test
        @DisplayName("fail when an unregistered target has no field named like the owner's uuid")
        void unregisteredTargetWithoutField() throws Exception {
            DataSource db = create(table);
            Invoice i = new Invoice();
            i.uuid = "i1";
            i.customer = new Customer("c-1");
            ApiException e = assertThrows(ApiException.class,
                    () -> save(db, new PgWriter(table, new PgSchemaRegistry()), i));
            assertTrue(e.getMessage().contains("no uuid field"), e::getMessage);
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {

        @Test
        @DisplayName("is bound through ST_GeomFromGeoJSON (SQL only: no PostGIS in the test engine)")
        void geometryPlaceholder() {
            PgTable places = model("places", IClass.getClass(Place.class), Map.of());
            String sql = new PgWriter(places, new PgSchemaRegistry()).upsertSql();
            assertTrue(sql.contains("ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)"), sql);
            assertTrue(sql.contains("\"where\" = EXCLUDED.\"where\""), sql);
        }
    }
}
