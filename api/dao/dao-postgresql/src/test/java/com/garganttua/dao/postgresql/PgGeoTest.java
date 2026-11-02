package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

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
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The geographic operators and the geo index, proved against a real PostGIS — skipped when none is
 * given (see {@link PgPostgisDatabase}).
 */
@DisplayName("Geography on PostGIS")
class PgGeoTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Place {
        String uuid;
        String tenantId;
        String name;
        Point location;
        Polygon area;
    }

    private static final EntityIndexRule GEO_LOCATION = new EntityIndexRule(new ObjectAddress("location"), false,
            UnicityScope.tenant, IndexKind.geo, null);
    private static final EntityIndexRule GEO_AREA = new EntityIndexRule(new ObjectAddress("area"), false,
            UnicityScope.tenant, IndexKind.geo, null);

    private static final Point PARIS = new Point(2.3522, 48.8566);

    private DataSource db;
    private PgDao places;

    @BeforeEach
    void setUp() throws ApiException {
        db = PgPostgisDatabase.freshDatabase();
        places = new PgDao(db, "places", new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.STRICT);
        places.registerDomain(TestDomains.indexed(Place.class, "tenantId", List.of(GEO_LOCATION, GEO_AREA)));
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
        return PgQueryFixture.field("location", "$near", new GeoNear(PARIS, max, min));
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
        @DisplayName("$maxDistance and $minDistance, in metres")
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
            IFilter other = PgQueryFixture.field("name", "$eq", "x");
            assertThrows(ApiException.class, () -> names(PgQueryFixture.logical("$or", near(1.0, null), other)));
            assertThrows(ApiException.class, () -> names(PgQueryFixture.logical("$and", near(1.0, null),
                    near(2.0, null))));
            ApiException notGeo = assertThrows(ApiException.class,
                    () -> names(PgQueryFixture.field("name", "$near", new GeoNear(PARIS, 1.0, null))));
            assertTrue(notGeo.getMessage().contains("not a geometry"), notGeo.getMessage());
        }

        @Test
        @DisplayName("is served by the geo index")
        void indexed() throws Exception {
            assertTrue(plan(near(50_000.0, null)).contains("places__gg_location_tenant_geo_idx"));
        }
    }

    @Nested
    @DisplayName("$geoWithin")
    class Within {

        @Test
        @DisplayName("is evaluated on the sphere: a wide polygon's edge bulges poleward, as on MongoDB's 2dsphere")
        void spherical() throws ApiException {
            places.save(place("north-of-the-parallel", new Point(0, 50.5)));
            // Edges along the 50th parallel: straight in degrees, a great circle — which reaches ~53.4°N
            // at longitude 0 — on the sphere. The point at 50.5°N is outside the first, inside the second.
            Polygon band = polygon(-30, 40, 30, 40, 30, 50, -30, 50, -30, 40);
            List<String> found = names(PgQueryFixture.field("location", "$geoWithin", band));
            assertTrue(found.contains("north-of-the-parallel"), found::toString);
            assertEquals(found, names(PgQueryFixture.field("location", "$geoWithinSphere", band)));
        }

        @Test
        @DisplayName("is served by the geo index")
        void indexed() throws Exception {
            assertTrue(plan(PgQueryFixture.field("location", "$geoWithin", polygon(2, 48, 3, 48, 3, 49, 2, 49, 2, 48)))
                    .contains("places__gg_location_tenant_geo_idx"));
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
            assertEquals(List.of("ile-de-france"), names(PgQueryFixture.field("area", "$geoIntersects", PARIS)));
            assertEquals(Set.of("paris", "versailles"), Set.copyOf(names(PgQueryFixture.field("location",
                    "$geoIntersects", idf.area))));
        }
    }

    @Test
    @DisplayName("the geo index is a GiST on the geography, recognised as declared at the next start")
    void indexShape() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                        + "AND indexname = 'places__gg_location_tenant_geo_idx'")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).contains("USING gist (((location)::geography))"), rs.getString(1));
        }
        PgTable table = PgSchemaModel.of("places", IClass.getClass(Place.class), "uuid", Map.of());
        PgIndexSpec spec = PgIndexPlanner.plan(table, List.of(GEO_LOCATION), p -> p, "tenantId").get(0).spec()
                .orElseThrow();
        try (Connection c = db.getConnection()) {
            List<PgIndexSpec.PgStoredIndex> stored = PgIndexManager.stored(c, Set.of(spec.table()), Set.of(spec.name()))
                    .stream().filter(i -> i.name().equals(spec.name())).toList();
            assertTrue(spec.matches(stored.get(0)), () -> spec.describe() + " / " + stored.get(0).describe());
        }
    }

    private String plan(IFilter filter) throws SQLException, ApiException {
        PgTable table = PgSchemaModel.of("places", IClass.getClass(Place.class), "uuid", Map.of());
        PgQuery query = new PgQueryBuilder(table, null).build(Optional.empty(), Optional.of(filter),
                Optional.empty(), Optional.empty());
        StringBuilder plan = new StringBuilder();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET enable_seqscan = off");
            try (PreparedStatement statement = c.prepareStatement("EXPLAIN SELECT 1 FROM places AS " + PgQuery.ALIAS
                    + " WHERE " + query.where() + " " + query.orderBy())) {
                for (int i = 0; i < query.params().size(); i++) {
                    statement.setObject(i + 1, query.params().get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
            }
        }
        return plan.toString();
    }
}
