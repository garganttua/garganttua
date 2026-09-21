package com.garganttua.dao.postgresql.parity;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;
import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * Parity of the sort, pagination and projection family: the same find, asked of MongoDB and of
 * PostgreSQL, must answer the same thing.
 *
 * <p>
 * Sort scenarios compare the ORDER of uuids only (so a read-side difference unrelated to sorting,
 * such as a null vs empty collection, does not blur the finding); projection scenarios compare the
 * whole DTOs, since what the non-projected fields hold is precisely the question.
 * </p>
 */
@DisplayName("Parity MongoDB / PostgreSQL — sort, pagination, projection")
class ParitySortPageProjectionTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ------------------------------------------------------------------ DTOs

    public enum Color {
        RED, GREEN, BLUE
    }

    public static class Address {
        String city;
        String zip;

        public Address() {
        }

        Address(String city, String zip) {
            this.city = city;
            this.zip = zip;
        }
    }

    public static class Item {
        String uuid;
        String name;
        Integer rank;
        Long big;
        Double score;
        Boolean flag;
        boolean active;
        Instant at;
        Color color;
        Address address;
        List<String> tags;
        List<Integer> nums;
        Map<String, String> attrs;
        String status = "draft";
        Integer group;
    }

    public static class Customer {
        String uuid;
        String name;
    }

    public static class Order {
        String uuid;
        String label;
        Integer amount;
        Customer customer;
    }

    private static final String ITEMS = "items";

    private static Item item(String uuid) {
        Item i = new Item();
        i.uuid = uuid;
        return i;
    }

    private static Item named(String uuid, String name) {
        Item i = item(uuid);
        i.name = name;
        return i;
    }

    private static Item ranked(String uuid, Integer rank) {
        Item i = item(uuid);
        i.rank = rank;
        return i;
    }

    private static ParityHarness items() {
        return ParityHarness.of(Domain.of(ITEMS, Item.class));
    }

    // ------------------------------------------------------------------ helpers

    private static Outcome sorted(ParityHarness h, String field, SortDirection dir) {
        return h.find(ITEMS, Optional.empty(), Optional.empty(), Optional.of(ParityFilter.sort(field, dir)),
                Optional.empty());
    }

    private static Outcome paged(ParityHarness h, IPageable page, ISort sort) {
        return h.find(ITEMS, Optional.of(page), Optional.empty(), Optional.ofNullable(sort), Optional.empty());
    }

    private static Outcome projected(ParityHarness h, String domain, List<String> projection) {
        return h.find(domain, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(projection));
    }

    /** Applies {@code f} to each engine's list result; errors are kept as they are. */
    @SuppressWarnings("unchecked")
    private static Outcome map(Outcome o, Function<List<Object>, Object> f) {
        return new Outcome(o.mongo() == null ? null : f.apply((List<Object>) o.mongo()),
                o.pg() == null ? null : f.apply((List<Object>) o.pg()), o.mongoError(), o.pgError());
    }

    private static Object uuids(List<Object> dtos) {
        List<String> out = new ArrayList<>();
        for (Object dto : dtos) {
            out.add(dto instanceof Item i ? i.uuid : dto instanceof Order o ? o.uuid : String.valueOf(dto));
        }
        return out;
    }

    private static Outcome uuidOrder(Outcome o) {
        return map(o, ParitySortPageProjectionTest::uuids);
    }

    /** Concatenates several outcomes per engine (first error wins on each side). */
    @SuppressWarnings("unchecked")
    private static Outcome concat(Outcome... pages) {
        List<Object> m = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        Throwable me = null;
        Throwable pe = null;
        for (Outcome o : pages) {
            if (o.mongoError() != null && me == null) {
                me = o.mongoError();
            } else if (o.mongo() != null) {
                m.addAll((List<Object>) o.mongo());
            }
            if (o.pgError() != null && pe == null) {
                pe = o.pgError();
            } else if (o.pg() != null) {
                p.addAll((List<Object>) o.pg());
            }
        }
        return new Outcome(me == null ? m : null, pe == null ? p : null, me, pe);
    }

    /** The uuids of a list, plus whether any uuid appears twice — for "union of all pages" scenarios. */
    private static Object unionReport(List<Object> dtos) {
        @SuppressWarnings("unchecked")
        List<String> ids = new ArrayList<>((List<String>) uuids(dtos));
        boolean duplicates = ids.stream().distinct().count() != ids.size();
        ids.sort(null);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("uuids", ids);
        report.put("duplicates", duplicates);
        return report;
    }

    // ================================================================== SORT

    @Nested
    @DisplayName("Sort")
    class Sort {

        private ParityHarness strings() {
            ParityHarness h = items();
            h.save(ITEMS, named("s1", "b"), named("s2", "B"), named("s3", "a"), named("s4", "A"),
                    named("s5", null), named("s7", "é"), named("s8", "z"), named("s9", ""),
                    named("s10", "Ab"), named("s11", "a b"));
            return h;
        }

        @Test
        @DisplayName("String ascending: binary code-point order, null/absent first")
        void stringAscending() {
            ParityHarness.assertSame("sort name asc", uuidOrder(sorted(strings(), "name", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("String descending: binary code-point order reversed, null/absent last")
        void stringDescending() {
            ParityHarness.assertSame("sort name desc", uuidOrder(sorted(strings(), "name", SortDirection.desc)), true);
        }

        @Test
        @DisplayName("String ascending when the database collation is linguistic (any non-C production locale)")
        void stringAscendingLinguisticCollation() throws Exception {
            ParityHarness h = strings();
            // The DAO declares TEXT columns without COLLATE: they inherit the database default, which on
            // a real server is rarely "C". Simulate an en_US-like default on the column itself.
            try (Connection c = h.pgDatabase().getConnection(); Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + PgNaming.quote(PgNaming.table(ITEMS)) + " ALTER COLUMN "
                        + PgNaming.quote(PgNaming.column(List.of("name"))) + " TYPE TEXT COLLATE \"und-x-icu\"");
            }
            ParityHarness.assertSame("sort name asc (ICU collation)",
                    uuidOrder(sorted(h, "name", SortDirection.asc)), true);
        }

        private ParityHarness integers() {
            ParityHarness h = items();
            h.save(ITEMS, ranked("r1", 10), ranked("r2", -5), ranked("r3", null), ranked("r4", 0),
                    ranked("r5", 1000000), ranked("r6", -1000000));
            return h;
        }

        @Test
        @DisplayName("Integer ascending: negatives first after null")
        void integerAscending() {
            ParityHarness.assertSame("sort rank asc", uuidOrder(sorted(integers(), "rank", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("Integer descending: nulls last")
        void integerDescending() {
            ParityHarness.assertSame("sort rank desc", uuidOrder(sorted(integers(), "rank", SortDirection.desc)), true);
        }

        @Test
        @DisplayName("Long ascending beyond the int range")
        void longAscending() {
            ParityHarness h = items();
            Item a = item("l1");
            a.big = 5_000_000_000L;
            Item b = item("l2");
            b.big = -5_000_000_000L;
            Item c = item("l3");
            c.big = 7L;
            h.save(ITEMS, a, b, c, item("l4"));
            ParityHarness.assertSame("sort big asc", uuidOrder(sorted(h, "big", SortDirection.asc)), true);
        }

        private ParityHarness doubles() {
            ParityHarness h = items();
            double[] values = { 1.5, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.5, 0.0 };
            for (int k = 0; k < values.length; k++) {
                Item i = item("d" + k);
                i.score = values[k];
                h.save(ITEMS, i);
            }
            h.save(ITEMS, item("dnull"));
            return h;
        }

        @Test
        @DisplayName("Double ascending with NaN and infinities (MongoDB: NaN is the smallest number)")
        void doubleAscendingNaN() {
            ParityHarness.assertSame("sort score asc", uuidOrder(sorted(doubles(), "score", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("Double descending with NaN and infinities")
        void doubleDescendingNaN() {
            ParityHarness.assertSame("sort score desc", uuidOrder(sorted(doubles(), "score", SortDirection.desc)),
                    true);
        }

        @Test
        @DisplayName("Boolean ascending: null, false, true")
        void booleanAscending() {
            ParityHarness h = items();
            Item t = item("b1");
            t.flag = true;
            Item f = item("b2");
            f.flag = false;
            h.save(ITEMS, t, f, item("b3"));
            ParityHarness.assertSame("sort flag asc", uuidOrder(sorted(h, "flag", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("primitive boolean descending: true first")
        void primitiveBooleanDescending() {
            ParityHarness h = items();
            Item t = item("p1");
            t.active = true;
            h.save(ITEMS, item("p0"), t, item("p2"));
            Outcome o = sorted(h, "active", SortDirection.desc);
            // Only the first position is determined (ties among the false ones).
            ParityHarness.assertSame("sort active desc (head)", map(o, l -> ((Item) l.get(0)).uuid), true);
        }

        @Test
        @DisplayName("Instant ascending, null first")
        void instantAscending() {
            ParityHarness h = items();
            Instant base = Instant.parse("2024-03-01T10:00:00Z");
            Item a = item("t1");
            a.at = base.plusSeconds(60);
            Item b = item("t2");
            b.at = base.minusSeconds(86400L * 365 * 60);
            Item c = item("t3");
            c.at = base.plusMillis(1);
            Item d = item("t4");
            d.at = base;
            h.save(ITEMS, a, b, c, d, item("t5"));
            ParityHarness.assertSame("sort at asc", uuidOrder(sorted(h, "at", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("Instant descending, null last")
        void instantDescending() {
            ParityHarness h = items();
            Instant base = Instant.parse("2024-03-01T10:00:00Z");
            Item a = item("t1");
            a.at = base.plusSeconds(60);
            Item b = item("t2");
            b.at = base.minusSeconds(3600);
            h.save(ITEMS, a, item("t3"), b);
            ParityHarness.assertSame("sort at desc", uuidOrder(sorted(h, "at", SortDirection.desc)), true);
        }

        @Test
        @DisplayName("enum ascending: by NAME (BLUE < GREEN < RED), not by ordinal")
        void enumAscending() {
            ParityHarness h = items();
            Item r = item("e1");
            r.color = Color.RED;
            Item g = item("e2");
            g.color = Color.GREEN;
            Item b = item("e3");
            b.color = Color.BLUE;
            h.save(ITEMS, r, g, b, item("e4"));
            ParityHarness.assertSame("sort color asc", uuidOrder(sorted(h, "color", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("on the uuid field, descending")
        void uuidDescending() {
            ParityHarness h = items();
            h.save(ITEMS, item("u2"), item("u10"), item("u1"), item("U3"));
            ParityHarness.assertSame("sort uuid desc", uuidOrder(sorted(h, "uuid", SortDirection.desc)), true);
        }

        @Test
        @DisplayName("a null direction sorts descending on both")
        void nullDirection() {
            ParityHarness.assertSame("sort rank <null dir>", uuidOrder(sorted(integers(), "rank", null)), true);
        }

        @Test
        @DisplayName("on an unknown field: MongoDB ignores it (every row, natural order)")
        void unknownField() {
            ParityHarness h = items();
            h.save(ITEMS, ranked("x1", 3), ranked("x2", 1), ranked("x3", 2));
            ParityHarness.assertSame("sort nope asc", uuidOrder(sorted(h, "nope", SortDirection.asc)), false);
        }

        @Test
        @DisplayName("on MongoDB's internal _id (= uuid there)")
        void mongoIdField() {
            ParityHarness h = items();
            h.save(ITEMS, item("m2"), item("m1"), item("m3"));
            ParityHarness.assertSame("sort _id asc", uuidOrder(sorted(h, "_id", SortDirection.asc)), true);
        }

        private ParityHarness addresses() {
            ParityHarness h = items();
            Item a = item("a1");
            a.address = new Address("Paris", "75000");
            Item b = item("a2");
            b.address = new Address("Lyon", "69000");
            Item c = item("a3");
            c.address = new Address(null, "01000");
            Item d = item("a4");
            Item e = item("a5");
            e.address = new Address("Annecy", null);
            h.save(ITEMS, a, b, c, d, e);
            return h;
        }

        @Test
        @DisplayName("on a dotted embedded-POJO leaf ascending (null POJO and null leaf first)")
        void dottedAscending() {
            Outcome o = sorted(addresses(), "address.city", SortDirection.asc);
            // The two cityless rows tie: compare the sequence of sort keys.
            ParityHarness.assertSame("sort address.city asc (keys)", map(o, Sort::cityKeys), true);
        }

        @Test
        @DisplayName("on a dotted embedded-POJO leaf descending")
        void dottedDescending() {
            Outcome o = sorted(addresses(), "address.city", SortDirection.desc);
            // The two cityless rows tie: compare the sort key sequence and the non-tied head.
            ParityHarness.assertSame("sort address.city desc (keys)", map(o, Sort::cityKeys), true);
        }

        @Test
        @DisplayName("on a whole embedded POJO (MongoDB compares sub-documents field by field)")
        void wholeEmbedded() {
            ParityHarness.assertSame("sort address asc",
                    uuidOrder(sorted(addresses(), "address", SortDirection.asc)), true);
        }

        private static Object cityKeys(List<Object> l) {
            return l.stream().map(x -> ((Item) x).address == null || ((Item) x).address.city == null ? "<null>"
                    : ((Item) x).address.city).toList();
        }

        private ParityHarness tagged() {
            ParityHarness h = items();
            Item a = item("g1");
            a.tags = List.of("m", "c");
            Item b = item("g2");
            b.tags = List.of("d");
            Item c = item("g3");
            c.tags = List.of("a", "z");
            Item d = item("g4");
            d.tags = List.of("k");
            h.save(ITEMS, a, b, c, d);
            return h;
        }

        @Test
        @DisplayName("on a String array ascending (MongoDB: by the smallest element)")
        void arrayAscending() {
            ParityHarness.assertSame("sort tags asc", uuidOrder(sorted(tagged(), "tags", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("on a String array descending (MongoDB: by the largest element)")
        void arrayDescending() {
            ParityHarness.assertSame("sort tags desc", uuidOrder(sorted(tagged(), "tags", SortDirection.desc)), true);
        }

        @Test
        @DisplayName("on an Integer array ascending")
        void intArrayAscending() {
            ParityHarness h = items();
            Item a = item("n1");
            a.nums = List.of(5, 9);
            Item b = item("n2");
            b.nums = List.of(7);
            Item c = item("n3");
            c.nums = List.of(-1, 100);
            h.save(ITEMS, a, b, c);
            ParityHarness.assertSame("sort nums asc", uuidOrder(sorted(h, "nums", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("on a Map field (MongoDB compares sub-documents)")
        void mapField() {
            ParityHarness h = items();
            Item a = item("k1");
            a.attrs = Map.of("color", "red");
            Item b = item("k2");
            b.attrs = Map.of("color", "blue");
            h.save(ITEMS, a, b);
            ParityHarness.assertSame("sort attrs asc", uuidOrder(sorted(h, "attrs", SortDirection.asc)), true);
        }

        @Test
        @DisplayName("on a composition field (MongoDB compares DBRefs: by $id within one collection)")
        void compositionField() {
            ParityHarness h = ParityHarness.of(new Domain("orders", Order.class, Map.of("customer", "customers")),
                    Domain.of("customers", Customer.class));
            Customer c1 = new Customer();
            c1.uuid = "c1";
            c1.name = "Zoe";
            Customer c2 = new Customer();
            c2.uuid = "c2";
            c2.name = "Adam";
            h.save("customers", c1, c2);
            Order o1 = new Order();
            o1.uuid = "o1";
            o1.customer = c2;
            Order o2 = new Order();
            o2.uuid = "o2";
            o2.customer = c1;
            Order o3 = new Order();
            o3.uuid = "o3";
            h.save("orders", o1, o2, o3);
            Outcome o = h.find("orders", Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("customer", SortDirection.asc)), Optional.empty());
            ParityHarness.assertSame("sort customer asc", uuidOrder(o), true);
        }
    }

    // ================================================================== PAGINATION

    @Nested
    @DisplayName("Pagination")
    class Pagination {

        private ParityHarness five() {
            ParityHarness h = items();
            h.save(ITEMS, ranked("p3", 30), ranked("p1", 10), ranked("p5", 50), ranked("p2", 20), item("p4"));
            return h;
        }

        @Test
        @DisplayName("pages 0, 1, 2 of size 2 sorted ascending")
        void pagesSorted() {
            ParityHarness h = five();
            ISort s = ParityFilter.sort("rank", SortDirection.asc);
            ParityHarness.assertSame("page 0", uuidOrder(paged(h, ParityFilter.page(0, 2), s)), true);
            ParityHarness.assertSame("page 1", uuidOrder(paged(h, ParityFilter.page(1, 2), s)), true);
            ParityHarness.assertSame("page 2", uuidOrder(paged(h, ParityFilter.page(2, 2), s)), true);
        }

        @Test
        @DisplayName("page 1 of size 2 sorted descending")
        void pageDescending() {
            ParityHarness.assertSame("page 1 desc", uuidOrder(paged(five(), ParityFilter.page(1, 2),
                    ParityFilter.sort("rank", SortDirection.desc))), true);
        }

        @Test
        @DisplayName("page size 0 at index 0 returns everything")
        void sizeZeroIndexZero() {
            ParityHarness.assertSame("page(0,0)", uuidOrder(paged(five(), ParityFilter.page(0, 0),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("page size 0 at index 3 returns everything (skip = 3*0)")
        void sizeZeroIndexThree() {
            ParityHarness.assertSame("page(3,0)", uuidOrder(paged(five(), ParityFilter.page(3, 0),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("a page index beyond the end is empty")
        void beyondTheEnd() {
            ParityHarness.assertSame("page(7,2)", uuidOrder(paged(five(), ParityFilter.page(7, 2),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("the last, partial page")
        void partialLastPage() {
            ParityHarness.assertSame("page(1,3)", uuidOrder(paged(five(), ParityFilter.page(1, 3),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("without a sort: page sizes agree")
        void unsortedSizes() {
            ParityHarness h = five();
            Outcome sizes = concat(map(paged(h, ParityFilter.page(0, 2), null), l -> List.of(l.size())),
                    map(paged(h, ParityFilter.page(1, 2), null), l -> List.of(l.size())),
                    map(paged(h, ParityFilter.page(2, 2), null), l -> List.of(l.size())),
                    map(paged(h, ParityFilter.page(3, 2), null), l -> List.of(l.size())));
            ParityHarness.assertSame("unsorted page sizes", sizes, true);
        }

        @Test
        @DisplayName("without a sort: the union of all pages is every row, once")
        void unsortedUnion() {
            ParityHarness h = five();
            Outcome all = concat(paged(h, ParityFilter.page(0, 2), null), paged(h, ParityFilter.page(1, 2), null),
                    paged(h, ParityFilter.page(2, 2), null));
            ParityHarness.assertSame("unsorted union", map(all, ParitySortPageProjectionTest::unionReport), true);
        }

        private ParityHarness ties() {
            ParityHarness h = items();
            // Inserted in reverse uuid order, so "natural" and "by id" orders differ.
            for (int k = 9; k >= 1; k--) {
                Item i = item("q" + k);
                i.group = k % 3;
                h.save(ITEMS, i);
            }
            return h;
        }

        @Test
        @DisplayName("ties in the sort key across pages: union of all pages is every row, once")
        void tiesUnion() {
            ParityHarness h = ties();
            ISort s = ParityFilter.sort("group", SortDirection.asc);
            Outcome all = concat(paged(h, ParityFilter.page(0, 2), s), paged(h, ParityFilter.page(1, 2), s),
                    paged(h, ParityFilter.page(2, 2), s), paged(h, ParityFilter.page(3, 2), s),
                    paged(h, ParityFilter.page(4, 2), s));
            ParityHarness.assertSame("tied union", map(all, ParitySortPageProjectionTest::unionReport), true);
        }

        @Test
        @DisplayName("ties in the sort key across pages: each page carries the same sort keys")
        void tiesKeys() {
            ParityHarness h = ties();
            ISort s = ParityFilter.sort("group", SortDirection.desc);
            Outcome all = concat(paged(h, ParityFilter.page(0, 4), s), paged(h, ParityFilter.page(1, 4), s),
                    paged(h, ParityFilter.page(2, 4), s));
            ParityHarness.assertSame("tied keys", map(all, l -> l.stream().map(x -> ((Item) x).group).toList()), true);
        }

        @Test
        @DisplayName("a page with a filter and a sort")
        void filteredPage() {
            ParityHarness h = five();
            Outcome o = h.find(ITEMS, Optional.of(ParityFilter.page(1, 1)),
                    Optional.of(ParityFilter.field("rank", "$gte", 20)),
                    Optional.of(ParityFilter.sort("rank", SortDirection.desc)), Optional.empty());
            ParityHarness.assertSame("filter rank>=20, page(1,1), desc", uuidOrder(o), true);
        }

        @Test
        @DisplayName("a page on an empty collection")
        void emptyCollection() {
            ParityHarness h = items();
            ParityHarness.assertSame("empty page", uuidOrder(paged(h, ParityFilter.page(0, 5),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("a negative page size (MongoDB: a negative limit is a single batch of |n|)")
        void negativeSize() {
            ParityHarness.assertSame("page(0,-2)", uuidOrder(paged(five(), ParityFilter.page(0, -2),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("a negative page index")
        void negativeIndex() {
            ParityHarness.assertSame("page(-1,2)", uuidOrder(paged(five(), ParityFilter.page(-1, 2),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }

        @Test
        @DisplayName("a page index whose offset overflows an int")
        void overflowingIndex() {
            ParityHarness.assertSame("page(MAX,2)", uuidOrder(paged(five(), ParityFilter.page(Integer.MAX_VALUE, 2),
                    ParityFilter.sort("rank", SortDirection.asc))), true);
        }
    }

    // ================================================================== PROJECTION

    @Nested
    @DisplayName("Projection")
    class Projection {

        private ParityHarness rich() {
            ParityHarness h = items();
            Item full = item("f1");
            full.name = "full";
            full.rank = 7;
            full.active = true;
            full.color = Color.GREEN;
            full.address = new Address("Paris", "75000");
            full.tags = List.of("x", "y");
            full.attrs = Map.of("k", "v");
            full.status = "live";
            Item emptyish = item("f2");
            emptyish.name = "emptyish";
            emptyish.address = new Address();
            emptyish.tags = new ArrayList<>();
            emptyish.attrs = new LinkedHashMap<>();
            Item bare = item("f3");
            bare.tags = null;
            bare.status = null;
            Item partial = item("f4");
            partial.name = "partial";
            partial.address = new Address(null, "69000");
            partial.tags = List.of("z");
            h.save(ITEMS, full, emptyish, bare, partial);
            return h;
        }

        @Test
        @DisplayName("a scalar field: non-projected fields hold their constructor defaults")
        void scalar() {
            ParityHarness.assertSame("projection [name]", projected(rich(), ITEMS, List.of("name")), false);
        }

        @Test
        @DisplayName("the uuid only")
        void uuidOnly() {
            ParityHarness.assertSame("projection [uuid]", projected(rich(), ITEMS, List.of("uuid")), false);
        }

        @Test
        @DisplayName("a primitive and an enum field")
        void primitiveAndEnum() {
            ParityHarness.assertSame("projection [active, color]",
                    projected(rich(), ITEMS, List.of("active", "color")), false);
        }

        @Test
        @DisplayName("a whole embedded POJO (incl. one stored with every field null)")
        void embedded() {
            ParityHarness.assertSame("projection [address]", projected(rich(), ITEMS, List.of("address")), false);
        }

        @Test
        @DisplayName("a dotted leaf: MongoDB returns the WHOLE sub-document")
        void dottedLeaf() {
            ParityHarness.assertSame("projection [address.city]",
                    projected(rich(), ITEMS, List.of("address.city")), false);
        }

        @Test
        @DisplayName("a dotted path to an unknown leaf of a known POJO")
        void dottedUnknownLeaf() {
            ParityHarness.assertSame("projection [address.nope]",
                    projected(rich(), ITEMS, List.of("address.nope")), false);
        }

        @Test
        @DisplayName("a collection (filled, empty, never set)")
        void collection() {
            ParityHarness.assertSame("projection [tags]", projected(rich(), ITEMS, List.of("tags")), false);
        }

        @Test
        @DisplayName("a Map field (filled, empty, never set)")
        void projectMapField() {
            ParityHarness.assertSame("projection [attrs]", projected(rich(), ITEMS, List.of("attrs")), false);
        }

        @Test
        @DisplayName("a field with an initialiser, stored null, projected")
        void initialiserProjected() {
            ParityHarness.assertSame("projection [status]", projected(rich(), ITEMS, List.of("status")), false);
        }

        @Test
        @DisplayName("an unknown field: only the identity comes back")
        void projectUnknownField() {
            ParityHarness.assertSame("projection [nope]", projected(rich(), ITEMS, List.of("nope")), false);
        }

        @Test
        @DisplayName("an empty projection list: everything comes back")
        void emptyList() {
            ParityHarness.assertSame("projection []", projected(rich(), ITEMS, List.of()), false);
        }

        @Test
        @DisplayName("a projection of blanks only: everything comes back")
        void blanksOnly() {
            ParityHarness.assertSame("projection [' ']", projected(rich(), ITEMS, Arrays.asList(" ", "")), false);
        }

        @Test
        @DisplayName("no projection at all: the full read of the same rows")
        void none() {
            ParityHarness h = rich();
            ParityHarness.assertSame("no projection",
                    h.find(ITEMS, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), false);
        }

        @Test
        @DisplayName("a composition is always kept and resolved, even when not projected")
        void composition() {
            ParityHarness h = ParityHarness.of(new Domain("orders", Order.class, Map.of("customer", "customers")),
                    Domain.of("customers", Customer.class));
            Customer c = new Customer();
            c.uuid = "c1";
            c.name = "Zoe";
            h.save("customers", c);
            Order o1 = new Order();
            o1.uuid = "o1";
            o1.label = "first";
            o1.amount = 12;
            o1.customer = c;
            Order o2 = new Order();
            o2.uuid = "o2";
            o2.label = "second";
            h.save("orders", o1, o2);
            ParityHarness.assertSame("orders projection [label]", projected(h, "orders", List.of("label")), false);
        }

        @Test
        @DisplayName("a projection on the composition field itself")
        void compositionProjected() {
            ParityHarness h = ParityHarness.of(new Domain("orders", Order.class, Map.of("customer", "customers")),
                    Domain.of("customers", Customer.class));
            Customer c = new Customer();
            c.uuid = "c1";
            c.name = "Zoe";
            h.save("customers", c);
            Order o1 = new Order();
            o1.uuid = "o1";
            o1.label = "first";
            o1.customer = c;
            h.save("orders", o1);
            ParityHarness.assertSame("orders projection [customer]", projected(h, "orders", List.of("customer")),
                    false);
        }

        @Test
        @DisplayName("projection combined with sort and page")
        void withSortAndPage() {
            ParityHarness h = items();
            for (int k = 1; k <= 5; k++) {
                Item i = item("w" + k);
                i.name = "n" + k;
                i.rank = 10 - k;
                i.address = new Address("C" + k, "Z" + k);
                h.save(ITEMS, i);
            }
            Outcome o = h.find(ITEMS, Optional.of(ParityFilter.page(1, 2)), Optional.empty(),
                    Optional.of(ParityFilter.sort("rank", SortDirection.asc)), Optional.of(List.of("name", "rank")));
            ParityHarness.assertSame("projection [name, rank] + sort + page", o, true);
        }

        @Test
        @DisplayName("projection on a field used by the sort but not projected")
        void sortOnNonProjected() {
            ParityHarness h = items();
            for (int k = 1; k <= 4; k++) {
                Item i = item("v" + k);
                i.name = "n" + k;
                i.rank = 10 - k;
                h.save(ITEMS, i);
            }
            Outcome o = h.find(ITEMS, Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("rank", SortDirection.asc)), Optional.of(List.of("name")));
            ParityHarness.assertSame("projection [name] sorted by rank", o, true);
        }
    }
}
