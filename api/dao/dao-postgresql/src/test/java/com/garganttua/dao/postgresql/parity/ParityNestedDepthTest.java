package com.garganttua.dao.postgresql.parity;

import static com.garganttua.dao.postgresql.parity.ParityFilter.field;
import static com.garganttua.dao.postgresql.parity.ParityFilter.listed;
import static com.garganttua.dao.postgresql.parity.ParityFilter.logical;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.postgresql.PgJson;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Parity of nesting DEPTH: DTOs nested three to five levels, through embedded POJOs, lists of POJOs,
 * lists inside list elements, maps of POJOs and a recursive type — round trip, filters on deep dotted
 * paths, sort on a deep path, projection of an intermediate path, {@code $empty} / {@code $eq null} /
 * {@code $ne null} on intermediate levels, and updates (save again after a deep change).
 *
 * <p>
 * Every shape's round-trip test also prints, prefixed {@code [STORAGE]}, the tables and columns
 * PostgreSQL created for it (from {@code information_schema}), so the report can say per level whether
 * it became a flattened column, a child table or a JSONB value. That print is a record, not an
 * assertion.
 * </p>
 *
 * <p>
 * Seeds discriminate: each shape has rows that must match, rows that must not, a null at each level,
 * an empty object, an empty collection. Filters compare WHICH rows matched (uuids); round trips and
 * projections compare the full DTOs; sorts compare the sequence of sort keys plus the set of rows
 * (tie order is not part of either engine's contract).
 * </p>
 */
@DisplayName("Parity — nesting depth (POJO / List / Map / recursive, 3 to 5 levels)")
class ParityNestedDepthTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ================================================================== helpers

    @SafeVarargs
    private static <T> List<T> list(T... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> typed(Map<String, Object> m) {
        return m == null ? null : (Map<String, V>) (Map<String, ?>) m;
    }

    /** The same outcome, each returned DTO reduced to its uuid. */
    private static Outcome uuids(Outcome o) {
        return new Outcome(ids(o.mongo()), ids(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object ids(Object result) {
        if (!(result instanceof List<?> rows)) {
            return result;
        }
        List<Object> out = new ArrayList<>();
        for (Object row : rows) {
            out.add(row == null ? null : PgJson.MAPPER.valueToTree(row).path("uuid").asText());
        }
        return out;
    }

    /**
     * The documented residuals (see {@link ParityResiduals}), keyed {@code NestedClass#method|check}: they
     * print {@code [KNOWN]} instead of failing, and fail the day the engines agree on them.
     */
    private static final Map<String, String> RESIDUALS = Map.ofEntries(
            Map.entry("PojoListPojo#intermediate|a.items.dims $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("PojoListPojo#intermediate|a.items.dims $ne null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("PojoListPojo#intermediate|a.items.dims $empty", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("PojoListPojo#updateDeep|after update a.items.dims $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("ListPojoPojo#intermediate|lines.product.brand $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("ListPojoPojo#intermediate|lines.product.brand $ne null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("ListPojoPojo#intermediate|lines.product $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("ListPojoPojo#intermediate|lines.product.brand $empty", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("ListPojoPojo#updateDeep|after update lines.product $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("MapPojoPojoList#intermediate|stock.paris.origin $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("MapPojoPojoList#intermediate|stock.paris.origin $ne null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("MapPojoPojoList#updateDeep|after update lyon.origin $eq null", ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("Recursive#sortDeep|sort head.next.next.next.v asc", ParityResiduals.JSONB_SORT),
            Map.entry("Recursive#sortDeep|sort head.next.next.next.v desc", ParityResiduals.JSONB_SORT),
            Map.entry("NullElements#nullPojoElement|lines.product $eq null",
                    ParityResiduals.NULL_ELEMENT + " and " + ParityResiduals.PRESENCE_IN_ELEMENT),
            Map.entry("NullElements#nullGrandchild|orders.lines.sku $eq null", ParityResiduals.NULL_ELEMENT),
            Map.entry("NullElements#nullElementUnderPojo|a.items.dims.width $eq null", ParityResiduals.NULL_ELEMENT));

    /** Every disagreement of the running test — all sub-checks run, the test fails once at the end. */
    private final List<String> failures = new ArrayList<>();

    /** {@code NestedClass#method} of the running test, the prefix of its {@link #RESIDUALS} keys. */
    private String running = "";

    @BeforeEach
    void identify(TestInfo info) {
        running = info.getTestClass().map(Class::getSimpleName).orElse("") + "#"
                + info.getTestMethod().map(m -> m.getName()).orElse("");
    }

    @AfterEach
    void reportAll() {
        if (!failures.isEmpty()) {
            fail(failures.size() + " disagreement(s):\n- " + String.join("\n- ", failures));
        }
    }

    private void verify(String what, Outcome outcome, boolean ordered) {
        String residual = RESIDUALS.get(running + "|" + what);
        if (residual != null) {
            try {
                ParityResiduals.pinned(residual, () -> ParityHarness.assertSame(what, outcome, ordered));
            } catch (AssertionError gone) {
                failures.add(gone.getMessage());
            }
            return;
        }
        try {
            ParityHarness.assertSame(what, outcome, ordered);
            System.out.println("[AGREE] " + what + " -> " + (outcome.mongoError() != null ? "both threw"
                    : String.valueOf(outcome.mongo())));
        } catch (AssertionError e) {
            System.out.println("[DIVERGE] " + e.getMessage());
            failures.add(e.getMessage());
        }
    }

    private void check(ParityHarness h, String domain, String what, IFilter filter) {
        verify(what, uuids(h.find(domain, filter)), false);
    }

    private void roundTrip(ParityHarness h, String domain, String what) {
        verify(what, h.find(domain, null), false);
    }

    private void projected(ParityHarness h, String domain, String what, List<String> projection) {
        verify(what,
                h.find(domain, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(projection)), false);
    }

    /**
     * Sorted find: compares the SEQUENCE of sort keys, computed from each engine's rows the way MongoDB
     * computes an array sort key (asc: the smallest element, null lowest; desc: the largest), and the
     * set of rows returned — so a tie ordered differently is not a divergence, a different order is.
     */
    private void sorted(ParityHarness h, String domain, String path, SortDirection dir) {
        Outcome o = h.find(domain, Optional.empty(), Optional.empty(),
                Optional.of(ParityFilter.sort(path, dir)), Optional.empty());
        boolean asc = dir == SortDirection.asc;
        verify("sort " + path + " " + dir,
                new Outcome(sortView(o.mongo(), path, asc), sortView(o.pg(), path, asc), o.mongoError(),
                        o.pgError()), true);
    }

    private static Object sortView(Object result, String path, boolean asc) {
        if (!(result instanceof List<?> rows)) {
            return result;
        }
        List<String> keys = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Object row : rows) {
            JsonNode node = PgJson.MAPPER.valueToTree(row);
            keys.add(sortKey(leaves(node, path.split("\\."), 0), asc));
            ids.add(node.path("uuid").asText());
        }
        ids.sort(null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("keys", keys);
        view.put("rows", ids);
        return view;
    }

    /** MongoDB's sort key of a multi-valued path: on asc a null anywhere wins, on desc the largest value. */
    private static String sortKey(List<JsonNode> values, boolean asc) {
        JsonNode best = null;
        boolean sawNull = values.isEmpty();
        for (JsonNode v : values) {
            if (v == null || v.isNull()) {
                sawNull = true;
                continue;
            }
            if (best == null || (compare(v, best) < 0) == asc) {
                best = v;
            }
        }
        if (best == null || (asc && sawNull)) {
            return "null";
        }
        return best.toString();
    }

    private static int compare(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.asDouble(), b.asDouble());
        }
        return a.asText().compareTo(b.asText());
    }

    /** The values at a dotted path, flattening arrays (a present element missing the field gives null). */
    private static List<JsonNode> leaves(JsonNode node, String[] path, int i) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            out.add(null);
            return out;
        }
        if (node.isArray()) {
            node.forEach(e -> out.addAll(leaves(e, path, i)));
            return out;
        }
        if (i == path.length) {
            out.add(node);
            return out;
        }
        out.addAll(leaves(node.get(path[i]), path, i + 1));
        return out;
    }

    /** Prints the tables and columns PostgreSQL created in this harness's schema. */
    private static void storage(ParityHarness h, String shape) {
        String sql = "SELECT table_name, column_name, data_type FROM information_schema.columns "
                + "WHERE table_schema = current_schema() ORDER BY table_name, ordinal_position";
        Map<String, List<String>> tables = new TreeMap<>();
        try (Connection c = h.pgDatabase().getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(rs.getString(2) + ":" + rs.getString(3));
            }
        } catch (SQLException e) {
            System.out.println("[STORAGE] " + shape + " — cannot read information_schema: " + e.getMessage());
            return;
        }
        StringBuilder sb = new StringBuilder("[STORAGE] ").append(shape);
        tables.forEach((t, cols) -> sb.append("\n[STORAGE]   ").append(t).append(" ").append(cols));
        System.out.println(sb);
    }

    // ================================================================== shape 1: POJO > POJO > POJO > scalar

    public static class Deep {
        String uuid;
        L1 a;
    }

    public static class L1 {
        String tag;
        L2 b;
    }

    public static class L2 {
        Integer n;
        L3 c;
    }

    public static class L3 {
        String d;
        Integer e;
    }

    private static final String DEEPS = "deeps";

    private static L3 l3(String d, Integer e) {
        L3 x = new L3();
        x.d = d;
        x.e = e;
        return x;
    }

    private static L2 l2(Integer n, L3 c) {
        L2 x = new L2();
        x.n = n;
        x.c = c;
        return x;
    }

    private static L1 l1(String tag, L2 b) {
        L1 x = new L1();
        x.tag = tag;
        x.b = b;
        return x;
    }

    private static Deep deep(String uuid, L1 a) {
        Deep x = new Deep();
        x.uuid = uuid;
        x.a = a;
        return x;
    }

    /**
     * d1 a.b.c.d=X e=3 · d2 d=Y e=1 · d3 d=null e=2 · d4 c=null · d5 b=null · d6 a=null ·
     * d7 c={} (all fields null) · d8 a={} (all fields null).
     */
    private static ParityHarness deeps() {
        ParityHarness h = ParityHarness.of(Domain.of(DEEPS, Deep.class));
        h.save(DEEPS,
                deep("d1", l1("t1", l2(1, l3("X", 3)))),
                deep("d2", l1("t2", l2(2, l3("Y", 1)))),
                deep("d3", l1("t3", l2(3, l3(null, 2)))),
                deep("d4", l1("t4", l2(4, null))),
                deep("d5", l1("t5", null)),
                deep("d6", null),
                deep("d7", l1("t7", l2(null, l3(null, null)))),
                deep("d8", l1(null, null)));
        return h;
    }

    @Nested
    @DisplayName("POJO > POJO > POJO > scalar (a.b.c.d)")
    class PojoChain {

        @Test
        @DisplayName("round trip keeps null / empty object at every level")
        void roundTripAll() {
            ParityHarness h = deeps();
            storage(h, "a.b.c.d (POJO>POJO>POJO>scalar)");
            roundTrip(h, DEEPS, "deeps round trip");
        }

        @Test
        @DisplayName("filters on a.b.c.d: $eq, $ne, $in")
        void leafFilters() {
            ParityHarness h = deeps();
            check(h, DEEPS, "a.b.c.d $eq X", field("a.b.c.d", "$eq", "X"));
            check(h, DEEPS, "a.b.c.d $ne X", field("a.b.c.d", "$ne", "X"));
            check(h, DEEPS, "a.b.c.d $in [X,Y]", listed("a.b.c.d", "$in", "X", "Y"));
            check(h, DEEPS, "a.b.c.e $gte 2", field("a.b.c.e", "$gte", 2));
        }

        @Test
        @DisplayName("a.b.c $eq null / $ne null on an intermediate level")
        void intermediateNull() {
            ParityHarness h = deeps();
            check(h, DEEPS, "a.b.c $eq null", field("a.b.c", "$eq", null));
            check(h, DEEPS, "a.b.c $ne null", field("a.b.c", "$ne", null));
            check(h, DEEPS, "a.b.c.d $eq null", field("a.b.c.d", "$eq", null));
        }

        @Test
        @DisplayName("$empty on a, a.b, a.b.c")
        void intermediateEmpty() {
            ParityHarness h = deeps();
            check(h, DEEPS, "a $empty", field("a", "$empty", null));
            check(h, DEEPS, "a.b $empty", field("a.b", "$empty", null));
            check(h, DEEPS, "a.b.c $empty", field("a.b.c", "$empty", null));
        }

        @Test
        @DisplayName("sort on a.b.c.e (asc, desc)")
        void sortDeep() {
            ParityHarness h = deeps();
            sorted(h, DEEPS, "a.b.c.e", SortDirection.asc);
            sorted(h, DEEPS, "a.b.c.e", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of the intermediate path a.b")
        void projectIntermediate() {
            projected(deeps(), DEEPS, "project a.b", List.of("a.b"));
        }

        @Test
        @DisplayName("update: change a.b.c.d, then set a.b to null")
        void updateDeep() {
            ParityHarness h = deeps();
            h.save(DEEPS, deep("d1", l1("t1", l2(1, l3("Z", 3)))), deep("d2", l1("t2", null)));
            check(h, DEEPS, "after update a.b.c.d $eq Z", field("a.b.c.d", "$eq", "Z"));
            check(h, DEEPS, "after update a.b.c.d $eq X", field("a.b.c.d", "$eq", "X"));
            check(h, DEEPS, "after update a.b $empty", field("a.b", "$empty", null));
            roundTrip(h, DEEPS, "deeps after update");
        }
    }

    // ================================================================== shape 2: POJO > List<POJO> > POJO > scalar

    public static class Box {
        String uuid;
        Pack a;
    }

    public static class Pack {
        String label;
        List<Part> items;
    }

    public static class Part {
        String name;
        Dims dims;
    }

    public static class Dims {
        Integer width;
        Integer height;
    }

    private static final String BOXES = "boxes";

    private static Part part(String name, Integer width, Integer height, boolean hasDims) {
        Part p = new Part();
        p.name = name;
        if (hasDims) {
            p.dims = new Dims();
            p.dims.width = width;
            p.dims.height = height;
        }
        return p;
    }

    private static Box box(String uuid, String label, List<Part> items, boolean hasPack) {
        Box b = new Box();
        b.uuid = uuid;
        if (hasPack) {
            b.a = new Pack();
            b.a.label = label;
            b.a.items = items;
        }
        return b;
    }

    /**
     * b1 items [p1 10x5, p2 20x6] · b2 [p3 30x-] · b3 [] · b4 items null · b5 a null ·
     * b7 [element with name and dims null] · b8 [p8 10x1, p9 dims {} all null].
     */
    private static ParityHarness boxes() {
        ParityHarness h = ParityHarness.of(Domain.of(BOXES, Box.class));
        h.save(BOXES,
                box("b1", "L1", list(part("p1", 10, 5, true), part("p2", 20, 6, true)), true),
                box("b2", "L2", list(part("p3", 30, null, true)), true),
                box("b3", "L3", list(), true),
                box("b4", "L4", null, true),
                box("b5", null, null, false),
                box("b7", "L7", list(part(null, null, null, false)), true),
                box("b8", "L8", list(part("p8", 10, 1, true), part("p9", null, null, true)), true));
        return h;
    }

    @Nested
    @DisplayName("POJO > List<POJO> > POJO > scalar (a.items.dims.width)")
    class PojoListPojo {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = boxes();
            storage(h, "a.items.dims.width (POJO>List<POJO>>POJO>scalar)");
            roundTrip(h, BOXES, "boxes round trip");
        }

        @Test
        @DisplayName("a.items.dims.width $eq / $ne / $gt — any element semantics")
        void leafFilters() {
            ParityHarness h = boxes();
            check(h, BOXES, "a.items.dims.width $eq 10", field("a.items.dims.width", "$eq", 10));
            check(h, BOXES, "a.items.dims.width $ne 10", field("a.items.dims.width", "$ne", 10));
            check(h, BOXES, "a.items.dims.width $gt 15", field("a.items.dims.width", "$gt", 15));
            check(h, BOXES, "a.items.dims.height $eq null", field("a.items.dims.height", "$eq", null));
        }

        @Test
        @DisplayName("a.items.dims $eq null / $ne null, a.items $empty, a.items.dims $empty")
        void intermediate() {
            ParityHarness h = boxes();
            check(h, BOXES, "a.items.dims $eq null", field("a.items.dims", "$eq", null));
            check(h, BOXES, "a.items.dims $ne null", field("a.items.dims", "$ne", null));
            check(h, BOXES, "a.items $empty", field("a.items", "$empty", null));
            check(h, BOXES, "a.items.dims $empty", field("a.items.dims", "$empty", null));
        }

        @Test
        @DisplayName("sort on a.items.dims.width (array: min asc / max desc on MongoDB)")
        void sortDeep() {
            ParityHarness h = boxes();
            sorted(h, BOXES, "a.items.dims.width", SortDirection.asc);
            sorted(h, BOXES, "a.items.dims.width", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of a.items")
        void projectIntermediate() {
            projected(boxes(), BOXES, "project a.items", List.of("a.items"));
        }

        @Test
        @DisplayName("update: remove an element, null a nested object")
        void updateDeep() {
            ParityHarness h = boxes();
            h.save(BOXES, box("b1", "L1", list(part("p1", 10, 5, true)), true),
                    box("b8", "L8", list(part("p8", 10, 1, false)), true));
            check(h, BOXES, "after update width $eq 20", field("a.items.dims.width", "$eq", 20));
            check(h, BOXES, "after update width $eq 10", field("a.items.dims.width", "$eq", 10));
            check(h, BOXES, "after update a.items.dims $eq null", field("a.items.dims", "$eq", null));
            roundTrip(h, BOXES, "boxes after update");
        }
    }

    // ================================================================== shape 3: List<POJO> > POJO > POJO

    public static class Cart {
        String uuid;
        List<CLine> lines;
    }

    public static class CLine {
        Integer qty;
        Product product;
    }

    public static class Product {
        String name;
        Brand brand;
    }

    public static class Brand {
        String name;
        String country;
    }

    private static final String CARTS = "carts";

    private static Brand brand(String name, String country) {
        Brand b = new Brand();
        b.name = name;
        b.country = country;
        return b;
    }

    private static Product product(String name, Brand brand) {
        Product p = new Product();
        p.name = name;
        p.brand = brand;
        return p;
    }

    private static CLine cline(Integer qty, Product product) {
        CLine l = new CLine();
        l.qty = qty;
        l.product = product;
        return l;
    }

    private static Cart cart(String uuid, List<CLine> lines) {
        Cart c = new Cart();
        c.uuid = uuid;
        c.lines = lines;
        return c;
    }

    /**
     * c1 [Acme/FR, Bolt/DE] · c2 [brand null] · c3 [product null] · c4 [] · c5 null ·
     * c6 [brand {} all null] · c7 [Acme/IT].
     */
    private static ParityHarness carts() {
        ParityHarness h = ParityHarness.of(Domain.of(CARTS, Cart.class));
        h.save(CARTS,
                cart("c1", list(cline(1, product("P1", brand("Acme", "FR"))),
                        cline(2, product("P2", brand("Bolt", "DE"))))),
                cart("c2", list(cline(3, product("P3", null)))),
                cart("c3", list(cline(4, null))),
                cart("c4", list()),
                cart("c5", null),
                cart("c6", list(cline(null, product(null, brand(null, null))))),
                cart("c7", list(cline(5, product("P5", brand("Acme", "IT"))))));
        return h;
    }

    @Nested
    @DisplayName("List<POJO> > POJO > POJO (lines.product.brand.name)")
    class ListPojoPojo {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = carts();
            storage(h, "lines.product.brand.name (List<POJO>>POJO>POJO)");
            roundTrip(h, CARTS, "carts round trip");
        }

        @Test
        @DisplayName("lines.product.brand.name $eq / $ne / $in")
        void leafFilters() {
            ParityHarness h = carts();
            check(h, CARTS, "brand.name $eq Acme", field("lines.product.brand.name", "$eq", "Acme"));
            check(h, CARTS, "brand.name $ne Acme", field("lines.product.brand.name", "$ne", "Acme"));
            check(h, CARTS, "brand.name $in [Bolt]", listed("lines.product.brand.name", "$in", "Bolt"));
            check(h, CARTS, "brand.name $eq null", field("lines.product.brand.name", "$eq", null));
        }

        @Test
        @DisplayName("lines.product.brand / lines.product $eq null, $ne null, $empty")
        void intermediate() {
            ParityHarness h = carts();
            check(h, CARTS, "lines.product.brand $eq null", field("lines.product.brand", "$eq", null));
            check(h, CARTS, "lines.product.brand $ne null", field("lines.product.brand", "$ne", null));
            check(h, CARTS, "lines.product $eq null", field("lines.product", "$eq", null));
            check(h, CARTS, "lines.product.brand $empty", field("lines.product.brand", "$empty", null));
        }

        @Test
        @DisplayName("$and on two deep fields (independent element match, not $elemMatch)")
        void andAcrossElements() {
            ParityHarness h = carts();
            check(h, CARTS, "brand.name Acme AND brand.country DE", logical("$and",
                    field("lines.product.brand.name", "$eq", "Acme"),
                    field("lines.product.brand.country", "$eq", "DE")));
        }

        @Test
        @DisplayName("sort on lines.product.brand.name")
        void sortDeep() {
            ParityHarness h = carts();
            sorted(h, CARTS, "lines.product.brand.name", SortDirection.asc);
            sorted(h, CARTS, "lines.product.brand.name", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of lines.product")
        void projectIntermediate() {
            projected(carts(), CARTS, "project lines.product", List.of("lines.product"));
        }

        @Test
        @DisplayName("update: change a brand inside an element, null a product")
        void updateDeep() {
            ParityHarness h = carts();
            h.save(CARTS, cart("c7", list(cline(5, product("P5", brand("Zeta", "IT"))))),
                    cart("c1", list(cline(1, null), cline(2, product("P2", brand("Bolt", "DE"))))));
            check(h, CARTS, "after update brand.name $eq Acme", field("lines.product.brand.name", "$eq", "Acme"));
            check(h, CARTS, "after update lines.product $eq null", field("lines.product", "$eq", null));
            roundTrip(h, CARTS, "carts after update");
        }
    }

    // ================================================================== shape 4: List<POJO> > List<String>

    public static class Tagged {
        String uuid;
        List<TLine> lines;
    }

    public static class TLine {
        String sku;
        List<String> tags;
    }

    private static final String TAGGEDS = "taggeds";

    private static TLine tline(String sku, List<String> tags) {
        TLine l = new TLine();
        l.sku = sku;
        l.tags = tags;
        return l;
    }

    private static Tagged tagged(String uuid, List<TLine> lines) {
        Tagged t = new Tagged();
        t.uuid = uuid;
        t.lines = lines;
        return t;
    }

    /** t1 [A[x,y], B[z]] · t2 [C[]] · t3 [D null] · t4 [] · t5 null · t6 [E[y]]. */
    private static ParityHarness taggeds() {
        ParityHarness h = ParityHarness.of(Domain.of(TAGGEDS, Tagged.class));
        h.save(TAGGEDS,
                tagged("t1", list(tline("A", list("x", "y")), tline("B", list("z")))),
                tagged("t2", list(tline("C", list()))),
                tagged("t3", list(tline("D", null))),
                tagged("t4", list()),
                tagged("t5", null),
                tagged("t6", list(tline("E", list("y")))));
        return h;
    }

    @Nested
    @DisplayName("List<POJO> > List<String> (lines.tags, JSONB in the child table)")
    class ListPojoListString {

        @Test
        @DisplayName("round trip (null tags vs empty tags inside an element)")
        void roundTripAll() {
            ParityHarness h = taggeds();
            storage(h, "lines.tags (List<POJO>>List<String>)");
            roundTrip(h, TAGGEDS, "taggeds round trip");
        }

        @Test
        @DisplayName("lines.tags $eq / $ne / $in")
        void leafFilters() {
            ParityHarness h = taggeds();
            check(h, TAGGEDS, "lines.tags $eq x", field("lines.tags", "$eq", "x"));
            check(h, TAGGEDS, "lines.tags $ne x", field("lines.tags", "$ne", "x"));
            check(h, TAGGEDS, "lines.tags $in [z,y]", listed("lines.tags", "$in", "z", "y"));
            check(h, TAGGEDS, "lines.tags $nin [y]", listed("lines.tags", "$nin", "y"));
        }

        @Test
        @DisplayName("lines.tags $empty / $eq null / $ne null")
        void intermediate() {
            ParityHarness h = taggeds();
            check(h, TAGGEDS, "lines.tags $empty", field("lines.tags", "$empty", null));
            check(h, TAGGEDS, "lines.tags $eq null", field("lines.tags", "$eq", null));
            check(h, TAGGEDS, "lines.tags $ne null", field("lines.tags", "$ne", null));
        }

        @Test
        @DisplayName("update: remove a tag, empty a tag list")
        void updateDeep() {
            ParityHarness h = taggeds();
            h.save(TAGGEDS, tagged("t1", list(tline("A", list("y")), tline("B", list()))));
            check(h, TAGGEDS, "after update lines.tags $eq x", field("lines.tags", "$eq", "x"));
            check(h, TAGGEDS, "after update lines.tags $eq z", field("lines.tags", "$eq", "z"));
            roundTrip(h, TAGGEDS, "taggeds after update");
        }
    }

    // ================================================================== shape 5: List<POJO> > List<POJO>

    public static class Shop {
        String uuid;
        List<Order> orders;
    }

    public static class Order {
        String ref;
        List<OLine> lines;
    }

    public static class OLine {
        String sku;
        Integer qty;
    }

    private static final String SHOPS = "shops";

    private static OLine oline(String sku, Integer qty) {
        OLine l = new OLine();
        l.sku = sku;
        l.qty = qty;
        return l;
    }

    private static Order order(String ref, List<OLine> lines) {
        Order o = new Order();
        o.ref = ref;
        o.lines = lines;
        return o;
    }

    private static Shop shop(String uuid, List<Order> orders) {
        Shop s = new Shop();
        s.uuid = uuid;
        s.orders = orders;
        return s;
    }

    /**
     * s1 [R1[K1/1,K2/2], R2[K3/3]] · s2 [R3 []] · s3 [R4 null] · s4 [] · s5 null ·
     * s6 [R6[K2/9], R7[{sku null, qty null}]].
     */
    private static ParityHarness shops() {
        ParityHarness h = ParityHarness.of(Domain.of(SHOPS, Shop.class));
        h.save(SHOPS,
                shop("s1", list(order("R1", list(oline("K1", 1), oline("K2", 2))), order("R2", list(oline("K3", 3))))),
                shop("s2", list(order("R3", list()))),
                shop("s3", list(order("R4", null))),
                shop("s4", list()),
                shop("s5", null),
                shop("s6", list(order("R6", list(oline("K2", 9))), order("R7", list(oline(null, null))))));
        return h;
    }

    @Nested
    @DisplayName("List<POJO> > List<POJO> (orders.lines.sku — a path crossing TWO arrays)")
    class ListPojoListPojo {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = shops();
            storage(h, "orders.lines.sku (List<POJO>>List<POJO>)");
            roundTrip(h, SHOPS, "shops round trip");
        }

        @Test
        @DisplayName("orders.lines.sku $eq K2")
        void eq() {
            check(shops(), SHOPS, "orders.lines.sku $eq K2", field("orders.lines.sku", "$eq", "K2"));
        }

        @Test
        @DisplayName("orders.lines.sku $ne K2")
        void ne() {
            check(shops(), SHOPS, "orders.lines.sku $ne K2", field("orders.lines.sku", "$ne", "K2"));
        }

        @Test
        @DisplayName("orders.lines.sku $in [K1,K3] / $nin [K2]")
        void inNin() {
            ParityHarness h = shops();
            check(h, SHOPS, "orders.lines.sku $in [K1,K3]", listed("orders.lines.sku", "$in", "K1", "K3"));
            check(h, SHOPS, "orders.lines.sku $nin [K2]", listed("orders.lines.sku", "$nin", "K2"));
        }

        @Test
        @DisplayName("orders.lines.qty $gt 2 / orders.lines.sku $regex ^K")
        void rangeRegex() {
            ParityHarness h = shops();
            check(h, SHOPS, "orders.lines.qty $gt 2", field("orders.lines.qty", "$gt", 2));
            check(h, SHOPS, "orders.lines.sku $regex ^K", field("orders.lines.sku", "$regex", "^K"));
        }

        @Test
        @DisplayName("orders.lines.sku $eq null / $ne null / $empty, orders.lines $empty")
        void nulls() {
            ParityHarness h = shops();
            check(h, SHOPS, "orders.lines.sku $eq null", field("orders.lines.sku", "$eq", null));
            check(h, SHOPS, "orders.lines.sku $ne null", field("orders.lines.sku", "$ne", null));
            check(h, SHOPS, "orders.lines.sku $empty", field("orders.lines.sku", "$empty", null));
            check(h, SHOPS, "orders.lines $empty", field("orders.lines", "$empty", null));
        }

        @Test
        @DisplayName("count orders.lines.sku $eq K2")
        void count() {
            verify("count orders.lines.sku $eq K2",
                    shops().count(SHOPS, field("orders.lines.sku", "$eq", "K2")), false);
        }

        @Test
        @DisplayName("sort on orders.lines.qty")
        void sortDeep() {
            ParityHarness h = shops();
            sorted(h, SHOPS, "orders.lines.qty", SortDirection.asc);
            sorted(h, SHOPS, "orders.lines.qty", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of orders.lines")
        void projectIntermediate() {
            projected(shops(), SHOPS, "project orders.lines", List.of("orders.lines"));
        }

        @Test
        @DisplayName("update: remove a grandchild line")
        void updateDeep() {
            ParityHarness h = shops();
            h.save(SHOPS, shop("s6", list(order("R6", list()), order("R7", list(oline(null, null))))));
            check(h, SHOPS, "after update orders.lines.sku $eq K2", field("orders.lines.sku", "$eq", "K2"));
            roundTrip(h, SHOPS, "shops after update");
        }
    }

    // ================================================================== shape 6: List<POJO> > Map<String,POJO>

    public static class Palette {
        String uuid;
        List<ALine> lines;
    }

    public static class ALine {
        String sku;
        Map<String, Attr> attrs;
    }

    public static class Attr {
        String hex;
        Integer weight;
    }

    private static final String PALETTES = "palettes";

    private static Attr attr(String hex, Integer weight) {
        Attr a = new Attr();
        a.hex = hex;
        a.weight = weight;
        return a;
    }

    private static ALine aline(String sku, Map<String, Object> attrs) {
        ALine l = new ALine();
        l.sku = sku;
        l.attrs = typed(attrs);
        return l;
    }

    private static Palette palette(String uuid, List<ALine> lines) {
        Palette p = new Palette();
        p.uuid = uuid;
        p.lines = lines;
        return p;
    }

    /** p1 [S1 {color #fff, size #000}] · p2 [S2 {size #fff}] · p3 [S3 {}] · p4 [S4 null] · p6 null. */
    private static ParityHarness palettes() {
        ParityHarness h = ParityHarness.of(Domain.of(PALETTES, Palette.class));
        h.save(PALETTES,
                palette("p1", list(aline("S1", map("color", attr("#fff", 1), "size", attr("#000", 2))))),
                palette("p2", list(aline("S2", map("size", attr("#fff", 3))))),
                palette("p3", list(aline("S3", map()))),
                palette("p4", list(aline("S4", null))),
                palette("p6", null));
        return h;
    }

    @Nested
    @DisplayName("List<POJO> > Map<String,POJO> (lines.attrs.color.hex)")
    class ListPojoMapPojo {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = palettes();
            storage(h, "lines.attrs.color.hex (List<POJO>>Map<String,POJO>)");
            roundTrip(h, PALETTES, "palettes round trip");
        }

        @Test
        @DisplayName("lines.attrs.color.hex $eq / $ne, lines.attrs.size.weight $gt")
        void leafFilters() {
            ParityHarness h = palettes();
            check(h, PALETTES, "lines.attrs.color.hex $eq #fff", field("lines.attrs.color.hex", "$eq", "#fff"));
            check(h, PALETTES, "lines.attrs.color.hex $ne #fff", field("lines.attrs.color.hex", "$ne", "#fff"));
            check(h, PALETTES, "lines.attrs.size.weight $gt 2", field("lines.attrs.size.weight", "$gt", 2));
        }

        @Test
        @DisplayName("lines.attrs.color $eq null / $empty, lines.attrs $empty")
        void intermediate() {
            ParityHarness h = palettes();
            check(h, PALETTES, "lines.attrs.color $eq null", field("lines.attrs.color", "$eq", null));
            check(h, PALETTES, "lines.attrs.color $empty", field("lines.attrs.color", "$empty", null));
            check(h, PALETTES, "lines.attrs $empty", field("lines.attrs", "$empty", null));
        }

        @Test
        @DisplayName("a map entry whose value is null, inside a list element")
        void nullMapValue() {
            ParityHarness h = ParityHarness.of(Domain.of(PALETTES, Palette.class));
            h.save(PALETTES, palette("p5", list(aline("S5", map("color", null)))),
                    palette("p1", list(aline("S1", map("color", attr("#fff", 1))))));
            roundTrip(h, PALETTES, "palette with null map value");
            check(h, PALETTES, "lines.attrs.color $eq null", field("lines.attrs.color", "$eq", null));
        }

        @Test
        @DisplayName("update: change a hex, drop a map entry")
        void updateDeep() {
            ParityHarness h = palettes();
            h.save(PALETTES, palette("p1", list(aline("S1", map("size", attr("#000", 2))))),
                    palette("p2", list(aline("S2", map("size", attr("#abc", 3))))));
            check(h, PALETTES, "after update color.hex $eq #fff", field("lines.attrs.color.hex", "$eq", "#fff"));
            check(h, PALETTES, "after update size.hex $eq #abc", field("lines.attrs.size.hex", "$eq", "#abc"));
            roundTrip(h, PALETTES, "palettes after update");
        }
    }

    // ================================================================== shape 7: Map<String,POJO> > POJO > List<String>

    public static class Depot {
        String uuid;
        Map<String, Stock> stock;
    }

    public static class Stock {
        Integer qty;
        Origin origin;
    }

    public static class Origin {
        String country;
        List<String> labels;
    }

    private static final String DEPOTS = "depots";

    private static Stock stockOf(Integer qty, String country, List<String> labels, boolean hasOrigin) {
        Stock s = new Stock();
        s.qty = qty;
        if (hasOrigin) {
            s.origin = new Origin();
            s.origin.country = country;
            s.origin.labels = labels;
        }
        return s;
    }

    private static Depot depot(String uuid, Map<String, Object> stock) {
        Depot d = new Depot();
        d.uuid = uuid;
        d.stock = typed(stock);
        return d;
    }

    /**
     * w1 {paris 5 FR [bio,local], lyon 2 FR []} · w2 {paris 1 IT null} · w3 {paris 3 origin null} ·
     * w4 {} · w5 null · w6 {lyon 7 ES [bio]}.
     */
    private static ParityHarness depots() {
        ParityHarness h = ParityHarness.of(Domain.of(DEPOTS, Depot.class));
        h.save(DEPOTS,
                depot("w1", map("paris", stockOf(5, "FR", list("bio", "local"), true),
                        "lyon", stockOf(2, "FR", list(), true))),
                depot("w2", map("paris", stockOf(1, "IT", null, true))),
                depot("w3", map("paris", stockOf(3, null, null, false))),
                depot("w4", map()),
                depot("w5", null),
                depot("w6", map("lyon", stockOf(7, "ES", list("bio"), true))));
        return h;
    }

    @Nested
    @DisplayName("Map<String,POJO> > POJO > List<String> (stock.<key>.origin.labels)")
    class MapPojoPojoList {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = depots();
            storage(h, "stock.<key>.origin.labels (Map<String,POJO>>POJO>List<String>)");
            roundTrip(h, DEPOTS, "depots round trip");
        }

        @Test
        @DisplayName("stock.paris.origin.labels $eq / $ne, stock.paris.origin.country $eq")
        void leafFilters() {
            ParityHarness h = depots();
            check(h, DEPOTS, "stock.paris.origin.labels $eq bio", field("stock.paris.origin.labels", "$eq", "bio"));
            check(h, DEPOTS, "stock.paris.origin.labels $ne bio", field("stock.paris.origin.labels", "$ne", "bio"));
            check(h, DEPOTS, "stock.lyon.origin.labels $eq bio", field("stock.lyon.origin.labels", "$eq", "bio"));
            check(h, DEPOTS, "stock.paris.origin.country $eq FR", field("stock.paris.origin.country", "$eq", "FR"));
        }

        @Test
        @DisplayName("stock.paris.origin $eq null / $ne null, stock.paris.origin.labels $empty, stock.paris $empty")
        void intermediate() {
            ParityHarness h = depots();
            check(h, DEPOTS, "stock.paris.origin $eq null", field("stock.paris.origin", "$eq", null));
            check(h, DEPOTS, "stock.paris.origin $ne null", field("stock.paris.origin", "$ne", null));
            check(h, DEPOTS, "stock.paris.origin.labels $empty", field("stock.paris.origin.labels", "$empty", null));
            check(h, DEPOTS, "stock.paris $empty", field("stock.paris", "$empty", null));
        }

        @Test
        @DisplayName("sort on stock.paris.qty")
        void sortDeep() {
            ParityHarness h = depots();
            sorted(h, DEPOTS, "stock.paris.qty", SortDirection.asc);
            sorted(h, DEPOTS, "stock.paris.qty", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of stock.paris.origin")
        void projectIntermediate() {
            projected(depots(), DEPOTS, "project stock.paris.origin", List.of("stock.paris.origin"));
        }

        @Test
        @DisplayName("update: remove a label, null an origin")
        void updateDeep() {
            ParityHarness h = depots();
            h.save(DEPOTS, depot("w1", map("paris", stockOf(5, "FR", list("local"), true),
                    "lyon", stockOf(2, null, null, false))));
            check(h, DEPOTS, "after update paris labels $eq bio", field("stock.paris.origin.labels", "$eq", "bio"));
            check(h, DEPOTS, "after update lyon.origin $eq null", field("stock.lyon.origin", "$eq", null));
            roundTrip(h, DEPOTS, "depots after update");
        }
    }

    // ================================================================== shape 8: POJO > Map<String, List<POJO>>

    public static class Catalog {
        String uuid;
        Shelf shelf;
    }

    public static class Shelf {
        String name;
        Map<String, List<Book>> byGenre;
    }

    public static class Book {
        String title;
        Integer year;
    }

    private static final String CATALOGS = "catalogs";

    private static Book book(String title, Integer year) {
        Book b = new Book();
        b.title = title;
        b.year = year;
        return b;
    }

    private static Catalog catalog(String uuid, String name, Map<String, Object> byGenre, boolean hasShelf) {
        Catalog c = new Catalog();
        c.uuid = uuid;
        if (hasShelf) {
            c.shelf = new Shelf();
            c.shelf.name = name;
            c.shelf.byGenre = typed(byGenre);
        }
        return c;
    }

    /** k1 {sf [Dune/1965, Hyperion/1989], noir [BigSleep/1939]} · k2 {sf []} · k3 {} · k4 map null · k5 shelf null · k6 {sf [Dune/null]}. */
    private static ParityHarness catalogs() {
        ParityHarness h = ParityHarness.of(Domain.of(CATALOGS, Catalog.class));
        h.save(CATALOGS,
                catalog("k1", "main", map("sf", list(book("Dune", 1965), book("Hyperion", 1989)),
                        "noir", list(book("BigSleep", 1939))), true),
                catalog("k2", "side", map("sf", list()), true),
                catalog("k3", "empty", map(), true),
                catalog("k4", "nomap", null, true),
                catalog("k5", null, null, false),
                catalog("k6", "x", map("sf", list(book("Dune", null))), true));
        return h;
    }

    @Nested
    @DisplayName("POJO > Map<String, List<POJO>> (shelf.byGenre.<key>.title)")
    class PojoMapListPojo {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = catalogs();
            storage(h, "shelf.byGenre.<key>.title (POJO>Map<String,List<POJO>>)");
            roundTrip(h, CATALOGS, "catalogs round trip");
        }

        @Test
        @DisplayName("shelf.byGenre.sf.title $eq / $ne, shelf.byGenre.sf.year $gt")
        void leafFilters() {
            ParityHarness h = catalogs();
            check(h, CATALOGS, "shelf.byGenre.sf.title $eq Dune", field("shelf.byGenre.sf.title", "$eq", "Dune"));
            check(h, CATALOGS, "shelf.byGenre.sf.title $ne Dune", field("shelf.byGenre.sf.title", "$ne", "Dune"));
            check(h, CATALOGS, "shelf.byGenre.sf.year $gt 1970", field("shelf.byGenre.sf.year", "$gt", 1970));
        }

        @Test
        @DisplayName("shelf.byGenre.sf $empty, shelf.byGenre $empty, shelf.byGenre.sf.year $eq null")
        void intermediate() {
            ParityHarness h = catalogs();
            check(h, CATALOGS, "shelf.byGenre.sf $empty", field("shelf.byGenre.sf", "$empty", null));
            check(h, CATALOGS, "shelf.byGenre $empty", field("shelf.byGenre", "$empty", null));
            check(h, CATALOGS, "shelf.byGenre.sf.year $eq null", field("shelf.byGenre.sf.year", "$eq", null));
        }

        @Test
        @DisplayName("update: remove a book from a map-held list")
        void updateDeep() {
            ParityHarness h = catalogs();
            h.save(CATALOGS, catalog("k1", "main", map("sf", list(book("Hyperion", 1989))), true));
            check(h, CATALOGS, "after update sf.title $eq Dune", field("shelf.byGenre.sf.title", "$eq", "Dune"));
            roundTrip(h, CATALOGS, "catalogs after update");
        }
    }

    // ================================================================== shape 9: recursive type

    public static class Chain {
        String uuid;
        Node head;
    }

    public static class Node {
        String v;
        Node next;
    }

    private static final String CHAINS = "chains";

    private static Node nodes(String... values) {
        Node head = null;
        for (int i = values.length - 1; i >= 0; i--) {
            Node n = new Node();
            n.v = values[i];
            n.next = head;
            head = n;
        }
        return head;
    }

    private static Chain chain(String uuid, Node head) {
        Chain c = new Chain();
        c.uuid = uuid;
        c.head = head;
        return c;
    }

    /** n1 a>b>c>d · n2 a>b>c · n3 a>b>c>e · n4 a · n5 null · n6 {v null}>{v null}. */
    private static ParityHarness chains() {
        ParityHarness h = ParityHarness.of(Domain.of(CHAINS, Chain.class));
        h.save(CHAINS,
                chain("n1", nodes("a", "b", "c", "d")),
                chain("n2", nodes("a", "b", "c")),
                chain("n3", nodes("a", "b", "c", "e")),
                chain("n4", nodes("a")),
                chain("n5", null),
                chain("n6", nodes(null, null)));
        return h;
    }

    @Nested
    @DisplayName("recursive type 4 levels deep (head.next.next.next.v)")
    class Recursive {

        @Test
        @DisplayName("round trip")
        void roundTripAll() {
            ParityHarness h = chains();
            storage(h, "head.next.next.next.v (recursive Node)");
            roundTrip(h, CHAINS, "chains round trip");
        }

        @Test
        @DisplayName("head.next.next.next.v $eq / $ne, head.next.v $in")
        void leafFilters() {
            ParityHarness h = chains();
            check(h, CHAINS, "head.next.next.next.v $eq d", field("head.next.next.next.v", "$eq", "d"));
            check(h, CHAINS, "head.next.next.next.v $ne d", field("head.next.next.next.v", "$ne", "d"));
            check(h, CHAINS, "head.next.v $in [b]", listed("head.next.v", "$in", "b"));
        }

        @Test
        @DisplayName("head.next.next.next $eq null / $ne null / $empty, head.next.v $eq null")
        void intermediate() {
            ParityHarness h = chains();
            check(h, CHAINS, "head.next.next.next $eq null", field("head.next.next.next", "$eq", null));
            check(h, CHAINS, "head.next.next.next $ne null", field("head.next.next.next", "$ne", null));
            check(h, CHAINS, "head.next.next.next $empty", field("head.next.next.next", "$empty", null));
            check(h, CHAINS, "head.next.v $eq null", field("head.next.v", "$eq", null));
        }

        @Test
        @DisplayName("sort on head.next.next.next.v")
        void sortDeep() {
            ParityHarness h = chains();
            sorted(h, CHAINS, "head.next.next.next.v", SortDirection.asc);
            sorted(h, CHAINS, "head.next.next.next.v", SortDirection.desc);
        }

        @Test
        @DisplayName("projection of head.next")
        void projectIntermediate() {
            projected(chains(), CHAINS, "project head.next", List.of("head.next"));
        }

        @Test
        @DisplayName("update: cut the chain at depth 2")
        void updateDeep() {
            ParityHarness h = chains();
            h.save(CHAINS, chain("n1", nodes("a", "b")));
            check(h, CHAINS, "after update head.next.next.next.v $eq d", field("head.next.next.next.v", "$eq", "d"));
            check(h, CHAINS, "after update head.next.next $eq null", field("head.next.next", "$eq", null));
            roundTrip(h, CHAINS, "chains after update");
        }
    }

    // ================================================================== null / empty at every level

    @Nested
    @DisplayName("null elements inside nested lists")
    class NullElements {

        @Test
        @DisplayName("List<POJO> with a null element (carts.lines = [null, line])")
        void nullPojoElement() {
            ParityHarness h = ParityHarness.of(Domain.of(CARTS, Cart.class));
            h.save(CARTS, cart("c1", list(null, cline(1, product("P1", brand("Acme", "FR"))))),
                    cart("c2", list(cline(2, product("P2", null)))));
            roundTrip(h, CARTS, "carts with a null element");
            check(h, CARTS, "lines $eq null", field("lines", "$eq", null));
            check(h, CARTS, "lines.product $eq null", field("lines.product", "$eq", null));
        }

        @Test
        @DisplayName("POJO > List<POJO> with a null element (boxes.a.items = [null])")
        void nullElementUnderPojo() {
            ParityHarness h = ParityHarness.of(Domain.of(BOXES, Box.class));
            h.save(BOXES, box("b1", "L1", list((Part) null), true), box("b2", "L2", list(part("p", 1, 1, true)), true));
            roundTrip(h, BOXES, "boxes with a null element");
            check(h, BOXES, "a.items.dims.width $eq null", field("a.items.dims.width", "$eq", null));
        }

        @Test
        @DisplayName("List<String> inside an element with a null value (lines.tags = [null, x])")
        void nullScalarInJsonb() {
            ParityHarness h = ParityHarness.of(Domain.of(TAGGEDS, Tagged.class));
            h.save(TAGGEDS, tagged("t1", list(tline("A", list(null, "x")))), tagged("t2", list(tline("B", list("y")))));
            roundTrip(h, TAGGEDS, "taggeds with a null tag");
            check(h, TAGGEDS, "lines.tags $eq null", field("lines.tags", "$eq", null));
            check(h, TAGGEDS, "lines.tags $ne null", field("lines.tags", "$ne", null));
        }

        @Test
        @DisplayName("grandchild list with a null element (orders.lines = [null])")
        void nullGrandchild() {
            ParityHarness h = ParityHarness.of(Domain.of(SHOPS, Shop.class));
            h.save(SHOPS, shop("s1", list(order("R1", list((OLine) null)))), shop("s2", list(order("R2", list(oline("K", 1))))));
            roundTrip(h, SHOPS, "shops with a null grandchild");
            check(h, SHOPS, "orders.lines.sku $eq null", field("orders.lines.sku", "$eq", null));
            check(h, SHOPS, "orders.lines $eq null", field("orders.lines", "$eq", null));
        }
    }
}
