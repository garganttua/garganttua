package com.garganttua.dao.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.Point;
import org.geojson.Polygon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.dao.postgresql.TestDomains;
import com.garganttua.dao.postgresql.parity.ParityFilter;
import com.garganttua.dao.sql.SqlSchemaRegistry;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/**
 * The geographic operators on SQLite — computed on the sphere by {@link SqliteGeo}, as MongoDB's 2dsphere does.
 */
@DisplayName("SQLite — geography")
class SqliteGeoTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    /** A place. */
    public static class Place {
        String uuid;
        String tenantId;
        String name;
        Point location;
        Polygon area;
        LineString route;
    }

    private static final EntityIndexRule GEO_LOCATION = new EntityIndexRule(new ObjectAddress("location"), false,
            UnicityScope.tenant, IndexKind.geo, null);
    private static final Point PARIS = new Point(2.3522, 48.8566);

    private SqliteDao places;

    @BeforeEach
    void setUp() throws ApiException {
        places = new SqliteDao(SqliteTestDatabase.freshDatabase(), "places", new SqlSchemaRegistry(),
                SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
        places.registerDomain(TestDomains.indexed(Place.class, "tenantId", List.of(GEO_LOCATION)));
        places.save(place("paris", PARIS));
        places.save(place("versailles", new Point(2.1301, 48.8049)));
        places.save(place("lyon", new Point(4.8357, 45.7640)));
        places.save(place("marseille", new Point(5.3698, 43.2965)));
        places.save(place("nowhere", null));
    }

    private static Place place(String name, Point location) {
        Place place = new Place();
        place.uuid = name;
        place.tenantId = "T1";
        place.name = name;
        place.location = location;
        return place;
    }

    private List<String> names(IFilter filter) throws ApiException {
        return names(filter, null);
    }

    private List<String> names(IFilter filter, Sort sort) throws ApiException {
        return places.find(Optional.empty(), Optional.ofNullable(filter), Optional.ofNullable(sort)).stream()
                .map(p -> ((Place) p).name).toList();
    }

    private static IFilter near(Double max, Double min) {
        return ParityFilter.field("location", "$near", new GeoNear(PARIS, max, min));
    }

    private static Polygon polygon(double... lonLat) {
        LngLatAlt[] ring = new LngLatAlt[lonLat.length / 2];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new LngLatAlt(lonLat[2 * i], lonLat[2 * i + 1]);
        }
        return new Polygon(ring);
    }

    @Nested
    @DisplayName("$near")
    class Near {

        @Test
        @DisplayName("nearest first, the unset geometry left out")
        void ordered() throws ApiException {
            assertEquals(List.of("paris", "versailles", "lyon", "marseille"), names(near(null, null)));
        }

        @Test
        @DisplayName("$maxDistance and $minDistance, in metres on MongoDB's sphere")
        void ring() throws ApiException {
            assertEquals(List.of("paris", "versailles"), names(near(50_000.0, null)));
            assertEquals(List.of("versailles", "lyon"), names(near(400_000.0, 1_000.0)));
        }

        @Test
        @DisplayName("a sort the read names wins over the distance")
        void explicitSort() throws ApiException {
            assertEquals(List.of("lyon", "marseille", "paris", "versailles"),
                    names(near(null, null), new Sort("name", SortDirection.asc)));
        }

        @Test
        @DisplayName("refused where MongoDB refuses it: under $or, twice, on a field that is not a geometry")
        void refusals() {
            IFilter other = ParityFilter.field("name", "$eq", "x");
            assertThrows(ApiException.class, () -> names(ParityFilter.logical("$or", near(1.0, null), other)));
            assertThrows(ApiException.class, () -> names(ParityFilter.logical("$and", near(1.0, null),
                    near(2.0, null))));
            ApiException notGeo = assertThrows(ApiException.class,
                    () -> names(ParityFilter.field("name", "$near", new GeoNear(PARIS, 1.0, null))));
            assertTrue(notGeo.getMessage().contains("not a geometry"), notGeo.getMessage());
        }

        @Test
        @DisplayName("Paris–Lyon is ~392 km on MongoDB's sphere")
        void distance() {
            double metres = SqliteGeo.distance("{\"type\":\"Point\",\"coordinates\":[4.8357,45.764]}", 2.3522,
                    48.8566);
            assertEquals(391_900, metres, 1_500);
        }
    }

    @Nested
    @DisplayName("$geoWithin")
    class Within {

        @Test
        @DisplayName("is evaluated on the sphere: a wide polygon's edge bulges poleward, as on MongoDB's 2dsphere")
        void spherical() throws ApiException {
            places.save(place("north-of-the-parallel", new Point(0, 50.5)));
            Polygon band = polygon(-30, 40, 30, 40, 30, 50, -30, 50, -30, 40);
            List<String> found = names(ParityFilter.field("location", "$geoWithin", band));
            assertTrue(found.contains("north-of-the-parallel"), found::toString);
            assertEquals(found, names(ParityFilter.field("location", "$geoWithinSphere", band)));
        }

        @Test
        @DisplayName("a hole excludes what it contains; a point on the boundary is within")
        void holes() throws ApiException {
            Polygon idf = polygon(1.4, 48.1, 3.6, 48.1, 3.6, 49.3, 1.4, 49.3, 1.4, 48.1);
            assertEquals(Set.of("paris", "versailles"),
                    Set.copyOf(names(ParityFilter.field("location", "$geoWithin", idf))));
            idf.addInteriorRing(List.of(new LngLatAlt(2.3, 48.8), new LngLatAlt(2.4, 48.8), new LngLatAlt(2.4, 48.9),
                    new LngLatAlt(2.3, 48.9), new LngLatAlt(2.3, 48.8)));
            assertEquals(List.of("versailles"), names(ParityFilter.field("location", "$geoWithin", idf)));
            assertTrue(SqliteGeo.within("{\"type\":\"Point\",\"coordinates\":[0,0]}",
                    "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}"));
        }

        @Test
        @DisplayName("a line leaving the shape is not within, even with both ends inside a concave polygon")
        void lines() {
            String concave = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[4,0],[4,4],[2,1],[0,4],[0,0]]]}";
            assertTrue(SqliteGeo.within("{\"type\":\"LineString\",\"coordinates\":[[0.5,0.5],[3.5,0.5]]}", concave));
            assertTrue(!SqliteGeo.within("{\"type\":\"LineString\",\"coordinates\":[[0.5,3],[3.5,3]]}", concave));
        }
    }

    @Nested
    @DisplayName("$geoIntersects")
    class Intersects {

        @Test
        @DisplayName("finds the areas a point falls in, and the points inside a shape")
        void intersects() throws ApiException {
            Place idf = place("ile-de-france", null);
            idf.area = polygon(1.4, 48.1, 3.6, 48.1, 3.6, 49.3, 1.4, 49.3, 1.4, 48.1);
            places.save(idf);
            assertEquals(List.of("ile-de-france"), names(ParityFilter.field("area", "$geoIntersects", PARIS)));
            assertEquals(Set.of("paris", "versailles"), Set.copyOf(names(ParityFilter.field("location",
                    "$geoIntersects", idf.area))));
        }

        @Test
        @DisplayName("two crossing routes intersect; parallel ones do not")
        void routes() throws ApiException {
            Place a = place("a", null);
            a.route = new LineString(new LngLatAlt(0, 0), new LngLatAlt(2, 2));
            places.save(a);
            LineString crossing = new LineString(new LngLatAlt(0, 2), new LngLatAlt(2, 0));
            LineString apart = new LineString(new LngLatAlt(0, 1), new LngLatAlt(1, 2));
            assertEquals(List.of("a"), names(ParityFilter.field("route", "$geoIntersects", crossing)));
            assertEquals(List.of(), names(ParityFilter.field("route", "$geoIntersects", apart)));
        }
    }
}
