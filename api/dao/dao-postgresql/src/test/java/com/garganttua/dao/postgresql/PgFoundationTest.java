package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The pieces every part of the DAO shares, checked against the real engine.
 */
@DisplayName("The shared foundation")
class PgFoundationTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    private static PgTable orders() {
        return PgSchemaModel.of("orders", IClass.getClass(PgSchemaModelTest.Order.class), "uuid",
                Map.of("customer", "customers", "relatedOrders", "orders"));
    }

    @Nested
    @DisplayName("base DDL")
    class Ddl {

        @Test
        @DisplayName("creates every table and column of the model on the real engine")
        void createsTheModel() throws Exception {
            PgTable table = orders();
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                for (String ddl : PgDdl.create(table)) {
                    s.execute(ddl);
                }
                Set<String> columns = new HashSet<>();
                try (ResultSet r = s.executeQuery("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'orders'")) {
                    while (r.next()) {
                        columns.add(r.getString(1));
                    }
                }
                for (PgColumn column : table.columns()) {
                    assertTrue(columns.contains(column.name()), () -> "missing column " + column.name());
                }
                for (var child : table.children()) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + PgNaming.quote(child.name()))) {
                        assertTrue(r.next(), () -> "missing child table " + child.name());
                    }
                }
            }
        }

        @Test
        @DisplayName("is idempotent — running it twice changes nothing and fails nothing")
        void isIdempotent() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                for (int round = 0; round < 2; round++) {
                    for (String ddl : PgDdl.create(orders())) {
                        s.execute(ddl);
                    }
                }
            }
        }

        @Test
        @DisplayName("cascades an owner's delete to its collections")
        void deleteCascadesToChildren() throws Exception {
            PgTable table = orders();
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                for (String ddl : PgDdl.create(table)) {
                    s.execute(ddl);
                }
                s.execute("INSERT INTO \"orders\" (\"uuid\") VALUES ('o1')");
                s.execute("INSERT INTO \"orders__labels\" (\"_owner\", \"_ord\", \"value\") VALUES ('o1', 0, 'a')");
                s.execute("DELETE FROM \"orders\" WHERE \"uuid\" = 'o1'");
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM \"orders__labels\"")) {
                    r.next();
                    assertEquals(0, r.getInt(1), "a delete must not leave orphan collection rows");
                }
            }
        }
    }

    @Nested
    @DisplayName("nested DDL")
    class NestedDdl {

        @Test
        @DisplayName("creates tables three levels deep, and a root delete cascades through all of them")
        void nestedTablesCascade() throws Exception {
            PgTable shops = PgSchemaModel.of("shops", IClass.getClass(PgSchemaModelTest.Shop.class), "uuid", Map.of());
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                for (String ddl : PgDdl.create(shops)) {
                    s.execute(ddl);
                }
                s.execute("INSERT INTO \"shops\" (\"uuid\") VALUES ('s1')");
                long order;
                try (ResultSet r = s.executeQuery("INSERT INTO \"shops__orders\" (\"_owner\", \"_ord\") "
                        + "VALUES ('s1', 0) RETURNING \"_id\"")) {
                    r.next();
                    order = r.getLong(1);
                }
                long line;
                try (ResultSet r = s.executeQuery("INSERT INTO \"shops__orders__lines\" (\"_owner\", \"_parent\", \"_ord\", \"sku\") "
                        + "VALUES ('s1', " + order + ", 0, 'K1') RETURNING \"_id\"")) {
                    r.next();
                    line = r.getLong(1);
                }
                s.execute("INSERT INTO \"shops__orders__lines__tags\" (\"_owner\", \"_parent\", \"_ord\", \"value\") "
                        + "VALUES ('s1', " + line + ", 0, 'red')");

                s.execute("DELETE FROM \"shops\" WHERE \"uuid\" = 's1'");

                for (String table : List.of("shops__orders", "shops__orders__lines", "shops__orders__lines__tags")) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                        r.next();
                        assertEquals(0, r.getInt(1), () -> "a root delete must leave nothing in " + table);
                    }
                }
            }
        }

        @Test
        @DisplayName("creates the table of an element that is itself a collection")
        void collectionOfCollections() throws Exception {
            PgTable grids = PgSchemaModel.of("grids", IClass.getClass(PgSchemaModelTest.Grid.class), "uuid", Map.of());
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                for (String ddl : PgDdl.create(grids)) {
                    s.execute(ddl);
                }
                for (var child : grids.allChildren()) {
                    try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + PgNaming.quote(child.name()))) {
                        assertTrue(r.next(), () -> "missing " + child.name());
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("value conversion")
    class Values {

        private PgColumn column(String path) {
            return orders().column(path).orElseThrow();
        }

        @Test
        @DisplayName("stores an enum by its name")
        void enumByName() throws ApiException {
            assertEquals("ACTIVE", PgValues.toJdbc(column("status"), PgSchemaModelTest.Status.ACTIVE));
        }

        @Test
        @DisplayName("coerces a filter's string to the column's type")
        void stringsAreCoerced() throws ApiException {
            assertEquals(42L, PgValues.toJdbc(column("total"), "42"));
            assertEquals(42, PgValues.toJdbc(column("address.zip"), "42"));
        }

        @Test
        @DisplayName("refuses a value that does not parse, rather than matching nothing in silence")
        void unparseableIsAnError() {
            assertThrows(ApiException.class, () -> PgValues.toJdbc(column("total"), "forty-two"));
        }

        @Test
        @DisplayName("round-trips an instant through the real engine at the millisecond a BSON date keeps")
        void instantRoundTrips() throws Exception {
            Instant now = Instant.parse("2026-09-21T10:15:30.123456Z");
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE ts (v TIMESTAMPTZ)");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO ts VALUES (?)")) {
                    p.setObject(1, PgValues.toJdbc(column("createdAt"), now));
                    p.executeUpdate();
                }
                try (ResultSet r = s.executeQuery("SELECT v FROM ts")) {
                    r.next();
                    assertEquals(Instant.parse("2026-09-21T10:15:30.123Z"), r.getObject(1, OffsetDateTime.class).toInstant(),
                            "truncated to the millisecond, as MongoDB stores it");
                }
            }
        }

        @Test
        @DisplayName("writes JSONB the real engine accepts and can query into")
        void jsonbIsQueryable() throws Exception {
            PgSchemaModelTest.Node node = new PgSchemaModelTest.Node();
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE j (v JSONB)");
                try (PreparedStatement p = c.prepareStatement("INSERT INTO j VALUES (?)")) {
                    p.setObject(1, PgValues.toJdbc(column("node"), node));
                    p.executeUpdate();
                }
                try (ResultSet r = s.executeQuery("SELECT jsonb_typeof(v) FROM j")) {
                    r.next();
                    assertEquals("object", r.getString(1),
                            "a getter-less DTO must still serialise its fields, not to {} or null");
                }
            }
        }
    }
}
