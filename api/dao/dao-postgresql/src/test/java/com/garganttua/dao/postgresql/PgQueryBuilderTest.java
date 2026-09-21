package com.garganttua.dao.postgresql;

import static com.garganttua.dao.postgresql.PgQueryFixture.field;
import static com.garganttua.dao.postgresql.PgQueryFixture.listed;
import static com.garganttua.dao.postgresql.PgQueryFixture.logical;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.geojson.LngLatAlt;
import org.geojson.Polygon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.PgQueryFixture.Status;

/**
 * The query builder, proved against a real PostgreSQL: each filter is run and the ids it returns are
 * compared with what MongoDB would return for the same filter on the same data.
 *
 * <p>
 * Rows (see {@link PgQueryFixture#seed}): a = Alice/10/ACTIVE/Paris, tags [red, blue], lines A1×2 B2×5,
 * stock apple=3; b = Bob/42/CLOSED/Lyon, tags [blue], lines A1×1, stock apple=7 pear=1; c = Carol with
 * no total, status, address or collection rows; d = no name/5/ACTIVE/Paris, tags [green], line C3
 * with no qty; e = a hostile name/7/ACTIVE/Nice, tags [null], no other collection rows.
 * </p>
 */
@DisplayName("PgQueryBuilder")
class PgQueryBuilderTest {

    private static DataSource db;

    @BeforeAll
    static void setUp() throws Exception {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
        db = PgQueryFixture.seed();
    }

    private static Set<String> match(IFilter filter) throws Exception {
        PgQuery q = PgQueryFixture.builder().build(Optional.empty(), Optional.of(filter), Optional.empty(),
                Optional.empty());
        return new HashSet<>(PgQueryFixture.ids(db, q));
    }

    private static void assertMatches(IFilter filter, String... expected) throws Exception {
        Set<String> actual = match(filter);
        assertEquals(Set.of(expected), actual, () -> "filter matched the wrong rows; SQL was: " + where(filter));
    }

    private static String where(IFilter filter) {
        try {
            return PgQueryFixture.builder().count(filter).where();
        } catch (ApiException e) {
            return "<" + e.getMessage() + ">";
        }
    }

    @Nested
    @DisplayName("comparison operators on a scalar column")
    class Scalars {

        @Test
        @DisplayName("$eq matches the equal value only")
        void eq() throws Exception {
            assertMatches(field("name", "$eq", "Bob"), "b");
        }

        @Test
        @DisplayName("$eq null matches the rows where the field is NULL (Mongo: missing or null)")
        void eqNull() throws Exception {
            assertMatches(field("total", "$eq", null), "c");
        }

        @Test
        @DisplayName("$ne ALSO matches NULL rows, as Mongo's $ne matches documents without the field")
        void neMatchesNull() throws Exception {
            assertMatches(field("total", "$ne", 42), "a", "c", "d", "e");
        }

        @Test
        @DisplayName("$ne null matches every row that has a value")
        void neNull() throws Exception {
            assertMatches(field("total", "$ne", null), "a", "b", "d", "e");
        }

        @Test
        @DisplayName("$gt / $gte / $lt / $lte compare and exclude NULL")
        void ordering() throws Exception {
            assertMatches(field("total", "$gt", 7), "a", "b");
            assertMatches(field("total", "$gte", 7), "a", "b", "e");
            assertMatches(field("total", "$lt", 7), "d");
            assertMatches(field("total", "$lte", 7), "d", "e");
        }

        @Test
        @DisplayName("\"42\" against an INTEGER column matches nothing, as on MongoDB: a string is not a number")
        void stringAgainstNumber() throws Exception {
            assertMatches(field("total", "$eq", "42"));
            assertMatches(field("total", "$gt", "9"));
            assertMatches(field("total", "$ne", "42"), "a", "b", "c", "d", "e");
            assertMatches(listed("total", "$in", "42", 5), "d");
        }

