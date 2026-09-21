package com.garganttua.dao.postgresql;

import static com.garganttua.dao.postgresql.PgQueryFixture.field;
import static com.garganttua.dao.postgresql.PgQueryFixture.listed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Filters, sorts and text search on paths crossing SEVERAL child tables, proved on the real engine.
 *
 * <p>
 * The rows are inserted with plain SQL following the nested-table DDL ({@code _id} on a table with
 * children, {@code _parent} on a nested one) — the writer is not the subject here. The data and every
 * expected answer are those of the parity suite ({@code ParityNestedDepthTest}), where MongoDB itself
 * gave them: s = shops ({@code orders.lines}), t = taggeds ({@code lines.tags}), w = depots
 * ({@code stock.<key>.origin.labels}), k = catalogs ({@code shelf.byGenre.<key>} a list of books),
 * p = palettes ({@code lines.attrs.<key>}); g = grids, a {@code List<List<String>>}.
 * </p>
 */
@DisplayName("PgQueryBuilder — paths through nested child tables")
class PgNestedQueryTest {

    public static class OLine {
        private String sku;
        private Integer qty;
    }

    public static class Order {
        private String ref;
        private List<OLine> lines;
    }

    public static class Shop {
        private String uuid;
        private List<Order> orders;
    }

    public static class TLine {
        private String sku;
        private List<String> tags;
    }

    public static class Tagged {
        private String uuid;
        private List<TLine> lines;
    }

    public static class Origin {
        private String country;
        private List<String> labels;
    }

    public static class Stock {
        private Integer qty;
        private Origin origin;
    }

    public static class Depot {
        private String uuid;
        private Map<String, Stock> stock;
    }

    public static class Book {
        private String title;
        private Integer year;
    }

    public static class Shelf {
        private String name;
        private Map<String, List<Book>> byGenre;
    }

    public static class Catalog {
        private String uuid;
        private Shelf shelf;
    }

    public static class Attr {
        private String hex;
        private Integer weight;
    }

    public static class ALine {
        private String sku;
        private Map<String, Attr> attrs;
    }

    public static class Palette {
        private String uuid;
        private List<ALine> lines;
    }

    public static class Grid {
        private String uuid;
        private List<List<String>> matrix;
    }

    private static DataSource db;

    @BeforeAll
    static void seed() throws Exception {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
        db = PgTestDatabase.freshDatabase();
        try (Connection c = db.getConnection()) {
            shops(c);
            taggeds(c);
            depots(c);
            catalogs(c);
            palettes(c);
            grids(c);
        }
    }

    private static PgTable model(String domain, Class<?> dto) {
        return PgSchemaModel.of(domain, IClass.getClass(dto), "uuid", Map.of());
    }

