package com.garganttua.dao.postgresql.parity;

import static com.garganttua.dao.postgresql.parity.ParityFilter.field;
import static com.garganttua.dao.postgresql.parity.ParityFilter.listed;
import static com.garganttua.dao.postgresql.parity.ParityFilter.logical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Parity of the MongoDB and PostgreSQL DAOs on ABSENT, NULL and UNKNOWN fields.
 *
 * <p>
 * The MongoDB DAO never stores a null Java field: {@code MongoDocumentWriter} skips it, so a null
 * field is an ABSENT field in the document. Every MongoDB operator then answers with its "missing"
 * semantics — {@code $eq null}, {@code $gte null}, {@code $in [null]} match it, {@code $ne x} and
 * {@code $nin [x]} match it, {@code $empty} ({@code $exists: false}, value ignored) matches it. A field
 * name the DTO does not have at all is simply absent from every document, so it behaves the same way.
 * These tests pin that behaviour as the specification the PostgreSQL DAO must follow.
 * </p>
 */
@DisplayName("Parity — absent, null and unknown fields")
class ParityMissingFieldTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Address {
        String city;
        String zip;
    }

    public static class Item {
        String uuid;
        String name;
        Integer age;
        List<String> tags;
        Address address;
        Map<String, Integer> stock;
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

    private static Address address(String city, String zip) {
        Address a = new Address();
        a.city = city;
        a.zip = zip;
        return a;
    }

    private static Item item(String uuid, String name, Integer age, List<String> tags, Address address,
            Map<String, Integer> stock) {
        Item i = new Item();
        i.uuid = uuid;
        i.name = name;
        i.age = age;
        i.tags = tags == null ? null : new ArrayList<>(tags);
        i.address = address;
        i.stock = stock == null ? null : new LinkedHashMap<>(stock);
        return i;
    }

    /**
     * Rows whose values are either null or meaningful — no empty collections, no all-null POJO shells —
     * so the only thing a scenario can disagree on is WHICH rows match.
     */
    private static ParityHarness plain() {
        ParityHarness h = ParityHarness.of(Domain.of("items", Item.class));
        h.save("items",
                item("a1", "alpha", 18, List.of("x"), address("Paris", "75"), Map.of("apple", 1)),
                item("a2", "beta", 30, List.of("y", "x"), address("Lyon", null), Map.of("pear", 2)),
                item("a3", null, null, null, null, null),
                item("a4", "alpha", null, List.of("z"), address(null, "69"), null),
                item("a5", "gamma", 5, null, null, Map.of("apple", 3)));
        return h;
    }

    private static void find(ParityHarness h, String what, IFilter filter) {
        ParityHarness.assertSame(what, ids(h.find("items", filter)), false);
    }

    /**
     * The same outcome, each returned DTO reduced to its uuid. A filter scenario asks WHICH rows match;
     * how a matched row reads back (a null list read as [] by PostgreSQL, for instance) is pinned
     * separately by the read-back scenarios, and must not mask or fake a filter divergence.
     */
    private static Outcome ids(Outcome o) {
        return new Outcome(uuids(o.mongo()), uuids(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object uuids(Object result) {
        if (!(result instanceof List<?> list)) {
            return result;
        }
        List<String> uuids = new ArrayList<>();
        for (Object dto : list) {
            uuids.add(switch (dto) {
                case Item i -> i.uuid;
                case Order o -> o.uuid;
                default -> String.valueOf(dto);
            });
        }
        return uuids;
    }

    private static void count(ParityHarness h, String what, IFilter filter) {
        ParityHarness.assertSame(what + " (count)", h.count("items", filter), false);
    }

    @Nested
    @DisplayName("a null scalar field (omitted from the MongoDB document)")
    class NullScalar {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("$eq null matches the rows without the field")
        void eqNull() {
            find(h, "name $eq null", field("name", "$eq", null));
        }

        @Test
        @DisplayName("$ne null matches the rows with the field")
        void neNull() {
            find(h, "name $ne null", field("name", "$ne", null));
        }

        @Test
        @DisplayName("$ne x also matches the rows without the field")
        void neValue() {
            find(h, "name $ne alpha", field("name", "$ne", "alpha"));
        }

        @Test
        @DisplayName("$nin [x] also matches the rows without the field")
        void ninValue() {
            find(h, "name $nin [alpha]", listed("name", "$nin", "alpha"));
        }

        @Test
        @DisplayName("$nin [x, null] excludes the rows without the field")
        void ninWithNull() {
            find(h, "name $nin [alpha, null]", listed("name", "$nin", "alpha", null));
        }

        @Test
        @DisplayName("$in [null, x] matches the rows without the field and the x rows")
        void inWithNull() {
            find(h, "name $in [null, beta]", listed("name", "$in", null, "beta"));
        }

        @Test
        @DisplayName("$in [null] alone matches only the rows without the field")
        void inOnlyNull() {
            find(h, "age $in [null]", listed("age", "$in", (Object) null));
        }

        @Test
        @DisplayName("$gt null matches nothing")
        void gtNull() {
            find(h, "age $gt null", field("age", "$gt", null));
        }

        @Test
        @DisplayName("$gte null matches the rows without the field")
        void gteNull() {
            find(h, "age $gte null", field("age", "$gte", null));
        }

        @Test
        @DisplayName("$lt null matches nothing")
        void ltNull() {
            find(h, "age $lt null", field("age", "$lt", null));
        }

        @Test
        @DisplayName("$lte null matches the rows without the field")
        void lteNull() {
            find(h, "age $lte null", field("age", "$lte", null));
        }

        @Test
        @DisplayName("$lt 20 does not match the rows without the field")
        void ltValueSkipsNull() {
            find(h, "age $lt 20", field("age", "$lt", 20));
        }

        @Test
        @DisplayName("$empty matches the rows without the field")
        void empty() {
            find(h, "name $empty true", field("name", "$empty", true));
        }

        @Test
        @DisplayName("$empty ignores its value: false still means 'absent'")
        void emptyValueIgnored() {
            find(h, "name $empty false", field("name", "$empty", false));
        }

        @Test
        @DisplayName("$ne 18 (Integer) keeps every row but the 18 one, null rows included")
        void neTypedInteger() {
            find(h, "age $ne 18", field("age", "$ne", 18));
        }

        @Test
        @DisplayName("$ne \"18\" (String on an Integer field) differs from every stored value on MongoDB")
        void neMistypedString() {
            find(h, "age $ne \"18\"", field("age", "$ne", "18"));
        }

        @Test
        @DisplayName("$nin [\"18\"] (String on an Integer field) excludes nothing on MongoDB")
        void ninMistypedString() {
            find(h, "age $nin [\"18\"]", listed("age", "$nin", "18"));
        }

        @Test
        @DisplayName("$eq \"18\" (String on an Integer field) matches nothing on MongoDB")
        void eqMistypedString() {
            find(h, "age $eq \"18\"", field("age", "$eq", "18"));
        }

        @Test
        @DisplayName("count agrees with find on $ne x and $empty")
        void countAgrees() {
            count(h, "name $ne alpha", field("name", "$ne", "alpha"));
            count(h, "name $empty", field("name", "$empty", true));
            count(h, "age $gte null", field("age", "$gte", null));
        }
    }

    @Nested
    @DisplayName("a field the DTO does not declare")
    class UnknownField {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("$eq x matches nothing")
        void eqValue() {
            find(h, "ghost $eq x", field("ghost", "$eq", "x"));
        }

        @Test
        @DisplayName("$eq null matches everything")
        void eqNull() {
            find(h, "ghost $eq null", field("ghost", "$eq", null));
        }

        @Test
        @DisplayName("$ne x matches everything")
        void neValue() {
            find(h, "ghost $ne x", field("ghost", "$ne", "x"));
        }

        @Test
        @DisplayName("$ne null matches nothing")
        void neNull() {
            find(h, "ghost $ne null", field("ghost", "$ne", null));
        }

        @Test
        @DisplayName("$nin [x] matches everything")
        void nin() {
            find(h, "ghost $nin [x]", listed("ghost", "$nin", "x"));
        }

        @Test
        @DisplayName("$in [x] matches nothing")
        void in() {
            find(h, "ghost $in [x]", listed("ghost", "$in", "x"));
        }

        @Test
        @DisplayName("$empty matches everything")
        void empty() {
            find(h, "ghost $empty", field("ghost", "$empty", true));
        }

        @Test
        @DisplayName("$gt 1 matches nothing")
        void gt() {
            find(h, "ghost $gt 1", field("ghost", "$gt", 1));
        }

        @Test
        @DisplayName("$regex matches nothing")
        void regex() {
            find(h, "ghost $regex a", field("ghost", "$regex", "a"));
        }

        @Test
        @DisplayName("a dotted path into a scalar field (name.first) is absent everywhere")
        void pathIntoScalar() {
            find(h, "name.first $eq null", field("name.first", "$eq", null));
        }

        @Test
        @DisplayName("count agrees with find ($ne x on an unknown field counts everything)")
        void countAgrees() {
            count(h, "ghost $ne x", field("ghost", "$ne", "x"));
            count(h, "ghost $eq x", field("ghost", "$eq", "x"));
        }
    }

    @Nested
    @DisplayName("an unknown field inside a logical operator")
    class UnknownInLogical {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("$or [ghost = x, name = beta] is just name = beta")
        void orWithUnknown() {
            find(h, "$or [ghost $eq x, name $eq beta]",
                    logical("$or", field("ghost", "$eq", "x"), field("name", "$eq", "beta")));
        }

        @Test
        @DisplayName("$nor [ghost = x, name = beta] keeps everything but beta")
        void norWithUnknown() {
            find(h, "$nor [ghost $eq x, name $eq beta]",
                    logical("$nor", field("ghost", "$eq", "x"), field("name", "$eq", "beta")));
        }

        @Test
        @DisplayName("$and [name != null, ghost $empty] is just name != null")
        void andWithUnknownEmpty() {
            find(h, "$and [name $ne null, ghost $empty]",
                    logical("$and", field("name", "$ne", null), field("ghost", "$empty", true)));
        }

        @Test
        @DisplayName("$nor [name = alpha, age > 10] keeps the rows where both fields are missing or false")
        void norOverNulls() {
            find(h, "$nor [name $eq alpha, age $gt 10]",
                    logical("$nor", field("name", "$eq", "alpha"), field("age", "$gt", 10)));
        }

        @Test
        @DisplayName("count agrees with find under $or / $nor with an unknown field")
        void countAgrees() {
            count(h, "$or [ghost $eq x, name $eq beta]",
                    logical("$or", field("ghost", "$eq", "x"), field("name", "$eq", "beta")));
            count(h, "$nor [ghost $eq x, name $eq beta]",
                    logical("$nor", field("ghost", "$eq", "x"), field("name", "$eq", "beta")));
        }
    }

    @Nested
    @DisplayName("dotted paths into an embedded POJO (null / present with a null leaf / present)")
    class EmbeddedPaths {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("address.city $eq null matches a null address and a null city")
        void leafEqNull() {
            find(h, "address.city $eq null", field("address.city", "$eq", null));
        }

        @Test
        @DisplayName("address.city $ne Paris matches a null address and a null city")
        void leafNeValue() {
            find(h, "address.city $ne Paris", field("address.city", "$ne", "Paris"));
        }

        @Test
        @DisplayName("address.city $ne null matches only a present city")
        void leafNeNull() {
            find(h, "address.city $ne null", field("address.city", "$ne", null));
        }

        @Test
        @DisplayName("address.zip $empty matches a null address and a null zip")
        void leafEmpty() {
            find(h, "address.zip $empty", field("address.zip", "$empty", true));
        }

        @Test
        @DisplayName("address.city $nin [Paris] matches a null address and a null city")
        void leafNin() {
            find(h, "address.city $nin [Paris]", listed("address.city", "$nin", "Paris"));
        }

        @Test
        @DisplayName("address $empty matches only a null address")
        void pojoEmpty() {
            find(h, "address $empty", field("address", "$empty", true));
        }

        @Test
        @DisplayName("address $eq null matches only a null address")
        void pojoEqNull() {
            find(h, "address $eq null", field("address", "$eq", null));
        }

        @Test
        @DisplayName("address $ne null matches every present address")
        void pojoNeNull() {
            find(h, "address $ne null", field("address", "$ne", null));
        }

        @Test
        @DisplayName("address $ne \"x\" (a POJO against a scalar) matches everything on MongoDB")
        void pojoNeScalar() {
            find(h, "address $ne x", field("address", "$ne", "x"));
        }

        @Test
        @DisplayName("an unknown leaf (address.street) $eq null matches everything")
        void unknownLeafEqNull() {
            find(h, "address.street $eq null", field("address.street", "$eq", null));
        }

        @Test
        @DisplayName("an unknown leaf (address.street) $ne x matches everything")
        void unknownLeafNe() {
            find(h, "address.street $ne x", field("address.street", "$ne", "x"));
        }

        @Test
        @DisplayName("an unknown leaf (address.street) $eq x matches nothing")
        void unknownLeafEq() {
            find(h, "address.street $eq x", field("address.street", "$eq", "x"));
        }

        @Test
        @DisplayName("count agrees with find on address paths")
        void countAgrees() {
            count(h, "address.city $ne Paris", field("address.city", "$ne", "Paris"));
            count(h, "address $empty", field("address", "$empty", true));
            count(h, "address.street $ne x", field("address.street", "$ne", "x"));
        }
    }

    @Nested
    @DisplayName("a null collection or map (omitted from the MongoDB document)")
    class NullCollections {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("tags $eq null matches the rows without tags")
        void tagsEqNull() {
            find(h, "tags $eq null", field("tags", "$eq", null));
        }

        @Test
        @DisplayName("tags $ne x matches the rows without tags and those not holding x")
        void tagsNe() {
            find(h, "tags $ne x", field("tags", "$ne", "x"));
        }

        @Test
        @DisplayName("tags $empty matches the rows without tags")
        void tagsEmpty() {
            find(h, "tags $empty", field("tags", "$empty", true));
        }

        @Test
        @DisplayName("tags $nin [x] matches the rows without tags")
        void tagsNin() {
            find(h, "tags $nin [x]", listed("tags", "$nin", "x"));
        }

        @Test
        @DisplayName("stock.apple $eq null matches the rows without stock or without the apple key")
        void mapKeyEqNull() {
            find(h, "stock.apple $eq null", field("stock.apple", "$eq", null));
        }

        @Test
        @DisplayName("stock.apple $ne 1 matches the rows without stock or without the apple key")
        void mapKeyNe() {
            find(h, "stock.apple $ne 1", field("stock.apple", "$ne", 1));
        }

        @Test
        @DisplayName("reading back: a null list and a null map stay null")
        void readBackNulls() {
            ParityHarness.assertSame("find a3 (all fields null)", h.find("items", field("uuid", "$eq", "a3")), false);
        }

        @Test
        @DisplayName("stock $empty matches the rows without stock")
        void mapEmpty() {
            find(h, "stock $empty", field("stock", "$empty", true));
        }
    }

    @Nested
    @DisplayName("empty collections, empty maps, and all-null POJO shells")
    class EmptyShells {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = ParityHarness.of(Domain.of("items", Item.class));
            h.save("items",
                    item("b1", "empty", 1, List.of(), address(null, null), Map.of()),
                    item("b2", "null", 2, null, null, null),
                    item("b3", "full", 3, List.of("x"), address("Paris", null), Map.of("apple", 1)));
        }

        @Test
        @DisplayName("reading back: [] stays [], {} stays {}, an all-null POJO stays an object")
        void readBack() {
            ParityHarness.assertSame("find all (whole DTOs)", h.find("items", null), false);
        }

        @Test
        @DisplayName("tags $empty does not match an empty list (it is stored, as [])")
        void emptyListIsNotAbsent() {
            count(h, "tags $empty", field("tags", "$empty", true));
        }

        @Test
        @DisplayName("tags $eq null does not match an empty list")
        void emptyListIsNotNull() {
            count(h, "tags $eq null", field("tags", "$eq", null));
        }

        @Test
        @DisplayName("tags $ne null matches an empty list")
        void emptyListIsNotNullNe() {
            count(h, "tags $ne null", field("tags", "$ne", null));
        }

        @Test
        @DisplayName("tags $ne x matches the empty list and the null list")
        void emptyListNe() {
            count(h, "tags $ne x", field("tags", "$ne", "x"));
        }

        @Test
        @DisplayName("stock $empty does not match an empty map")
        void emptyMapIsNotAbsent() {
            count(h, "stock $empty", field("stock", "$empty", true));
        }

        @Test
        @DisplayName("stock $eq null does not match an empty map")
        void emptyMapIsNotNull() {
            count(h, "stock $eq null", field("stock", "$eq", null));
        }

        @Test
        @DisplayName("address $empty does not match an all-null POJO (stored as {})")
        void shellIsNotAbsent() {
            count(h, "address $empty", field("address", "$empty", true));
        }

        @Test
        @DisplayName("address $eq null does not match an all-null POJO")
        void shellIsNotNull() {
            count(h, "address $eq null", field("address", "$eq", null));
        }

        @Test
        @DisplayName("address $ne null matches an all-null POJO")
        void shellNeNull() {
            count(h, "address $ne null", field("address", "$ne", null));
        }

        @Test
        @DisplayName("address.city $eq null matches the shell and the null address alike")
        void shellLeafEqNull() {
            count(h, "address.city $eq null", field("address.city", "$eq", null));
        }
    }

    @Nested
    @DisplayName("sort, page and projection over null or unknown fields")
    class SortAndProjection {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = plain();
        }

        @Test
        @DisplayName("ascending sort puts the rows without the field first")
        void ascNullsFirst() {
            ParityHarness.assertSame("age asc, page 0 size 2",
                    ids(h.find("items", Optional.of(ParityFilter.page(0, 2)), Optional.empty(),
                            Optional.of(ParityFilter.sort("age", SortDirection.asc)), Optional.empty())),
                    false);
        }

        @Test
        @DisplayName("descending sort puts the rows without the field last")
        void descNullsLast() {
            ParityHarness.assertSame("age desc, page 1 size 3",
                    ids(h.find("items", Optional.of(ParityFilter.page(1, 3)), Optional.empty(),
                            Optional.of(ParityFilter.sort("age", SortDirection.desc)), Optional.empty())),
                    false);
        }

        @Test
        @DisplayName("sorting on an unknown field is accepted (every row ties)")
        void sortUnknown() {
            ParityHarness.assertSame("sort ghost asc",
                    ids(h.find("items", Optional.empty(), Optional.empty(),
                            Optional.of(ParityFilter.sort("ghost", SortDirection.asc)), Optional.empty())),
                    false);
        }

        @Test
        @DisplayName("projecting an unknown field alongside a known one keeps the known one")
        void projectUnknown() {
            ParityHarness.assertSame("projection [name, ghost]",
                    h.find("items", Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.of(List.of("name", "ghost"))),
                    false);
        }

        @Test
        @DisplayName("projecting only an unknown field keeps just the identity")
        void projectOnlyUnknown() {
            ParityHarness.assertSame("projection [ghost]",
                    h.find("items", Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.of(List.of("ghost"))),
                    false);
        }
    }

    @Nested
    @DisplayName("a null composition reference")
    class NullComposition {

        private ParityHarness h;

        @BeforeEach
        void seed() {
            h = ParityHarness.of(new Domain("orders", Order.class, Map.of("customer", "customers")),
                    Domain.of("customers", Customer.class));
            Customer c = new Customer();
            c.uuid = "c1";
            c.name = "Ann";
            h.save("customers", c);
            Order o1 = new Order();
            o1.uuid = "o1";
            o1.label = "with";
            o1.customer = c;
            Order o2 = new Order();
            o2.uuid = "o2";
            o2.label = "without";
            h.save("orders", o1, o2);
        }

        @Test
        @DisplayName("customer $eq null matches the order without a customer")
        void eqNull() {
            ParityHarness.assertSame("customer $eq null", ids(h.find("orders", field("customer", "$eq", null))), false);
        }

        @Test
        @DisplayName("customer $ne null matches the order with a customer")
        void neNull() {
            ParityHarness.assertSame("customer $ne null", ids(h.find("orders", field("customer", "$ne", null))), false);
        }

        @Test
        @DisplayName("customer $empty matches the order without a customer")
        void empty() {
            ParityHarness.assertSame("customer $empty", ids(h.find("orders", field("customer", "$empty", true))), false);
        }

        @Test
        @DisplayName("customer.name $eq null: a DBRef has no 'name', so it matches every order on MongoDB")
        void pathThroughReference() {
            ParityHarness.assertSame("customer.name $eq null",
                    ids(h.find("orders", field("customer.name", "$eq", null))), false);
        }
    }
}