        @Test
        @DisplayName("a value that is not a number, against a numeric column, matches nothing and does not fail")
        void notANumber() throws Exception {
            assertMatches(field("total", "$eq", "many"));
            assertMatches(field("total", "$eq", true));
        }

        @Test
        @DisplayName("numbers compare exactly, never narrowed to the column: 7.5 is not 7")
        void exactNumbers() throws Exception {
            assertMatches(field("total", "$eq", 7.5));
            assertMatches(field("total", "$gte", 6.5), "a", "b", "e");
            assertMatches(field("total", "$lt", 4_294_967_312L), "a", "b", "d", "e");
        }

        @Test
        @DisplayName("text orders by code point (COLLATE \"C\"): uppercase before lowercase")
        void codePointOrder() throws Exception {
            assertMatches(field("name", "$gt", "B"), "b", "c", "e");
        }

        @Test
        @DisplayName("an enum value compares by name(), the way it is stored")
        void enumByName() throws Exception {
            assertMatches(field("status", "$eq", Status.CLOSED), "b");
            assertMatches(field("status", "$eq", "ACTIVE"), "a", "d", "e");
        }

        @Test
        @DisplayName("an Instant compares as a TIMESTAMPTZ")
        void instant() throws Exception {
            assertMatches(field("createdAt", "$gt", Instant.parse("2026-01-03T00:00:00Z")), "d", "e");
        }

        @Test
        @DisplayName("$regex matches text")
        void regex() throws Exception {
            assertMatches(field("name", "$regex", "^(Al|Bo)"), "a", "b");
        }

        @Test
        @DisplayName("$regex on a numeric column matches nothing, as in MongoDB (a regex only matches strings)")
        void regexOnNumber() throws Exception {
            assertMatches(field("total", "$regex", "4"));
            assertMatches(logical("$nor", field("total", "$regex", "4"), field("name", "$eq", "Bob")), "a", "c", "d",
                    "e");
        }

        @Test
        @DisplayName("an untranslatable $regex is refused with a message naming the field and the construct")
        void regexUntranslatable() {
            ApiException e = assertThrows(ApiException.class, () -> match(field("name", "$regex", "a++b")));
            assertTrue(e.getMessage().contains("'name'") && e.getMessage().contains("possessive"),
                    () -> "message should name the field and the construct: " + e.getMessage());
        }

        @Test
        @DisplayName("$empty (Mongo exists:false) matches NULL")
        void empty() throws Exception {
            assertMatches(field("name", "$empty", null), "d");
        }

        @Test
        @DisplayName("$in matches any listed value")
        void in() throws Exception {
            assertMatches(listed("total", "$in", 5, 42), "b", "d");
        }

        @Test
        @DisplayName("$in listing null also matches NULL rows")
        void inWithNull() throws Exception {
            assertMatches(listed("total", "$in", 5, null), "c", "d");
        }

        @Test
        @DisplayName("$nin matches NULL rows too, as Mongo's $nin matches missing fields")
        void ninMatchesNull() throws Exception {
            assertMatches(listed("total", "$nin", 5, 42), "a", "c", "e");
        }

        @Test
        @DisplayName("$nin listing null excludes NULL rows")
        void ninWithNull() throws Exception {
            assertMatches(listed("total", "$nin", 5, null), "a", "b", "e");
        }

        @Test
        @DisplayName("a flattened embedded field is addressed by its dotted path")
        void flattened() throws Exception {
            assertMatches(field("address.city", "$eq", "Paris"), "a", "d");
            assertMatches(field("address.zip", "$ne", 75000), "b", "c", "d", "e");
        }
    }

    @Nested
    @DisplayName("collections — Mongo array semantics over child tables")
    class Collections {

        @Test
        @DisplayName("$eq on a collection matches when SOME element equals (EXISTS)")
        void eqSomeElement() throws Exception {
            assertMatches(field("tags", "$eq", "blue"), "a", "b");
        }