    private static void create(Connection c, String domain, Class<?> dto) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String ddl : PgDdl.create(model(domain, dto))) {
                s.execute(ddl);
            }
        }
    }

    /** Inserts consecutive rows: {@code values} is flat, as many values per row as the SQL has marks. */
    private static void rows(Connection c, String sql, Object... values) throws SQLException {
        int width = (int) sql.chars().filter(ch -> ch == '?').count();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int row = 0; row < values.length / width; row++) {
                for (int i = 0; i < width; i++) {
                    p.setObject(i + 1, values[row * width + i]);
                }
                p.executeUpdate();
            }
        }
    }

    private static final Boolean T = Boolean.TRUE;

    /** s1 [R1[K1/1,K2/2], R2[K3/3]] · s2 [R3 []] · s3 [R4 null] · s4 [] · s5 null · s6 [R6[K2/9], R7[{null}]]. */
    private static void shops(Connection c) throws SQLException {
        create(c, "shops", Shop.class);
        rows(c, "INSERT INTO shops (uuid, orders) VALUES (?, ?)", "s1", T, "s2", T, "s3", T, "s4", T, "s5", null,
                "s6", T);
        rows(c, "INSERT INTO shops__orders (_id, _owner, _ord, _present, ref, lines) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?, ?, ?, ?, ?)", 1, "s1", 0, T, "R1", T, 2, "s1", 1, T, "R2", T, 3, "s2", 0, T, "R3", T,
                4, "s3", 0, T, "R4", null, 5, "s6", 0, T, "R6", T, 6, "s6", 1, T, "R7", T);
        rows(c, "INSERT INTO shops__orders__lines (_owner, _parent, _ord, _present, sku, qty) VALUES (?, ?, ?, ?, ?, ?)",
                "s1", 1, 0, T, "K1", 1, "s1", 1, 1, T, "K2", 2, "s1", 2, 0, T, "K3", 3, "s6", 5, 0, T, "K2", 9,
                "s6", 6, 0, T, null, null);
    }

    /** t1 [A[x,y], B[z]] · t2 [C[]] · t3 [D null] · t4 [] · t5 null · t6 [E[y]]. */
    private static void taggeds(Connection c) throws SQLException {
        create(c, "taggeds", Tagged.class);
        rows(c, "INSERT INTO taggeds (uuid, lines) VALUES (?, ?)", "t1", T, "t2", T, "t3", T, "t4", T, "t5", null,
                "t6", T);
        rows(c, "INSERT INTO taggeds__lines (_id, _owner, _ord, _present, sku, tags) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?, ?, ?, ?, ?)", 1, "t1", 0, T, "A", T, 2, "t1", 1, T, "B", T, 3, "t2", 0, T, "C", T,
                4, "t3", 0, T, "D", null, 5, "t6", 0, T, "E", T);
        rows(c, "INSERT INTO taggeds__lines__tags (_owner, _parent, _ord, value) VALUES (?, ?, ?, ?)",
                "t1", 1, 0, "x", "t1", 1, 1, "y", "t1", 2, 0, "z", "t6", 5, 0, "y");
    }

    /** w1 {paris 5 FR [bio,local], lyon 2 FR []} · w2 {paris 1 IT null} · w3 {paris 3 null} · w4 {} · w5 null · w6 {lyon 7 ES [bio]}. */
    private static void depots(Connection c) throws SQLException {
        create(c, "depots", Depot.class);
        rows(c, "INSERT INTO depots (uuid, stock) VALUES (?, ?)", "w1", T, "w2", T, "w3", T, "w4", T, "w5", null,
                "w6", T);
        rows(c, "INSERT INTO depots__stock (_id, _owner, _key, _present, qty, origin, origin__country,"
                + " origin__labels) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                1, "w1", "paris", T, 5, T, "FR", T, 2, "w1", "lyon", T, 2, T, "FR", T,
                3, "w2", "paris", T, 1, T, "IT", null, 4, "w3", "paris", T, 3, null, null, null,
                5, "w6", "lyon", T, 7, T, "ES", T);
        rows(c, "INSERT INTO depots__stock__origin__labels (_owner, _parent, _ord, value) VALUES (?, ?, ?, ?)",
                "w1", 1, 0, "bio", "w1", 1, 1, "local", "w6", 5, 0, "bio");
    }

    /** k1 {sf [Dune/1965, Hyperion/1989], noir [BigSleep]} · k2 {sf []} · k3 {} · k4 map null · k5 shelf null · k6 {sf [Dune/null]}. */
    private static void catalogs(Connection c) throws SQLException {
        create(c, "catalogs", Catalog.class);
        rows(c, "INSERT INTO catalogs (uuid, shelf, shelf__name, \"shelf__byGenre\") VALUES (?, ?, ?, ?)",
                "k1", T, "main", T, "k2", T, "side", T, "k3", T, "empty", T, "k4", T, "nomap", null,
                "k5", null, null, null, "k6", T, "x", T);
        rows(c, "INSERT INTO \"catalogs__shelf__byGenre\" (_id, _owner, _key, _present) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?, ?, ?)", 1, "k1", "sf", T, 2, "k1", "noir", T, 3, "k2", "sf", T, 4, "k6", "sf", T);
        rows(c, "INSERT INTO \"catalogs__shelf__byGenre___e\" (_owner, _parent, _ord, _present, title, year)"
                + " VALUES (?, ?, ?, ?, ?, ?)", "k1", 1, 0, T, "Dune", 1965, "k1", 1, 1, T, "Hyperion", 1989,
                "k1", 2, 0, T, "BigSleep", 1939, "k6", 4, 0, T, "Dune", null);
    }

    /** p1 [S1 {color #fff/1, size #000/2}] · p2 [S2 {size #fff/3}] · p3 [S3 {}] · p4 [S4 null] · p5 [S5 {color null}] · p6 null. */
    private static void palettes(Connection c) throws SQLException {
        create(c, "palettes", Palette.class);
        rows(c, "INSERT INTO palettes (uuid, lines) VALUES (?, ?)", "p1", T, "p2", T, "p3", T, "p4", T, "p5", T,
                "p6", null);
        rows(c, "INSERT INTO palettes__lines (_id, _owner, _ord, _present, sku, attrs) OVERRIDING SYSTEM VALUE"
                + " VALUES (?, ?, ?, ?, ?, ?)", 1, "p1", 0, T, "S1", T, 2, "p2", 0, T, "S2", T, 3, "p3", 0, T, "S3", T,
                4, "p4", 0, T, "S4", null, 5, "p5", 0, T, "S5", T);
        rows(c, "INSERT INTO palettes__lines__attrs (_owner, _parent, _key, _present, hex, weight)"
                + " VALUES (?, ?, ?, ?, ?, ?)", "p1", 1, "color", T, "#fff", 1, "p1", 1, "size", T, "#000", 2,
                "p2", 2, "size", T, "#fff", 3, "p5", 5, "color", null, null, null);
    }

    /** g1 [[a,b],[],[c]] · g2 null · g3 [] · g4 [[a]]. */
    private static void grids(Connection c) throws SQLException {
        create(c, "grids", Grid.class);
        rows(c, "INSERT INTO grids (uuid, matrix) VALUES (?, ?)", "g1", T, "g2", null, "g3", T, "g4", T);
        rows(c, "INSERT INTO grids__matrix (_id, _owner, _ord, _present) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)",
                1, "g1", 0, T, 2, "g1", 1, T, 3, "g1", 2, T, 4, "g4", 0, T);
        rows(c, "INSERT INTO grids__matrix___e (_owner, _parent, _ord, value) VALUES (?, ?, ?, ?)",
                "g1", 1, 0, "a", "g1", 1, 1, "b", "g1", 3, 0, "c", "g4", 4, 0, "a");
    }

    private static final Map<String, Class<?>> DOMAINS = Map.of("shops", Shop.class, "taggeds", Tagged.class,
            "depots", Depot.class, "catalogs", Catalog.class, "palettes", Palette.class, "grids", Grid.class);

    private static PgQueryBuilder builder(String domain) {
        Class<?> dto = DOMAINS.get(domain);
        return new PgQueryBuilder(model(domain, dto), IClass.getClass(dto));
    }

    private static List<String> run(String domain, PgQuery q) throws SQLException {
        String sql = "SELECT t.uuid FROM " + domain + " t WHERE " + q.where() + " " + q.orderBy();
        List<String> ids = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < q.params().size(); i++) {
                p.setObject(i + 1, q.params().get(i));
            }
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
        }
        return ids;
    }

    private static void assertMatches(String domain, IFilter filter, String... expected) throws Exception {
        PgQuery q = builder(domain).build(Optional.empty(), Optional.of(filter), Optional.empty(), Optional.empty());
        assertEquals(Set.of(expected), new HashSet<>(run(domain, q)), () -> "wrong rows; WHERE was: " + q.where());
    }

    /** Every row, sorted, ties broken by uuid (a page of size 0 has no limit). */
    private static List<String> sorted(String domain, String path, SortDirection direction) throws Exception {
        return run(domain, builder(domain).build(Optional.of(new Pageable(0, 0)), Optional.empty(),
                Optional.of(new Sort(path, direction)), Optional.empty()));
    }

    @Nested
    @DisplayName("List<POJO> > List<POJO>: orders.lines.sku crosses two arrays")
    class TwoArrays {

        @Test
        @DisplayName("positive operators: SOME order has SOME line matching")
        void positive() throws Exception {
            assertMatches("shops", field("orders.lines.sku", "$eq", "K2"), "s1", "s6");
            assertMatches("shops", listed("orders.lines.sku", "$in", "K1", "K3"), "s1");
            assertMatches("shops", field("orders.lines.qty", "$gt", 2), "s1", "s6");
            assertMatches("shops", field("orders.lines.sku", "$regex", "^K"), "s1", "s6");
        }

        @Test
        @DisplayName("negations: NO order has a line matching — an absent or empty level included")
        void negative() throws Exception {
            assertMatches("shops", field("orders.lines.sku", "$ne", "K2"), "s2", "s3", "s4", "s5");
            assertMatches("shops", listed("orders.lines.sku", "$nin", "K2"), "s2", "s3", "s4", "s5");
        }

        @Test
        @DisplayName("null: a missing level on SOME branch matches $eq null; an empty one does not")
        void nulls() throws Exception {
            assertMatches("shops", field("orders.lines.sku", "$eq", null), "s3", "s5", "s6");
            assertMatches("shops", field("orders.lines.sku", "$ne", null), "s1", "s2", "s4");
            assertMatches("shops", field("orders.lines.sku", "$empty", null), "s2", "s3", "s4", "s5");
            assertMatches("shops", field("orders.lines", "$empty", null), "s3", "s4", "s5");
        }

        @Test
        @DisplayName("the SQL is one EXISTS per level, the nested one tied to its parent row")
        void shape() throws Exception {
            String where = builder("shops").count(field("orders.lines.sku", "$eq", "K2")).where();
            assertTrue(where.contains("c2.\"_parent\" = c.\"_id\""), where);
            assertFalse(where.contains("K2"), "values are bound, never inlined: " + where);
        }

        @Test
        @DisplayName("sort: smallest (asc) / largest (desc) over every line of every order, missing first")
        void sort() throws Exception {
            assertEquals(List.of("s2", "s3", "s4", "s5", "s6", "s1"), sorted("shops", "orders.lines.qty",
                    SortDirection.asc));
            assertEquals(List.of("s6", "s1", "s2", "s3", "s4", "s5"), sorted("shops", "orders.lines.qty",
                    SortDirection.desc));
        }

        @Test
        @DisplayName("a whole collection nested in a collection is not sortable")
        void sortRefused() {
            assertThrows(ApiException.class, () -> sorted("shops", "orders.lines", SortDirection.asc));
            assertThrows(ApiException.class, () -> sorted("shops", "orders", SortDirection.asc));
        }

        @Test
        @DisplayName("$text sees the strings of nested tables")
        void text() throws Exception {
            assertMatches("shops", field("uuid", "$text", "K3"), "s1");
        }
    }

    @Nested
    @DisplayName("List<POJO> > List<String>: lines.tags")
    class ScalarsInElements {

        @Test
        @DisplayName("SOME line has SOME tag; $ne / $nin: none has")
        void compare() throws Exception {
            assertMatches("taggeds", field("lines.tags", "$eq", "x"), "t1");
            assertMatches("taggeds", field("lines.tags", "$ne", "x"), "t2", "t3", "t4", "t5", "t6");
            assertMatches("taggeds", listed("lines.tags", "$in", "z", "y"), "t1", "t6");
            assertMatches("taggeds", listed("lines.tags", "$nin", "y"), "t2", "t3", "t4", "t5");
        }

        @Test
        @DisplayName("$empty: no line holds tags; $eq null: some line (or the lines) misses them")
        void nulls() throws Exception {
            assertMatches("taggeds", field("lines.tags", "$empty", null), "t3", "t4", "t5");
            assertMatches("taggeds", field("lines.tags", "$eq", null), "t3", "t5");
            assertMatches("taggeds", field("lines.tags", "$ne", null), "t1", "t2", "t4", "t6");
        }

        @Test
        @DisplayName("a whole list compares with the tags of some line, exactly and in order")
        void wholeArray() throws Exception {
            assertMatches("taggeds", field("lines.tags", "$eq", List.of("x", "y")), "t1");
            assertMatches("taggeds", field("lines.tags", "$eq", List.of("y", "x")));
            assertMatches("taggeds", field("lines.tags", "$eq", List.of()), "t2");
        }

        @Test
        @DisplayName("sort on the nested tags: smallest / largest tag over every line")
        void sort() throws Exception {
            assertEquals(List.of("t2", "t3", "t4", "t5", "t1", "t6"), sorted("taggeds", "lines.tags",
                    SortDirection.asc));
            assertEquals(List.of("t1", "t6", "t2", "t3", "t4", "t5"), sorted("taggeds", "lines.tags",
                    SortDirection.desc));
        }
    }

    @Nested
    @DisplayName("Map<String,POJO> > POJO > List<String>: stock.<key>.origin.labels")
    class ListInMapValue {

        @Test
        @DisplayName("the key narrows the entry, the list below is crossed element by element")
        void compare() throws Exception {
            assertMatches("depots", field("stock.paris.origin.labels", "$eq", "bio"), "w1");
            assertMatches("depots", field("stock.paris.origin.labels", "$ne", "bio"), "w2", "w3", "w4", "w5", "w6");
            assertMatches("depots", field("stock.lyon.origin.labels", "$eq", "bio"), "w6");
            assertMatches("depots", field("stock.paris.origin.country", "$eq", "FR"), "w1");
            assertMatches("depots", field("stock.paris.origin.labels", "$empty", null), "w2", "w3", "w4", "w5", "w6");
        }

        @Test
        @DisplayName("sort on a map entry: its value, missing where the key is absent")
        void sort() throws Exception {
            assertEquals(List.of("w4", "w5", "w6", "w2", "w3", "w1"), sorted("depots", "stock.paris.qty",
                    SortDirection.asc));
            assertEquals(List.of("w1", "w3", "w2", "w4", "w5", "w6"), sorted("depots", "stock.paris.qty",
                    SortDirection.desc));
            // Sub-documents holding a nested list: their BSON order is not reproduced, so refused.
            assertThrows(ApiException.class, () -> sorted("depots", "stock.paris.origin", SortDirection.asc));
            assertThrows(ApiException.class, () -> sorted("depots", "stock.paris", SortDirection.asc));
        }

        @Test
        @DisplayName("$text sees a list nested in a map value")
        void text() throws Exception {
            assertMatches("depots", field("uuid", "$text", "local"), "w1");
        }
    }

    @Nested
    @DisplayName("POJO > Map<String, List<POJO>>: shelf.byGenre.<key>.title")
    class ListAsMapValue {

        @Test
        @DisplayName("the entry's value is a list: crossed through the element table")
        void compare() throws Exception {
            assertMatches("catalogs", field("shelf.byGenre.sf.title", "$eq", "Dune"), "k1", "k6");
            assertMatches("catalogs", field("shelf.byGenre.sf.title", "$ne", "Dune"), "k2", "k3", "k4", "k5");
            assertMatches("catalogs", field("shelf.byGenre.sf.year", "$gt", 1970), "k1");
        }

        @Test
        @DisplayName("missing: the key absent, or a book without year; an empty list is present")
        void nulls() throws Exception {
            assertMatches("catalogs", field("shelf.byGenre.sf.year", "$eq", null), "k3", "k4", "k5", "k6");
            assertMatches("catalogs", field("shelf.byGenre.sf", "$empty", null), "k3", "k4", "k5");
            assertMatches("catalogs", field("shelf.byGenre", "$empty", null), "k4", "k5");
        }

        @Test
        @DisplayName("sort through the key and the list; a map holding lists is not sortable whole")
        void sort() throws Exception {
            assertEquals(List.of("k2", "k3", "k4", "k5", "k6", "k1"), sorted("catalogs", "shelf.byGenre.sf.year",
                    SortDirection.asc));
            assertEquals(List.of("k1", "k2", "k3", "k4", "k5", "k6"), sorted("catalogs", "shelf.byGenre.sf.year",
                    SortDirection.desc));
            assertThrows(ApiException.class, () -> sorted("catalogs", "shelf.byGenre", SortDirection.asc));
        }
    }

    @Nested
    @DisplayName("List<POJO> > Map<String,POJO>: lines.attrs.<key>.hex")
    class MapInElements {

        @Test
        @DisplayName("SOME line has the key with a matching value")
        void compare() throws Exception {
            assertMatches("palettes", field("lines.attrs.color.hex", "$eq", "#fff"), "p1");
            assertMatches("palettes", field("lines.attrs.color.hex", "$ne", "#fff"), "p2", "p3", "p4", "p5", "p6");
            assertMatches("palettes", field("lines.attrs.size.weight", "$gt", 2), "p2");
        }

        @Test
        @DisplayName("an entry: missing or null on some line ($eq null), absent on every line ($empty)")
        void entries() throws Exception {
            assertMatches("palettes", field("lines.attrs.color", "$eq", null), "p2", "p3", "p4", "p5", "p6");
            assertMatches("palettes", field("lines.attrs.color", "$empty", null), "p2", "p3", "p4", "p6");
            assertMatches("palettes", field("lines.attrs", "$empty", null), "p4", "p6");
        }
    }

    @Nested
    @DisplayName("List<List<String>>: an element that is itself an array")
    class ArraysOfArrays {

        @Test
        @DisplayName("a list matches an equal element; a scalar matches none (MongoDB does not look inside)")
        void compare() throws Exception {
            assertMatches("grids", field("matrix", "$eq", List.of("a", "b")), "g1");
            assertMatches("grids", field("matrix", "$eq", List.of()), "g1", "g3");
            assertMatches("grids", field("matrix", "$eq", "a"));
            assertMatches("grids", listed("matrix", "$in", List.of("c"), List.of("a")), "g1", "g4");
            assertMatches("grids", field("matrix", "$ne", List.of("a")), "g1", "g2", "g3");
        }

        @Test
        @DisplayName("presence: $eq null and $empty are the absent matrix")
        void nulls() throws Exception {
            assertMatches("grids", field("matrix", "$eq", null), "g2");
            assertMatches("grids", field("matrix", "$empty", null), "g2");
        }
    }
}
