package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.geojson.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The schema manager, proved against a real PostgreSQL: what CREATE builds and adds, what it
 * refuses to touch, what VALIDATE reports, and that instances starting together do not collide.
 */
@DisplayName("The schema manager")
class PgSchemaManagerTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    /** The first version of a DTO. */
    public static class PersonV1 {
        private String uuid;
        private String name;
    }

    /** The same DTO after it gained a scalar and a collection. */
    public static class PersonV2 {
        private String uuid;
        private String name;
        private int age;
        private List<String> tags;
    }

    /** A DTO with a geometry: only its DDL is ever run, and it fails without PostGIS. */
    public static class Place {
        private String uuid;
        private String label;
        private Point location;
    }

    private static PgTable people(Class<?> dto) {
        return PgSchemaModel.of("people", IClass.getClass(dto), "uuid", Map.of());
    }

    private static PgTable orders() {
        return PgSchemaModel.of("orders", IClass.getClass(PgSchemaModelTest.Order.class), "uuid",
                Map.of("customer", "customers", "relatedOrders", "orders"));
    }

    private static PgTable places() {
        return PgSchemaModel.of("places", IClass.getClass(Place.class), "uuid", Map.of());
    }

    private static void ensure(DataSource db, SchemaMode mode, PgTable table) throws ApiException {
        new PgSchemaManager(db, mode).ensure(table);
    }

    /** Column name to information_schema data_type; empty when the table does not exist. */
    private static Map<String, String> columns(DataSource db, String table) throws SQLException {
        try (Connection c = db.getConnection()) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            PgSchemaInspector.columns(c, table).forEach((k, v) -> out.put(k, v.describe()));
            return out;
        }
    }

    private static void execute(DataSource db, String... sql) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private static void assertCompleteSchema(DataSource db, PgTable table) throws SQLException {
        Map<String, String> main = columns(db, table.name());
        for (PgColumn column : table.columns()) {
            assertTrue(main.containsKey(column.name()),
                    () -> "main table " + table.name() + " lacks column " + column.name() + ": " + main);
        }
        for (PgChildTable child : table.children()) {
            Map<String, String> stored = columns(db, child.name());
            assertFalse(stored.isEmpty(), () -> "child table " + child.name() + " was not created");
            for (PgColumn column : child.valueColumns()) {
                assertTrue(stored.containsKey(column.name()),
                        () -> "child table " + child.name() + " lacks column " + column.name());
            }
        }
    }

    @Nested
    @DisplayName("in CREATE mode")
    class Create {

        private final List<LogEvent> warnings = new CopyOnWriteArrayList<>();
        private final IObserver<ObservableEvent> capture = event -> {
            if (event instanceof LogEvent log && log.level() == LogEvent.Level.WARN) {
                warnings.add(log);
            }
        };

        @BeforeEach
        void listen() {
            Logger.getLogger(PgSchemaManager.class).addObserver(capture);
        }

        @AfterEach
        void stopListening() {
            Logger.getLogger(PgSchemaManager.class).removeObserver(capture);
        }

        @Test
        @DisplayName("creates every table and column of the model on an empty database")
        void createsFromEmpty() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, orders());
            assertCompleteSchema(db, orders());
            assertEquals("bigint", columns(db, "orders").get("total"),
                    "a long field must be a BIGINT column");
        }

        @Test
        @DisplayName("defaults to CREATE when no mode is given")
        void nullModeIsCreate() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            new PgSchemaManager(db, null).ensure(orders());
            assertCompleteSchema(db, orders());
        }

        @Test
        @DisplayName("is idempotent: a second run changes nothing, fails nothing, keeps the rows")
        void isIdempotent() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, orders());
            execute(db, "INSERT INTO \"orders\" (\"uuid\", \"total\") VALUES ('o1', 7)");
            Map<String, String> before = columns(db, "orders");
            ensure(db, SchemaMode.CREATE, orders());
            assertEquals(before, columns(db, "orders"), "a re-run must not alter the table");
            assertEquals(1, count(db, "orders"), "a re-run must not lose rows");
            assertTrue(warnings.isEmpty(), () -> "a complete schema must not warn: " + warnings);
        }

        @Test
        @DisplayName("adds the column of a new scalar field and keeps the existing rows")
        void addsNewColumn() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, people(PersonV1.class));
            execute(db, "INSERT INTO \"people\" (\"uuid\", \"name\") VALUES ('p1', 'Ada')");
            assertFalse(columns(db, "people").containsKey("age"), "precondition: V1 has no age");

            ensure(db, SchemaMode.CREATE, people(PersonV2.class));

            assertEquals("integer", columns(db, "people").get("age"), "the new int field needs its column");
            try (Connection c = db.getConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT \"name\", \"age\" FROM \"people\" WHERE \"uuid\" = 'p1'")) {
                assertTrue(r.next(), "the migration must keep the row written before it");
                assertEquals("Ada", r.getString(1), "the migration must keep the row's values");
                assertNull(r.getObject(2), "an added column is NULL on existing rows");
            }
        }

        @Test
        @DisplayName("creates the child table of a new collection field")
        void addsNewChildTable() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, people(PersonV1.class));
            execute(db, "INSERT INTO \"people\" (\"uuid\", \"name\") VALUES ('p1', 'Ada')");
            ensure(db, SchemaMode.CREATE, people(PersonV2.class));
            assertCompleteSchema(db, people(PersonV2.class));
            execute(db, "INSERT INTO \"people__tags\" (\"_owner\", \"_ord\", \"value\") VALUES ('p1', 0, 'x')");
            assertEquals(1, count(db, "people__tags"), "the new child table must accept the old row's elements");
        }

        @Test
        @DisplayName("adds a value column a child table gained")
        void addsChildColumn() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            PgTable table = orders();
            ensure(db, SchemaMode.CREATE, table);
            execute(db, "ALTER TABLE \"orders__lines\" DROP COLUMN \"price\"");
            ensure(db, SchemaMode.CREATE, table);
            assertEquals("numeric", columns(db, "orders__lines").get("price"),
                    "a missing child value column must be added back");
        }

        @Test
        @DisplayName("only warns on a mistyped column, and leaves it as it is")
        void mistypedColumnWarns() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            execute(db, "CREATE TABLE \"people\" (\"uuid\" TEXT PRIMARY KEY, \"name\" INTEGER)");
            assertDoesNotThrow(() -> ensure(db, SchemaMode.CREATE, people(PersonV1.class)),
                    "a type mismatch must not stop CREATE mode");
            assertEquals("integer", columns(db, "people").get("name"), "CREATE mode must never retype");
            assertEquals(1, warnings.size(), () -> "exactly one WARN expected: " + warnings);
            String message = warnings.get(0).message();
            for (String part : List.of("people", "name", "integer", "TEXT")) {
                assertTrue(message.contains(part), () -> "the WARN must name " + part + ": " + message);
            }
        }

        @Test
        @DisplayName("rolls everything back when it fails midway: nothing is left half-created")
        void failureLeavesNothing() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            // A foreign child table without the structural columns: the main table gets created, then
            // the migration of that child fails.
            execute(db, "CREATE TABLE \"orders__labels\" (\"x\" INTEGER)");
            ApiException error = assertThrows(ApiException.class, () -> ensure(db, SchemaMode.CREATE, orders()));
            assertTrue(error.getMessage().contains("orders__labels") && error.getMessage().contains("_owner"),
                    () -> "the error must name the table and the missing structural column: " + error.getMessage());
            assertTrue(columns(db, "orders").isEmpty(),
                    "the main table created before the failure must be rolled back");
        }

        @Test
        @DisplayName("lets several instances start together on the same database")
        void concurrentEnsureSucceeds() throws Exception {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int round = 0; round < 4; round++) {
                    DataSource db = PgTestDatabase.freshDatabase();
                    CyclicBarrier start = new CyclicBarrier(threads);
                    List<Future<Void>> runs = new ArrayList<>();
                    for (int i = 0; i < threads; i++) {
                        Callable<Void> run = () -> {
                            start.await(30, TimeUnit.SECONDS);
                            ensure(db, SchemaMode.CREATE, orders());
                            return null;
                        };
                        runs.add(pool.submit(run));
                    }
                    for (Future<Void> run : runs) {
                        assertDoesNotThrow(() -> run.get(60, TimeUnit.SECONDS),
                                "every concurrent ensure() must succeed");
                    }
                    assertCompleteSchema(db, orders());
                }
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("refuses a geometry model without PostGIS with an actionable message, creating nothing")
        void geometryNeedsPostgis() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ApiException error = assertThrows(ApiException.class, () -> ensure(db, SchemaMode.CREATE, places()));
            String message = error.getMessage();
            for (String part : List.of("places", "location", "PostGIS", "CREATE EXTENSION")) {
                assertTrue(message.contains(part), () -> "the PostGIS error must mention " + part + ": " + message);
            }
            assertTrue(error.getCause() instanceof SQLException, "the engine's error must stay as the cause");
            assertTrue(columns(db, "places").isEmpty(), "nothing may be created when PostGIS is missing");
        }
    }

    @Nested
    @DisplayName("generated statements")
    class Statements {

        @Test
        @DisplayName("of a geometry model start with the PostGIS extension")
        void geometryModelCreatesExtensionFirst() {
            List<String> statements = PgSchemaExpectation.createStatements(places());
            assertEquals("CREATE EXTENSION IF NOT EXISTS postgis", statements.get(0),
                    "the extension must exist before a geometry column is declared");
            assertTrue(statements.get(1).contains("\"location\" geometry(Geometry, 4326)"),
                    () -> "the geometry column must be declared as a PostGIS geometry: " + statements.get(1));
        }

        @Test
        @DisplayName("of a model without geometry do not touch extensions")
        void plainModelHasNoExtension() {
            assertTrue(PgSchemaExpectation.createStatements(orders()).stream()
                    .noneMatch(s -> s.contains("EXTENSION")), "no extension without a geometry field");
        }

        @Test
        @DisplayName("use a stable, table-specific advisory lock key")
        void lockKeyIsStable() {
            assertEquals(PgSchemaManager.lockKey("orders"), PgSchemaManager.lockKey("orders"),
                    "every instance must derive the same key for a table");
            assertTrue(PgSchemaManager.lockKey("orders") != PgSchemaManager.lockKey("people"),
                    "two tables must not share a lock");
        }
    }

    @Nested
    @DisplayName("in VALIDATE mode")
    class Validate {

        @Test
        @DisplayName("refuses an empty database, listing the missing DDL, and creates nothing")
        void refusesEmptyDatabase() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            PgTable table = orders();
            ApiException error = assertThrows(ApiException.class, () -> ensure(db, SchemaMode.VALIDATE, table));
            String message = error.getMessage();
            for (String ddl : PgSchemaExpectation.createStatements(table)) {
                assertTrue(message.contains(ddl), () -> "the report must contain " + ddl + ":\n" + message);
            }
            assertTrue(columns(db, "orders").isEmpty(), "VALIDATE must issue no DDL");
        }

        @Test
        @DisplayName("passes on a complete schema")
        void passesOnCompleteSchema() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, orders());
            assertDoesNotThrow(() -> ensure(db, SchemaMode.VALIDATE, orders()),
                    "a schema CREATE mode built must validate");
        }

        @Test
        @DisplayName("lists every problem at once: missing column, missing child table, wrong type")
        void listsAllProblems() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            execute(db, "CREATE TABLE \"people\" (\"uuid\" TEXT PRIMARY KEY, \"name\" INTEGER)");
            ApiException error = assertThrows(ApiException.class,
                    () -> ensure(db, SchemaMode.VALIDATE, people(PersonV2.class)));
            String message = error.getMessage();
            for (String part : List.of(
                    "ALTER TABLE \"people\" ADD COLUMN IF NOT EXISTS \"age\" INTEGER",
                    "CREATE TABLE IF NOT EXISTS \"people__tags\"",
                    "\"people\".\"name\" is integer, the model expects TEXT",
                    // the fourth: the presence column of the "tags" collection, also missing
                    "ALTER TABLE \"people\" ADD COLUMN IF NOT EXISTS \"tags\" BOOLEAN",
                    "4 problem(s)")) {
                assertTrue(message.contains(part), () -> "the report must contain " + part + ":\n" + message);
            }
            assertFalse(columns(db, "people").containsKey("age"), "VALIDATE must not add the column");
        }

        @Test
        @DisplayName("reports the missing PostGIS extension of a geometry model")
        void reportsMissingPostgis() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ApiException error = assertThrows(ApiException.class, () -> ensure(db, SchemaMode.VALIDATE, places()));
            assertTrue(error.getMessage().contains("CREATE EXTENSION IF NOT EXISTS postgis;"),
                    () -> "the report must give the extension DDL:\n" + error.getMessage());
        }
    }

    @Nested
    @DisplayName("type comparison")
    class Types {

        private boolean matches(String model, String dataType, String udt) {
            return PgSchemaInspector.matches(model, new PgSchemaInspector.StoredColumn(dataType, udt));
        }

        @Test
        @DisplayName("maps DDL spellings to what information_schema reports")
        void mapsSpellings() {
            assertTrue(matches("TIMESTAMPTZ", "timestamp with time zone", "timestamptz"));
            assertTrue(matches("TIMESTAMP", "timestamp without time zone", "timestamp"));
            assertTrue(matches("DOUBLE PRECISION", "double precision", "float8"));
            assertTrue(matches("NUMERIC", "numeric", "numeric"));
            assertTrue(matches("JSONB", "jsonb", "jsonb"));
            assertTrue(matches("geometry(Geometry, 4326)", "USER-DEFINED", "geometry"));
        }

        @Test
        @DisplayName("tells different types apart")
        void detectsMismatch() {
            assertFalse(matches("BIGINT", "integer", "int4"));
            assertFalse(matches("TIMESTAMPTZ", "timestamp without time zone", "timestamp"));
            assertFalse(matches("geometry(Geometry, 4326)", "USER-DEFINED", "geography"));
            assertFalse(matches("JSONB", "json", "json"));
        }

        @Test
        @DisplayName("agrees with the real engine for every type the model can produce")
        void agreesWithEngine() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            ensure(db, SchemaMode.CREATE, orders());
            try (Connection c = db.getConnection()) {
                for (PgSchemaExpectation.Table table : PgSchemaExpectation.of(orders())) {
                    var stored = PgSchemaInspector.columns(c, table.name());
                    for (PgSchemaExpectation.Column column : table.columns()) {
                        assertTrue(PgSchemaInspector.matches(column.sqlType(), stored.get(column.name())),
                                () -> table.name() + "." + column.name() + " declared " + column.sqlType()
                                        + " but compared unequal to " + stored.get(column.name()));
                    }
                }
            }
        }
    }

    private static int count(DataSource db, String table) throws SQLException {
        try (Connection c = db.getConnection();
                PreparedStatement p = c.prepareStatement("SELECT count(*) FROM " + '"' + table + '"');
                ResultSet r = p.executeQuery()) {
            r.next();
            return r.getInt(1);
        }
    }
}