        @Test
        @DisplayName("$ne on a collection matches when NO element equals (NOT EXISTS) — including empty ones")
        void neNoElement() throws Exception {
            assertMatches(field("tags", "$ne", "blue"), "c", "d", "e");
        }

        @Test
        @DisplayName("$in on a collection: some element listed; $nin: no element listed")
        void inAndNin() throws Exception {
            assertMatches(listed("tags", "$in", "red", "green"), "a", "d");
            assertMatches(listed("tags", "$nin", "red", "green"), "b", "c", "e");
        }

        @Test
        @DisplayName("$empty on a collection matches owners with no element — a [null] collection is not empty")
        void emptyCollection() throws Exception {
            // e holds one null tag: MongoDB's {tags: [null]} has the field, so exists:false does not match it.
            assertMatches(field("tags", "$empty", null), "c");
        }

        @Test
        @DisplayName("$eq null on a collection matches owners without elements")
        void eqNullCollection() throws Exception {
            assertMatches(field("tags", "$eq", null), "c", "e");
        }

        @Test
        @DisplayName("a field of a collection's elements ('lines.sku'): EXISTS for $eq, NOT EXISTS for $ne")
        void pojoElements() throws Exception {
            assertMatches(field("lines.sku", "$eq", "A1"), "a", "b");
            assertMatches(field("lines.sku", "$ne", "A1"), "c", "d", "e");
            assertMatches(field("lines.qty", "$gt", 3), "a");
            assertMatches(field("lines.qty", "$gt", "3"));
        }

        @Test
        @DisplayName("$empty on an element field matches owners where no element has it")
        void emptyElementField() throws Exception {
            assertMatches(field("lines.qty", "$empty", null), "c", "d", "e");
        }

