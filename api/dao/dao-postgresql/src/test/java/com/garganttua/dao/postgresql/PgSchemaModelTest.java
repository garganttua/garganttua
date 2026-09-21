package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The relational shape a DTO gets — the contract every other part of the DAO reads.
 */
@DisplayName("The schema model of a DTO")
class PgSchemaModelTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public enum Status { ACTIVE, CLOSED }

    public static class Address {
        private String street;
        private String city;
        private int zip;
        private List<String> tags;
    }

    public static class Line {
        private String sku;
        private BigDecimal price;
    }

    /** A POJO that contains itself: no finite set of columns can hold it. */
    public static class Node {
        private String label = "root";
        private Node self;
    }

    /** GeoJSON-shaped but not a GeoJSON type: its coordinates are an untyped nested list. */
    public static class GeoShape {
        private String type;
        private List<Object> coordinates;
    }

    public static class Order {
        private String uuid;
        private String id;
        private String order;              // a reserved word, as a field name
        private Status status;
        private long total;
        private Instant createdAt;
        private byte[] signature;
        private Address address;           // flattened
        private List<String> labels;       // scalar child table
        private List<Line> lines;          // POJO child table
        private Map<String, Integer> stock;// map child table
        private Node node;                 // recursive → JSONB
        private GeoShape geo;              // flattened, with a JSONB inner column
        private Object anything;           // untyped → JSONB
        private String customer;           // @Composed
        private List<String> relatedOrders;// @Composed collection
        private static String IGNORED = "static";
        private transient String scratch;
    }

    public static class Shop {
        private String uuid;
        private List<ShopOrder> orders;
    }

    public static class ShopOrder {
        private String ref;
        private List<ShopLine> lines;
    }

    public static class ShopLine {
        private String sku;
        private List<String> tags;
    }

    public static class Grid {
        private String uuid;
        private List<List<Integer>> rows;
        private Map<String, List<Line>> byGenre;
    }

    private static PgTable model() {
        return PgSchemaModel.of("orders", IClass.getClass(Order.class), "uuid",
                Map.of("customer", "customers", "relatedOrders", "orders"));
    }

    private static PgColumn column(PgTable table, String path) {
        return table.column(path).orElseThrow(() -> new AssertionError("no column for " + path));
    }

    private static PgChildTable child(PgTable table, String path) {
        return table.child(path).orElseThrow(() -> new AssertionError("no child table for " + path));
    }

    @Nested
    @DisplayName("maps")
    class Mapping {

        @Test
        @DisplayName("the uuid to the primary key, first")
        void uuidIsThePrimaryKey() {
            PgTable table = model();
            assertEquals("uuid", table.id().name());
            assertEquals(PgColumnKind.ID, table.id().kind());
            assertEquals(table.id(), table.columns().get(0));
        }

        @Test
        @DisplayName("scalars to typed columns, reserved words included")
        void scalarsGetTypedColumns() {
            PgTable table = model();
            assertEquals("BIGINT", column(table, "total").sqlType());
            assertEquals("TIMESTAMPTZ", column(table, "createdAt").sqlType());
            assertEquals("BYTEA", column(table, "signature").sqlType());
            assertEquals("TEXT", column(table, "status").sqlType(), "an enum is stored by name");
            assertEquals("TEXT", column(table, "order").sqlType());
        }

        @Test
        @DisplayName("an embedded POJO to prefixed columns, its collections to child tables")
        void embeddedPojoIsFlattened() {
            PgTable table = model();
            assertEquals("address__city", column(table, "address.city").name());
            assertEquals("INTEGER", column(table, "address.zip").sqlType());
            assertEquals(PgChildKind.SCALAR_COLLECTION, child(table, "address.tags").kind());
            assertEquals("orders__address__tags", child(table, "address.tags").name());
        }

        @Test
        @DisplayName("a collection inside a collection element to a NESTED child table, at any depth")
        void nestedCollectionsGetNestedTables() {
            PgTable table = PgSchemaModel.of("shops", IClass.getClass(Shop.class), "uuid", Map.of());
            PgChildTable orders = child(table, "orders");
            PgChildTable lines = child(table, "orders.lines");
            PgChildTable tags = child(table, "orders.lines.tags");
            assertTrue(orders.hasChildren() && !orders.nested(), "orders is top-level and has children");
            assertEquals(orders.name(), lines.parent(), "lines hangs under orders");
            assertEquals(List.of("lines"), lines.fieldPath(), "a nested table's field path is relative to its parent element");
            assertEquals("shops__orders__lines__tags", tags.name());
            assertEquals(lines.name(), tags.parent());
            assertTrue(lines.valueColumns().stream().anyMatch(c -> c.name().equals("tags")
                    && c.kind() == PgColumnKind.PRESENCE), "a nested collection has a presence bit on its parent element");
            assertEquals(List.of("shops__orders", "shops__orders__lines", "shops__orders__lines__tags"),
                    table.allChildren().stream().map(PgChildTable::name).toList(), "parents before children");
        }

        @Test
        @DisplayName("an element that IS a collection to a table holding the element itself")
        void collectionOfCollections() {
            PgTable table = PgSchemaModel.of("grids", IClass.getClass(Grid.class), "uuid", Map.of());
            PgChildTable rows = child(table, "rows");
            assertEquals(PgChildKind.NESTED_COLLECTION, rows.kind());
            PgChildTable cells = rows.elementTable().orElseThrow(() -> new AssertionError("no element table"));
            assertTrue(cells.fieldPath().isEmpty(), "the element table holds the element itself");
            assertEquals(PgChildKind.SCALAR_COLLECTION, cells.kind());
            PgChildTable byGenre = child(table, "byGenre");
            assertEquals(PgChildKind.MAP, byGenre.kind());
            assertEquals(PgChildKind.POJO_COLLECTION, byGenre.elementTable().orElseThrow().kind(),
                    "Map<String, List<Line>>: each map value is a table of Lines");
        }

        @Test
        @DisplayName("collections and maps to child tables")
        void collectionsGetChildTables() {
            PgTable table = model();
            assertEquals(PgChildKind.SCALAR_COLLECTION, child(table, "labels").kind());

            PgChildTable lines = child(table, "lines");
            assertEquals(PgChildKind.POJO_COLLECTION, lines.kind());
            assertEquals(List.of("_present", "sku", "price"),
                    lines.valueColumns().stream().map(PgColumn::name).toList(),
                    "a POJO element carries its own presence bit first, then its flattened fields");
            assertEquals("NUMERIC", lines.valueColumns().get(2).sqlType());

            PgChildTable stock = child(table, "stock");
            assertEquals(PgChildKind.MAP, stock.kind());
            assertEquals("INTEGER", stock.valueColumns().get(0).sqlType());
        }

        @Test
        @DisplayName("compositions to uuid references")
        void compositionsAreReferences() {
            PgTable table = model();
            assertEquals(PgColumnKind.COMPOSITION, column(table, "customer").kind());
            PgChildTable related = child(table, "relatedOrders");
            assertEquals(PgChildKind.COMPOSITION_COLLECTION, related.kind());
            assertEquals("orders", related.composedCollection());
            assertEquals("customers", table.compositionTarget("customer").orElseThrow(),
                    "a single reference must say which domain it points to, like a collection does");
            assertEquals("orders", table.compositionTarget("relatedOrders").orElseThrow());
        }

        @Test
        @DisplayName("shapes with no finite relational form to JSONB — and only those")
        void unshapeableFieldsGoToJsonb() {
            PgTable table = model();
            assertEquals(PgColumnKind.JSONB, column(table, "node").kind(), "Node contains itself");
            assertEquals(PgColumnKind.JSONB, column(table, "anything").kind(), "Object has no shape");
            assertEquals(PgColumnKind.SCALAR, column(table, "geo.type").kind(),
                    "GeoShape itself is finite and flattens");
            assertEquals(PgColumnKind.JSONB, column(table, "geo.coordinates").kind(),
                    "…but its List<Object> has no column type");
        }

        @Test
        @DisplayName("a presence bit for every structure — the fact relational storage would lose")
        void structuresCarryPresence() {
            PgTable table = model();
            for (String structure : List.of("address", "labels", "lines", "stock", "address.tags", "relatedOrders")) {
                assertEquals(PgColumnKind.PRESENCE, table.presence(structure)
                        .orElseThrow(() -> new AssertionError("no presence bit for " + structure)).kind());
            }
            assertTrue(table.column("address").isEmpty(),
                    "column() must never return a presence bit: a filter on 'address' is not a filter on it");
            assertTrue(table.presence("node").isEmpty(), "a JSONB field keeps null vs {} natively");
        }

        @Test
        @DisplayName("nothing for static or transient fields")
        void staticAndTransientAreSkipped() {
            PgTable table = model();
            assertTrue(table.column("IGNORED").isEmpty());
            assertTrue(table.column("scratch").isEmpty());
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refusals {

        public static class NoUuid {
            private String name;
        }

        @Test
        @DisplayName("a DTO without its uuid field")
        void aTableNeedsAKey() {
            assertThrows(IllegalArgumentException.class,
                    () -> PgSchemaModel.of("things", IClass.getClass(NoUuid.class), "uuid", Map.of()));
        }
    }

    @Nested
    @DisplayName("names")
    class Naming {

        @Test
        @DisplayName("keep a too-long identifier unique instead of letting PostgreSQL truncate it")
        void longNamesStayDistinct() {
            String a = PgNaming.column(List.of("a".repeat(40), "b".repeat(40)));
            String b = PgNaming.column(List.of("a".repeat(40), "c".repeat(40)));
            assertTrue(a.length() <= PgNaming.MAX_IDENTIFIER_BYTES);
            assertTrue(!a.equals(b), "two long paths sharing a prefix must not become one column");
        }

        @Test
        @DisplayName("quote so a reserved word is a valid column — checked against the real engine")
        void quotedReservedWordsWork() throws Exception {
            DataSource db = PgTestDatabase.freshDatabase();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (" + PgNaming.quote("order") + " TEXT, "
                        + PgNaming.quote("createdAt") + " TEXT)");
                s.execute("INSERT INTO t VALUES ('x', 'y')");
                try (ResultSet r = s.executeQuery("SELECT " + PgNaming.quote("createdAt") + " FROM t")) {
                    assertTrue(r.next());
                    assertEquals("y", r.getString(1));
                }
            }
        }
    }

    /** Sanity: the model's column set is what a reader can rely on. */
    @Test
    @DisplayName("gives every column a distinct name")
    void columnNamesAreDistinct() {
        PgTable table = model();
        Set<String> names = new java.util.HashSet<>();
        table.columns().forEach(c -> assertTrue(names.add(c.name()), "duplicate " + c.name()));
    }
}
