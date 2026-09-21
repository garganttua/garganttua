package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;

/**
 * Sort, pagination and projection of {@link PgQueryBuilder} ({@link PgSortClause}, {@link PgBsonOrder}):
 * the SQL shape where it is the point (no field name ever reaches the SQL, the uuid tiebreaker), and
 * the real order on {@link PgQueryFixture#seed} otherwise. A page of size 0 is used to read everything
 * with the uuid tiebreaker, so ties come back in a known order.
 */
@DisplayName("PgQueryBuilder — sort, pagination, projection")
class PgSortPageTest {

    private static final String UUID_ASC = "t.\"uuid\" COLLATE \"C\" ASC";

    private static DataSource db;

    @BeforeAll
    static void seed() throws Exception {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
        db = PgQueryFixture.seed();
    }

    private static PgQuery query(Pageable page, String field, SortDirection direction) throws ApiException {
        return PgQueryFixture.builder().build(Optional.ofNullable(page), Optional.empty(),
                Optional.ofNullable(field == null ? null : new Sort(field, direction)), Optional.empty());
    }

    private static List<String> all(String field, SortDirection direction) throws Exception {
        return PgQueryFixture.ids(db, query(new Pageable(0, 0), field, direction));
    }

    @Nested
    @DisplayName("sort keys")
    class Keys {

        @Test
        @DisplayName("an unknown field adds no key: every row ties, as in MongoDB; a page still orders by uuid")
        void unknownField() throws Exception {
            assertEquals("", query(null, "nope", SortDirection.asc).orderBy());
            assertEquals("ORDER BY " + UUID_ASC, query(new Pageable(1, 2), "nope", SortDirection.asc).orderBy());
            assertEquals("", query(null, "address.nope", SortDirection.desc).orderBy(),
                    "a missing leaf of a known POJO is unknown too");
        }

        @Test
        @DisplayName("a hostile field name never reaches the SQL")
        void hostileName() throws Exception {
            String orderBy = query(new Pageable(0, 2), "t.\"uuid\"; DROP TABLE items", SortDirection.asc).orderBy();
            assertEquals("ORDER BY " + UUID_ASC, orderBy);
            assertEquals(5, all("t.\"uuid\"; DROP TABLE items", SortDirection.asc).size());
        }

        @Test
        @DisplayName("'_id' is the uuid, and a sort on the uuid needs no tiebreaker")
        void mongoId() throws Exception {
            assertEquals("ORDER BY t.\"uuid\" COLLATE \"C\" DESC NULLS LAST",
                    query(new Pageable(0, 2), "_id", SortDirection.desc).orderBy());
            assertEquals(List.of("e", "d", "c", "b", "a"), all("_id", SortDirection.desc));
        }

        @Test
        @DisplayName("text is ordered by code points (COLLATE \"C\") and paged with the uuid last")
        void textKey() throws Exception {
            assertEquals("ORDER BY t.\"name\" COLLATE \"C\" ASC NULLS FIRST, " + UUID_ASC,
                    query(new Pageable(0, 2), "name", SortDirection.asc).orderBy());
        }

        @Test
        @DisplayName("a JSONB value, a path inside one or a missing name is refused")
        void refused() {
            for (String name : List.of("node", "node.label", " ")) {
                assertThrows(ApiException.class, () -> query(null, name, SortDirection.asc),
                        () -> "sorting on '" + name + "' must be refused");
            }
            assertThrows(ApiException.class, () -> PgQueryFixture.builder().build(Optional.empty(), Optional.empty(),
                    Optional.of(new Sort(null, SortDirection.asc)), Optional.empty()));
        }
    }

    @Nested
    @DisplayName("arrays (MongoDB: ascending by the smallest element, descending by the largest)")
    class Arrays {

        @Test
        @DisplayName("a collection of scalars: a null element is the smallest of all")
        void scalars() throws Exception {
            // a [red, blue], b [blue], c none, d [green], e [null]
            assertEquals(List.of("c", "e", "a", "b", "d"), all("tags", SortDirection.asc));
            assertEquals(List.of("a", "d", "b", "c", "e"), all("tags", SortDirection.desc));
        }

        @Test
        @DisplayName("a map entry is a plain path for MongoDB: its value, missing where the key is absent")
        void mapEntry() throws Exception {
            // stock.apple: a 3, b 7, c / d / e without the key (missing: first ascending, last descending)
            assertEquals(List.of("c", "d", "e", "a", "b"), all("stock.apple", SortDirection.asc));
            assertEquals(List.of("b", "a", "c", "d", "e"), all("stock.apple", SortDirection.desc));
            assertEquals(List.of("b", "a", "c", "d", "e"), all("stock.pear", SortDirection.desc));
            assertFalse(query(null, "stock.apple", SortDirection.asc).orderBy().contains("apple"),
                    "the key is filter text: it reaches the SQL hex-encoded, never as written");
        }

        @Test
        @DisplayName("a path inside POJO elements")
        void elementPath() throws Exception {
            // a qty [2, 5], b [1], c none, d [null], e none
            assertEquals(List.of("c", "d", "e", "b", "a"), all("lines.qty", SortDirection.asc));
            assertEquals(List.of("a", "b", "c", "d", "e"), all("lines.qty", SortDirection.desc));
        }
    }

    @Nested
    @DisplayName("pagination")
    class Paging {

        @Test
        @DisplayName("the offset is computed in long arithmetic: a page far beyond the data is empty")
        void longOffset() throws Exception {
            PgQuery q = query(new Pageable(Integer.MAX_VALUE, 2), "total", SortDirection.asc);
            assertEquals(2L * Integer.MAX_VALUE, q.offset());
            assertTrue(PgQueryFixture.ids(db, q).isEmpty());
        }

        @Test
        @DisplayName("a negative index or size is refused")
        void negative() {
            assertThrows(ApiException.class, () -> query(new Pageable(-1, 2), "total", SortDirection.asc));
            assertThrows(ApiException.class, () -> query(new Pageable(0, -2), "total", SortDirection.asc));
        }

        @Test
        @DisplayName("consecutive pages over ties are disjoint and complete")
        void complementary() throws Exception {
            StringBuilder seen = new StringBuilder();
            for (int page = 0; page < 3; page++) {
                IPageable p = new Pageable(page, 2);
                PgQuery q = PgQueryFixture.builder().build(Optional.of(p), Optional.empty(),
                        Optional.of(new Sort("tags", SortDirection.asc)), Optional.empty());
                PgQueryFixture.ids(db, q).forEach(seen::append);
            }
            assertEquals("ceabd", seen.toString());
        }
    }

    @Nested
    @DisplayName("projection")
    class Projection {

        @Test
        @DisplayName("a dotted path projects its head field, as MongoDao.applyProjection does")
        void heads() throws Exception {
            PgQuery q = PgQueryFixture.builder().build(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of("address.city", "address.nope", "identifier", "nope")));
            assertEquals(List.of("address", "uuid", "nope"), q.projection());
            assertFalse(q.projection().contains("address.city"));
        }
    }
}
