package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;

/**
 * Everything through {@link PgDao}: what the writer writes, the reader reads back.
 *
 * <p>
 * The writer, the reader and the query builder were written in parallel against one contract, and
 * each was proven on its own — the writer by reading its rows with plain SQL, the reader by feeding
 * it rows written with plain SQL. Nothing proved they agree with EACH OTHER. This test does: every
 * shape goes in through {@code save} and must come back out through {@code find} unchanged.
 * </p>
 */
@DisplayName("PgDao, end to end")
class PgDaoRoundTripTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public enum Status { ACTIVE, CLOSED }

    public static class Address {
        String street;
        String city;
        int zip;
        List<String> tags;
    }

    public static class Line {
        String sku;
        BigDecimal price;
        int quantity;
    }

    public static class Node {
        String label;
        Node next;
    }

    public static class Customer {
        String uuid;
        String name;
    }

    public static class Order {
        String uuid;
        String reference;
        Status status;
        long total;
        Double ratio;
        boolean paid;
        Instant createdAt;
        byte[] signature;
        Address address;
        List<String> labels;
        List<Line> lines;
        Map<String, Integer> stock;
        Node chain;
        List<Object> coordinates;
        Customer customer;
        List<Customer> watchers;
    }

    public static class Leaf {
        String code;
        List<String> marks;
    }

    public static class Branch {
        String name;
        List<String> tags;
        Map<String, Integer> counts;
        List<Leaf> leaves;
    }

    /** Collections inside collection elements, three levels deep, and elements that ARE collections. */
    public static class Tree {
        String uuid;
        List<Branch> branches;
        List<List<String>> grid;
        Map<String, List<Leaf>> byKind;
    }

    private DataSource db;
    private PgSchemaRegistry registry;
    private PgDao orders;
    private PgDao customers;

    @BeforeEach
    void setUp() {
        db = PgTestDatabase.freshDatabase();
        registry = new PgSchemaRegistry();
        customers = new PgDao(db, "customers", registry, com.garganttua.dao.postgresql.schema.SchemaMode.CREATE);
        customers.registerDomain(TestDomains.definition(Customer.class, Map.of()));
        orders = new PgDao(db, "orders", registry, com.garganttua.dao.postgresql.schema.SchemaMode.CREATE);
        orders.registerDomain(TestDomains.definition(Order.class,
                Map.of("customer", "customers", "watchers", "customers")));
    }

    private static Customer customer(String uuid, String name) {
        Customer c = new Customer();
        c.uuid = uuid;
        c.name = name;
        return c;
    }

    private Order full(String uuid) throws ApiException {
        Order o = new Order();
        o.uuid = uuid;
        o.reference = "REF-" + uuid;
        o.status = Status.ACTIVE;
        o.total = 12_345_678_901L;
        o.ratio = 0.25;
        o.paid = true;
        o.createdAt = Instant.parse("2026-09-21T10:15:30.123456Z");
        o.signature = new byte[] { 1, 2, 3, (byte) 0xFF };
        o.address = new Address();
        o.address.street = "1 rue de la Paix";
        o.address.city = "Paris";
        o.address.zip = 75002;
        o.address.tags = new ArrayList<>(List.of("home", "billing"));
        o.labels = new ArrayList<>(List.of("b", "a", "c"));
        Line l1 = new Line();
        l1.sku = "S1";
        l1.price = new BigDecimal("19.99");
        l1.quantity = 2;
        Line l2 = new Line();
        l2.sku = "S2";
        l2.price = new BigDecimal("5.00");
        l2.quantity = 1;
        o.lines = new ArrayList<>(List.of(l1, l2));
        o.stock = new LinkedHashMap<>(Map.of("x", 1));
        o.chain = new Node();
        o.chain.label = "root";
        o.chain.next = new Node();
        o.chain.next.label = "leaf";
        o.coordinates = new ArrayList<>(List.of(List.of(2.35, 48.85), List.of(2.36, 48.86)));
        Customer alice = customer("c1", "Alice");
        Customer bob = customer("c2", "Bob");
        customers.save(alice);
        customers.save(bob);
        o.customer = alice;
        o.watchers = new ArrayList<>(List.of(bob, alice));
        return o;
    }

    private Order byUuid(String uuid) throws ApiException {
        IFilter filter = PgQueryFixture.field("uuid", "$eq", uuid);
        List<Object> found = orders.find(Optional.empty(), Optional.of(filter), Optional.empty());
        assertEquals(1, found.size(), () -> "expected exactly one order " + uuid + ", got " + found.size());
        return (Order) found.get(0);
    }

    @Nested
    @DisplayName("round-trips")
    class RoundTrip {

        @Test
        @DisplayName("every shape, unchanged")
        void everyShape() throws ApiException {
            orders.save(full("o1"));

            Order o = byUuid("o1");

            assertEquals("REF-o1", o.reference);
            assertEquals(Status.ACTIVE, o.status);
            assertEquals(12_345_678_901L, o.total);
            assertEquals(0.25, o.ratio);
            assertTrue(o.paid);
            assertEquals(Instant.parse("2026-09-21T10:15:30.123Z"), o.createdAt, "truncated to the millisecond");
            assertEquals(4, o.signature.length);
            assertEquals((byte) 0xFF, o.signature[3]);
            assertEquals("Paris", o.address.city);
            assertEquals(75002, o.address.zip);
            assertEquals(List.of("home", "billing"), o.address.tags);
            assertEquals(List.of("b", "a", "c"), o.labels, "list order must survive");
            assertEquals(2, o.lines.size());
            assertEquals("S1", o.lines.get(0).sku);
            assertEquals(0, new BigDecimal("19.99").compareTo(o.lines.get(0).price));
            assertEquals(Map.of("x", 1), o.stock);
            assertEquals("leaf", o.chain.next.label, "a recursive POJO survives as JSONB");
            assertEquals(2, o.coordinates.size());
            assertNotNull(o.customer, "a composition must be resolved");
            assertEquals("Alice", o.customer.name);
            assertEquals(List.of("Bob", "Alice"), o.watchers.stream().map(c -> c.name).toList(),
                    "a composition collection keeps its order and resolves every element");
        }

        @Test
        @DisplayName("an update replaces the entity, collections included")
        void updateReplaces() throws ApiException {
            Order o = full("o1");
            orders.save(o);
            o.reference = "changed";
            o.labels = new ArrayList<>(List.of("only"));
            o.lines = new ArrayList<>();
            o.address = null;
            orders.save(o);

            Order back = byUuid("o1");

            assertEquals("changed", back.reference);
            assertEquals(List.of("only"), back.labels, "removed elements must be gone");
            assertTrue(back.lines.isEmpty());
            assertNull(back.address, "an embedded POJO set to null must read back null");
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        @DisplayName("filter, count and delete see what save wrote")
        void writtenIsFindable() throws ApiException {
            orders.save(full("o1"));
            orders.save(full("o2"));

            assertEquals(2, orders.count(null));
            IFilter label = PgQueryFixture.field("labels", "$eq", "a");
            assertEquals(2, orders.find(Optional.empty(), Optional.of(label), Optional.empty()).size(),
                    "an array filter must match what the writer put in the child table");
            IFilter status = PgQueryFixture.field("status", "$eq", "ACTIVE");
            assertEquals(2, orders.count(status), "an enum is written and filtered by name");

            orders.delete(byUuid("o1"));
            assertEquals(1, orders.count(null));
            assertThrows(ApiException.class, () -> orders.delete(full("o1")),
                    "deleting what is not there is an error, as on MongoDB");
        }
    }

    @Nested
    @DisplayName("nested collections")
    class NestedCollections {

        private PgDao trees;

        @BeforeEach
        void registerTrees() {
            trees = new PgDao(db, "trees", registry, com.garganttua.dao.postgresql.schema.SchemaMode.CREATE);
            trees.registerDomain(TestDomains.definition(Tree.class, Map.of()));
        }

        private static Leaf leaf(String code, List<String> marks) {
            Leaf l = new Leaf();
            l.code = code;
            l.marks = marks;
            return l;
        }

        private static Branch branch(String name, List<String> tags, Map<String, Integer> counts, List<Leaf> leaves) {
            Branch b = new Branch();
            b.name = name;
            b.tags = tags;
            b.counts = counts;
            b.leaves = leaves;
            return b;
        }

        /** Every level populated, empty and null somewhere. */
        private static Tree full(String uuid) {
            Tree t = new Tree();
            t.uuid = uuid;
            t.branches = new ArrayList<>(Arrays.asList(
                    branch("B1", List.of("x", "y"), new LinkedHashMap<>(Map.of("a", 1)),
                            List.of(leaf("L1", List.of("m1", "m2")), leaf("L2", List.of()), leaf("L3", null))),
                    branch("B2", List.of(), Map.of(), List.of()),
                    branch("B3", null, null, null)));
            t.grid = new ArrayList<>(Arrays.asList(List.of("a", "b"), List.of(), List.of("c")));
            t.byKind = new LinkedHashMap<>();
            t.byKind.put("oak", List.of(leaf("O1", List.of("big")), leaf("O2", null)));
            t.byKind.put("elm", List.of());
            return t;
        }

        private Tree byUuid(String uuid) throws ApiException {
            IFilter filter = PgQueryFixture.field("uuid", "$eq", uuid);
            List<Object> found = trees.find(Optional.empty(), Optional.of(filter), Optional.empty());
            assertEquals(1, found.size());
            return (Tree) found.get(0);
        }

        @Test
        @DisplayName("List<POJO{List, Map, List<POJO{List}>}> round-trips three levels deep, null and empty kept apart")
        void threeLevels() throws ApiException {
            trees.save(full("t1"));

            Tree t = byUuid("t1");

            assertEquals(3, t.branches.size());
            Branch b1 = t.branches.get(0);
            assertEquals("B1", b1.name);
            assertEquals(List.of("x", "y"), b1.tags);
            assertEquals(Map.of("a", 1), b1.counts);
            assertEquals(List.of("L1", "L2", "L3"), b1.leaves.stream().map(l -> l.code).toList());
            assertEquals(List.of("m1", "m2"), b1.leaves.get(0).marks, "the third level keeps its order");
            assertEquals(List.of(), b1.leaves.get(1).marks, "an empty third-level list reads back empty");
            assertNull(b1.leaves.get(2).marks, "a null third-level list reads back null");
            Branch b2 = t.branches.get(1);
            assertEquals(List.of(), b2.tags);
            assertEquals(Map.of(), b2.counts);
            assertEquals(List.of(), b2.leaves);
            Branch b3 = t.branches.get(2);
            assertNull(b3.tags);
            assertNull(b3.counts);
            assertNull(b3.leaves);
        }

        @Test
        @DisplayName("List<List<String>> and Map<String, List<POJO>> round-trip through the element tables")
        void collectionsOfCollections() throws ApiException {
            trees.save(full("t1"));

            Tree t = byUuid("t1");

            assertEquals(List.of(List.of("a", "b"), List.of(), List.of("c")), t.grid);
            assertEquals(java.util.Set.of("oak", "elm"), t.byKind.keySet());
            assertEquals(List.of("O1", "O2"), t.byKind.get("oak").stream().map(l -> l.code).toList());
            assertEquals(List.of("big"), t.byKind.get("oak").get(0).marks, "a list inside a map-held list element");
            assertNull(t.byKind.get("oak").get(1).marks);
            assertEquals(List.of(), t.byKind.get("elm"));
        }

        @Test
        @DisplayName("an update replaces the whole tree, and leaves other entities' trees alone")
        void updateReplacesTree() throws ApiException {
            trees.save(full("t1"));
            trees.save(full("t2"));
            Tree changed = new Tree();
            changed.uuid = "t1";
            changed.branches = new ArrayList<>(List.of(branch("N1", List.of("new"), null,
                    List.of(leaf("NL", List.of("n"))))));
            changed.grid = new ArrayList<>(List.of(List.of("z")));
            trees.save(changed);

            Tree t = byUuid("t1");
            assertEquals(1, t.branches.size());
            assertEquals(List.of("new"), t.branches.get(0).tags);
            assertNull(t.branches.get(0).counts);
            assertEquals(List.of("n"), t.branches.get(0).leaves.get(0).marks, "no stale grandchild survives");
            assertEquals(List.of(List.of("z")), t.grid);
            assertNull(t.byKind, "a map saved null after being populated reads back null");
            Tree other = byUuid("t2");
            assertEquals(List.of("m1", "m2"), other.branches.get(0).leaves.get(0).marks);
            assertEquals(List.of(List.of("a", "b"), List.of(), List.of("c")), other.grid);
        }

        @Test
        @DisplayName("a projection loads the projected tree only")
        void projection() throws ApiException {
            trees.save(full("t1"));

            List<Object> found = trees.find(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of("branches.leaves")));

            Tree t = (Tree) found.get(0);
            assertEquals(List.of("m1", "m2"), t.branches.get(0).leaves.get(0).marks,
                    "a dotted projection covers the whole top-level field, grandchildren included");
            assertNull(t.grid, "grid was not projected");
            assertNull(t.byKind, "byKind was not projected");
        }
    }
}