        @Test
        @DisplayName("the whole collection of objects accepts presence tests only")
        void objectCollection() throws Exception {
            assertMatches(field("lines", "$empty", null), "c", "e");
            assertMatches(field("lines", "$ne", null), "a", "b", "d");
            // An element that is an object never equals a scalar: nothing matches, everything differs.
            assertMatches(field("lines", "$eq", "A1"));
            assertMatches(field("lines", "$ne", "A1"), "a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("a LIST value is an exact, ordered array match; an absent collection matches none")
        void wholeArray() throws Exception {
            assertMatches(field("tags", "$eq", List.of("red", "blue")), "a");
            assertMatches(field("tags", "$eq", List.of("blue", "red")));
            assertMatches(field("tags", "$eq", List.of("blue")), "b");
            assertMatches(field("tags", "$eq", List.of()));
            assertMatches(field("tags", "$ne", List.of("blue")), "a", "c", "d", "e");
        }

        @Test
        @DisplayName("a value of another type than the elements never matches an element")
        void mistypedElement() throws Exception {
            assertMatches(field("lines.qty", "$eq", "2"));
            assertMatches(field("tags", "$ne", 5), "a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("a map entry is addressed by key: 'stock.apple'")
        void mapEntry() throws Exception {
            assertMatches(field("stock.apple", "$gt", 5), "b");
            assertMatches(field("stock.pear", "$empty", null), "a", "c", "d", "e");
            assertMatches(field("stock.apple", "$eq", null), "c", "d", "e");
            assertMatches(field("stock.apple", "$ne", 3), "b", "c", "d", "e");
        }
    }

    @Nested
    @DisplayName("embedded POJOs and JSONB documents")
    class Documents {

        @Test
        @DisplayName("$empty on an embedded POJO matches rows where all its columns are NULL")
        void pojoEmpty() throws Exception {
            assertMatches(field("address", "$empty", null), "c");
            assertMatches(field("address", "$ne", null), "a", "b", "d", "e");
        }

        @Test
        @DisplayName("an embedded POJO never equals a scalar value")
        void pojoValue() throws Exception {
            assertMatches(field("address", "$eq", "Paris"));
            assertMatches(field("address", "$ne", "Paris"), "a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("a path into a JSONB column compares as text")
        void jsonText() throws Exception {
            assertMatches(field("node.label", "$eq", "root"), "a");
            assertMatches(field("node.label", "$regex", "^l"), "b");
            assertMatches(field("node.label", "$empty", null), "d", "e");
        }

        @Test
        @DisplayName("a path into a JSONB column compares NUMERICALLY with a number (10 > 9), skipping non-numbers")
        void jsonNumber() throws Exception {
            assertMatches(field("node.weight", "$gt", 9), "a");
            assertMatches(field("node.weight", "$lt", 10), "b");
            assertMatches(listed("node.weight", "$in", 9, 10), "a", "b");
        }
    }

    @Nested
    @DisplayName("logical operators")
    class Logical {

        @Test
        @DisplayName("nested $and / $or")
        void nested() throws Exception {
            assertMatches(logical("$and",
                    logical("$or", field("name", "$eq", "Alice"), field("total", "$eq", 42)),
                    field("tags", "$eq", "blue")), "a", "b");
            assertMatches(logical("$or",
                    logical("$and", field("status", "$eq", "ACTIVE"), field("total", "$lt", 7)),
                    field("name", "$eq", "Carol")), "c", "d");
        }

        @Test
        @DisplayName("$nor keeps rows whose fields are NULL — as Mongo keeps documents missing them")
        void norKeepsNull() throws Exception {
            // Without coercion to two-valued logic, c (total NULL, status NULL) evaluates NOT (NULL OR NULL)
            // = NULL and is dropped; MongoDB keeps it.
            assertMatches(logical("$nor", field("total", "$gt", 20), field("status", "$eq", "ACTIVE")), "c");
        }

        @Test
        @DisplayName("fewer than 2 sub-filters is refused with MongoFilterConverter's message")
        void tooFew() {
            ApiException e = assertThrows(ApiException.class,
                    () -> match(logical("$and", field("name", "$eq", "a"))));
            assertEquals("Logical operator $and requires at least 2 sub-filters", e.getMessage());
        }

        @Test
        @DisplayName("unknown operators are refused with MongoFilterConverter's messages")
        void unknownOperators() {
            assertEquals("Unsupported filter operator: $xor", assertThrows(ApiException.class,
                    () -> match(logical("$xor", field("name", "$eq", "a"), field("name", "$eq", "b")))).getMessage());
            assertEquals("Unsupported comparison operator: $near", assertThrows(ApiException.class,
                    () -> match(field("name", "$near", "a"))).getMessage());
            assertEquals("$in operator requires at least 1 value", assertThrows(ApiException.class,
                    () -> match(listed("name", "$in"))).getMessage());
        }

        @Test
        @DisplayName("a $field without exactly one comparison is refused")
        void fieldArity() {
            IFilter two = new PgQueryFixture.TestFilter("$field", "name", List.of(
                    new PgQueryFixture.TestFilter("$eq", "a", List.of()),
                    new PgQueryFixture.TestFilter("$eq", "b", List.of())));
            assertEquals("$field filter requires exactly 1 comparison sub-filter",
                    assertThrows(ApiException.class, () -> match(two)).getMessage());
        }

        @Test
        @DisplayName("no filter, and count(null), match every row")
        void noFilter() throws Exception {
            PgQuery q = PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
            assertEquals(5, PgQueryFixture.ids(db, q).size(), "no filter must match all rows");
            assertEquals(5, PgQueryFixture.ids(db, PgQueryFixture.builder().count(null)).size(),
                    "count(null) must match all rows");
            assertEquals(2, PgQueryFixture.ids(db, PgQueryFixture.builder().count(field("tags", "$eq", "blue")))
                    .size(), "count must carry the filter");
        }
    }

    @Nested
    @DisplayName("$text")
    class Text {

        @Test
        @DisplayName("searches every string of the entity, ignoring the named field")
        void search() throws Exception {
            assertMatches(field("whatever", "$text", "alice"), "a");
            assertMatches(field("name", "$text", "Paris"), "a", "d");
            assertMatches(field("name", "$text", "green"), "d");
            assertMatches(field("name", "$text", "heavy"), "c");
        }

        @Test
        @DisplayName("words are ORed, a -word excludes, and a stop word alone matches nothing")
        void words() throws Exception {
            assertMatches(field("name", "$text", "alice lyon"), "a", "b");
            assertMatches(field("name", "$text", "paris -green"), "a");
            assertMatches(field("name", "$text", "the"));
        }

        @Test
        @DisplayName("MongoDB's restrictions are refused: under $or, under $nor, twice")
        void restrictions() {
            assertThrows(ApiException.class, () -> match(logical("$or", field("x", "$text", "alice"),
                    field("name", "$eq", "Bob"))));
            assertThrows(ApiException.class, () -> match(logical("$nor", field("x", "$text", "alice"),
                    field("name", "$eq", "Bob"))));
            assertThrows(ApiException.class, () -> match(logical("$and", field("x", "$text", "alice"),
                    field("x", "$text", "bob"))));
        }
    }

    @Nested
    @DisplayName("safety")
    class Safety {

        @Test
        @DisplayName("an unknown field is absent from every row, as on MongoDB: $eq x none, $eq null all")
        void unknownField() throws Exception {
            assertMatches(field("nope", "$eq", "x"));
            assertMatches(field("nope", "$eq", null), "a", "b", "c", "d", "e");
            assertMatches(field("address.street", "$ne", "x"), "a", "b", "c", "d", "e");
        }

        @Test
        @DisplayName("SQL in a FIELD NAME never reaches SQL: it is an unknown field, hence absent")
        void injectionAsFieldName() throws Exception {
            String hostile = "name\" = \"name\" OR 1=1 --";
            assertMatches(field(hostile, "$eq", "x"));
            assertFalse(where(field(hostile, "$eq", "x")).contains("OR 1=1"), "the field name leaked into SQL");
            assertMatches(field("node') OR 1=1 --", "$eq", "x"));
        }

        @Test
        @DisplayName("SQL in a VALUE is matched literally and has no effect")
        void injectionAsValue() throws Exception {
            assertMatches(field("name", "$eq", PgQueryFixture.INJECTED_NAME), "e");
            assertMatches(field("name", "$eq", "x' OR '1'='1"));
            assertMatches(field("node.label' OR '1'='1", "$eq", "root"));
            try (Connection c = db.getConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT count(*) FROM \"items\"")) {
                assertTrue(r.next() && r.getInt(1) == 5, "the table must be intact");
            }
        }

        @Test
        @DisplayName("every value is a bind parameter: the SQL text never contains it")
        void valuesAreBound() throws Exception {
            PgQuery q = PgQueryFixture.builder().count(field("name", "$eq", "Needle"));
            assertFalse(q.where().contains("Needle"), () -> "value leaked into SQL: " + q.where());
            assertEquals(List.of("Needle"), q.params(), "the value must travel as a parameter");
        }
    }

    @Nested
    @DisplayName("geometry (SQL text only: the embedded engine has no PostGIS)")
    class Geometry {

        private PgQueryBuilder places() {
            return new PgQueryBuilder(PgQueryFixture.places(), IClass.getClass(PgQueryFixture.Place.class));
        }

        private Polygon square() {
            return new Polygon(List.of(new LngLatAlt(0, 0), new LngLatAlt(0, 1), new LngLatAlt(1, 1),
                    new LngLatAlt(1, 0), new LngLatAlt(0, 0)));
        }

        @Test
        @DisplayName("$geoWithin and $geoWithinSphere become ST_Within with the GeoJSON bound")
        void geoWithin() throws Exception {
            for (String op : List.of("$geoWithin", "$geoWithinSphere")) {
                PgQuery q = places().count(field("location", op, square()));
                assertEquals("ST_Within(t.\"location\", ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))", q.where(), op);
                assertTrue(q.params().get(0).toString().contains("\"Polygon\""),
                        () -> "the geometry must be bound as GeoJSON: " + q.params());
            }
        }

        @Test
        @DisplayName("$geoWithin on a non-geometry field, or without a geometry, is refused")
        void geoRefusals() {
            assertThrows(ApiException.class, () -> places().count(field("name", "$geoWithin", square())));
            assertEquals("$geoWithin filter on field 'location' requires a GeoJSON geometry value",
                    assertThrows(ApiException.class, () -> places().count(field("location", "$geoWithin", null)))
                            .getMessage());
        }
    }

    @Nested
    @DisplayName("sort and pagination")
    class Paging {

        private List<String> run(Optional<Pageable> page, Sort sort) throws Exception {
            PgQuery q = PgQueryFixture.builder().build(page.map(p -> (IPageable) p), Optional.empty(),
                    Optional.ofNullable(sort), Optional.empty());
            return PgQueryFixture.ids(db, q);
        }

        @Test
        @DisplayName("ascending puts NULL first, descending puts NULL last — Mongo's order")
        void nullsOrder() throws Exception {
            assertEquals("c", run(Optional.empty(), new Sort("total", SortDirection.asc)).get(0),
                    "NULL must sort first ascending");
            assertEquals(List.of("b", "a", "e", "d", "c"), run(Optional.empty(), new Sort("total", SortDirection.desc)),
                    "NULL must sort last descending");
        }

        @Test
        @DisplayName("pages over a sort with ties are disjoint and cover every row (id tiebreaker)")
        void stablePages() throws Exception {
            Sort byStatus = new Sort("status", SortDirection.asc);
            List<String> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                all.addAll(run(Optional.of(new Pageable(page, 2)), byStatus));
            }
            assertEquals(List.of("c", "a", "d", "e", "b"), all, "pages must follow status then id, without overlap");
            PgQuery q = PgQueryFixture.builder().build(Optional.of(new Pageable(2, 2)), Optional.empty(),
                    Optional.of(byStatus), Optional.empty());
            assertEquals("ORDER BY t.\"status\" COLLATE \"C\" ASC NULLS FIRST, t.\"uuid\" COLLATE \"C\" ASC",
                    q.orderBy());
            assertEquals(2, q.limit(), "limit = page size");
            assertEquals(4L, q.offset(), "offset = page index * page size");
        }

        @Test
        @DisplayName("a page without a sort is ordered by id; no page and no sort is unordered")
        void defaultOrder() throws Exception {
            assertEquals(List.of("c", "d"), run(Optional.of(new Pageable(1, 2)), null));
            assertEquals("", PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty()).orderBy());
        }

        @Test
        @DisplayName("a page size of 0 means no limit, as in MongoDB; a negative page is refused")
        void pageSizes() throws Exception {
            PgQuery q = PgQueryFixture.builder().build(Optional.of(new Pageable(3, 0)), Optional.empty(),
                    Optional.empty(), Optional.empty());
            assertNull(q.limit(), "size 0 must not limit");
            assertNull(q.offset(), "size 0 must not skip");
            assertThrows(ApiException.class, () -> PgQueryFixture.builder().build(Optional.of(new Pageable(-1, 2)),
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("sorting on a JSONB field, or inside one, is refused")
        void unsortable() {
            for (String name : List.of("node", "node.label")) {
                assertThrows(ApiException.class, () -> run(Optional.empty(), new Sort(name, SortDirection.asc)),
                        () -> "sorting on '" + name + "' must be refused");
            }
        }
    }

    @Nested
    @DisplayName("projection")
    class Projection {

        @Test
        @DisplayName("entity field names are translated through @FieldMappingRule, dotted tails kept")
        void translated() throws Exception {
            PgQuery q = PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of("identifier", "address.city", "name", " ", "name")));
            assertEquals(List.of("uuid", "address", "name"), q.projection());
        }

        @Test
        @DisplayName("no projection, or an empty one, loads everything")
        void none() throws Exception {
            assertNull(PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of())).projection());
            assertNull(PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty()).projection());
        }
    }
}
