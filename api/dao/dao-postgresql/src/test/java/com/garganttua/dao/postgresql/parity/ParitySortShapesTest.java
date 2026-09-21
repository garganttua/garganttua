package com.garganttua.dao.postgresql.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Parity of sorts on SHAPED values — arrays with empty / NaN elements, whole embedded POJOs
 * holding nested POJOs, collections and mixed types, collections of POJOs, paths inside their
 * elements, maps — where PostgreSQL has to rebuild MongoDB's BSON order instead of reading a column.
 *
 * <p>
 * Every data set has DISTINCT sort keys: MongoDB returns ties in natural order and PostgreSQL in no
 * particular one, so a tie would test nothing but luck. Only the uuid order is compared.
 * </p>
 */
@DisplayName("Parity MongoDB / PostgreSQL — sort on arrays, sub-documents and maps")
class ParitySortShapesTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Geo {
        Double lat;
        String label;

        public Geo() {
        }

        Geo(Double lat, String label) {
            this.lat = lat;
            this.label = label;
        }
    }

    public static class Spot {
        String city;
        Integer n;
        Geo geo;
        List<Integer> codes;

        public Spot() {
        }
    }

    public static class Line {
        String sku;
        Integer qty;

        public Line() {
        }

        Line(String sku, Integer qty) {
            this.sku = sku;
            this.qty = qty;
        }
    }

    public static class Holder {
        String uuid;
        List<Double> ds;
        Spot spot;
        List<Line> lines;
        Map<String, Integer> counts;
    }

    private static final String HOLDERS = "holders";

    private static Holder holder(String uuid) {
        Holder h = new Holder();
        h.uuid = uuid;
        return h;
    }

    private static ParityHarness harness(Holder... rows) {
        ParityHarness h = ParityHarness.of(Domain.of(HOLDERS, Holder.class));
        h.save(HOLDERS, (Object[]) rows);
        return h;
    }

    private static void assertOrder(String what, ParityHarness h, String field, SortDirection direction) {
        Outcome o = h.find(HOLDERS, Optional.empty(), Optional.empty(), Optional.of(ParityFilter.sort(field, direction)),
                Optional.empty());
        ParityHarness.assertSame(what + " " + direction, uuids(o), true);
    }

    @SuppressWarnings("unchecked")
    private static Outcome uuids(Outcome o) {
        return new Outcome(o.mongo() == null ? null : ids((List<Object>) o.mongo()),
                o.pg() == null ? null : ids((List<Object>) o.pg()), o.mongoError(), o.pgError());
    }

    private static List<String> ids(List<Object> dtos) {
        List<String> out = new ArrayList<>();
        dtos.forEach(d -> out.add(((Holder) d).uuid));
        return out;
    }

    @Nested
    @DisplayName("an array of doubles")
    class Doubles {

        private ParityHarness data() {
            Holder empty = holder("empty");
            empty.ds = new ArrayList<>();
            Holder nan = holder("nan");
            nan.ds = List.of(Double.NaN, 100.0);
            Holder five = holder("five");
            five.ds = List.of(5.0);
            Holder spread = holder("spread");
            spread.ds = List.of(-1.0, 7.0);
            Holder mid = holder("mid");
            mid.ds = List.of(2.5, 3.0);
            return harness(empty, holder("missing"), nan, five, spread, mid);
        }

        @Test
        @DisplayName("ascending: empty array, then missing, then by smallest element — NaN the smallest")
        void ascending() {
            assertOrder("ds", data(), "ds", SortDirection.asc);
        }

        @Test
        @DisplayName("descending: by largest element, then missing, then the empty array")
        void descending() {
            assertOrder("ds", data(), "ds", SortDirection.desc);
        }
    }

    @Nested
    @DisplayName("a whole embedded POJO")
    class Pojo {

        private Spot spot(String city, Integer n, Geo geo, List<Integer> codes) {
            Spot s = new Spot();
            s.city = city;
            s.n = n;
            s.geo = geo;
            s.codes = codes;
            return s;
        }

        private ParityHarness data() {
            Holder[] rows = {
                    holder("missing"), holder("empty"), holder("n5"), holder("cityA"), holder("cityA_n1"),
                    holder("cityA_n2"), holder("cityB"), holder("geoLat"), holder("geoLabel"), holder("geoEmpty"),
                    holder("codes12"), holder("codes1"), holder("codesEmpty"), holder("cityA_geo") };
            rows[1].spot = spot(null, null, null, null);
            rows[2].spot = spot(null, 5, null, null);
            rows[3].spot = spot("a", null, null, null);
            rows[4].spot = spot("a", 1, null, null);
            rows[5].spot = spot("a", 2, null, null);
            rows[6].spot = spot("b", null, null, null);
            rows[7].spot = spot(null, null, new Geo(1.5, null), null);
            rows[8].spot = spot(null, null, new Geo(null, "x"), null);
            rows[9].spot = spot(null, null, new Geo(), null);
            rows[10].spot = spot(null, null, null, List.of(1, 2));
            rows[11].spot = spot(null, null, null, List.of(1));
            rows[12].spot = spot(null, null, null, new ArrayList<>());
            rows[13].spot = spot("a", null, new Geo(0.0, null), null);
            return harness(rows);
        }

        @Test
        @DisplayName("ascending: field by field — type, then name, then value; nested POJO and list included")
        void ascending() {
            assertOrder("spot", data(), "spot", SortDirection.asc);
        }

        @Test
        @DisplayName("descending")
        void descending() {
            assertOrder("spot", data(), "spot", SortDirection.desc);
        }
    }

    @Nested
    @DisplayName("a collection of POJOs")
    class Lines {

        private ParityHarness data() {
            Holder empty = holder("empty");
            empty.lines = new ArrayList<>();
            // No null element: the MongoDB writer cannot store one (NullPointerException in toStorable).
            Holder withZ = holder("withZ");
            withZ.lines = List.of(new Line("z", 9));
            Holder blank = holder("blank");
            blank.lines = List.of(new Line());
            Holder a1 = holder("a1");
            a1.lines = List.of(new Line("a", 1));
            Holder a2b = holder("a2b");
            a2b.lines = List.of(new Line("b", null), new Line("a", 2));
            Holder q = holder("q");
            q.lines = List.of(new Line(null, 4));
            return harness(empty, withZ, blank, a1, a2b, q);
        }

        @Test
        @DisplayName("whole collection ascending: by the smallest element document")
        void wholeAscending() {
            assertOrder("lines", data(), "lines", SortDirection.asc);
        }

        @Test
        @DisplayName("whole collection descending: by the largest element document")
        void wholeDescending() {
            assertOrder("lines", data(), "lines", SortDirection.desc);
        }

        private ParityHarness quantities() {
            Holder a = holder("a");
            a.lines = List.of(new Line("x", 5), new Line("y", 9));
            Holder b = holder("b");
            b.lines = List.of(new Line("x", 7));
            Holder c = holder("c");
            c.lines = List.of(new Line("x", -3), new Line("y", 12));
            Holder d = holder("d");
            d.lines = List.of(new Line("x", 6), new Line("y", 8));
            return harness(a, b, c, d);
        }

        @Test
        @DisplayName("a path inside the elements ascending: smallest quantity")
        void pathAscending() {
            assertOrder("lines.qty", quantities(), "lines.qty", SortDirection.asc);
        }

        @Test
        @DisplayName("a path inside the elements descending: largest quantity")
        void pathDescending() {
            assertOrder("lines.qty", quantities(), "lines.qty", SortDirection.desc);
        }
    }

    @Nested
    @DisplayName("a map (single entries: MongoDB stores entries in iteration order)")
    class Maps {

        private ParityHarness data() {
            Holder empty = holder("empty");
            empty.counts = Map.of();
            Holder k1 = holder("k1");
            k1.counts = Map.of("k", 1);
            Holder a9 = holder("a9");
            a9.counts = Map.of("a", 9);
            Holder k0 = holder("k0");
            k0.counts = Map.of("k", 0);
            // No null value: the MongoDB writer cannot store one (NullPointerException in toStorable).
            return harness(holder("missing"), empty, k1, a9, k0);
        }

        @Test
        @DisplayName("ascending")
        void ascending() {
            assertOrder("counts", data(), "counts", SortDirection.asc);
        }

        @Test
        @DisplayName("descending")
        void descending() {
            assertOrder("counts", data(), "counts", SortDirection.desc);
        }
    }
}
