package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.geojson.LngLatAlt;
import org.geojson.Point;
import org.geojson.Polygon;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Geometries on a REAL MongoDB: stored as GeoJSON a {@code 2dsphere} index accepts, read back
 * unchanged, and queried by every geographic operator — what the PostgreSQL DAO proves on PostGIS.
 */
@DisplayName("MongoDao — geometries as GeoJSON, 2dsphere, $near / $geoWithin / $geoIntersects")
class MongoDaoGeoTest {

    public static class Place {
        private String uuid;
        private String name;
        private Point location;
        private Polygon area;
        private List<Point> stops;
    }

    private static final Point PARIS = new Point(2.3522, 48.8566);

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private MongoDao places;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private MongoDao dao() {
        IDtoDefinition<Object> dto = mock(IDtoDefinition.class);
        when(dto.dtoClass()).thenReturn((IClass) IClass.getClass(Place.class));
        when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
        when(dto.compositions()).thenReturn(List.of());
        IEntityDefinition<Object> entity = mock(IEntityDefinition.class);
        when(entity.indexes()).thenReturn(List.of(
                new EntityIndexRule(new ObjectAddress("location"), false, UnicityScope.system, IndexKind.geo, null),
                new EntityIndexRule(new ObjectAddress("area"), false, UnicityScope.system, IndexKind.geo, null)));
        IDomainDefinition domain = mock(IDomainDefinition.class);
        when(domain.domainName()).thenReturn("places");
        when(domain.dtoDefinitions()).thenReturn(List.of(dto));
        when(domain.entityDefinition()).thenReturn(entity);
        MongoDao dao = new MongoDao(this.database, "places", MongoIndexMode.STRICT);
        dao.registerDomain(domain);
        return dao;
    }

    private static Place place(String name, Point location) {
        Place place = new Place();
        place.uuid = name;
        place.name = name;
        place.location = location;
        return place;
    }

    @BeforeEach
    void setUp() throws ApiException {
        this.database = MongoTestServer.freshDatabase();
        this.collection = this.database.getCollection("places");
        this.places = dao();
        this.places.save(place("paris", PARIS));
        this.places.save(place("versailles", new Point(2.1301, 48.8049)));
        this.places.save(place("lyon", new Point(4.8357, 45.7640)));
        this.places.save(place("marseille", new Point(5.3698, 43.2965)));
        this.places.save(place("nowhere", null));
    }

    private static IFilter field(String field, String op, Object value) {
        IFilter comparison = mock(IFilter.class);
        when(comparison.getName()).thenReturn(op);
        when(comparison.getValue()).thenReturn(value);
        IFilter filter = mock(IFilter.class);
        when(filter.getName()).thenReturn("$field");
        when(filter.getValue()).thenReturn(field);
        when(filter.getFilters()).thenReturn(List.of(comparison));
        return filter;
    }

    private List<String> names(IFilter filter) throws ApiException {
        List<String> names = new ArrayList<>();
        for (Object found : this.places.find(Optional.empty(), Optional.ofNullable(filter), Optional.empty())) {
            names.add(((Place) found).name);
        }
        return names;
    }

    private static Polygon polygon(double... lonLat) {
        LngLatAlt[] ring = new LngLatAlt[lonLat.length / 2];
        for (int i = 0; i < ring.length; i++) {
            ring[i] = new LngLatAlt(lonLat[2 * i], lonLat[2 * i + 1]);
        }
        return new Polygon(ring);
    }

    @Nested
    @DisplayName("storage")
    class Storage {

        @Test
        @DisplayName("a point is stored as GeoJSON, and the 2dsphere indexes are in place")
        void storedAsGeoJson() {
            Document stored = collection.find(new Document("_id", "paris")).first();
            assertEquals(new Document("type", "Point").append("coordinates", List.of(2.3522, 48.8566)),
                    stored.get("location"));
            List<String> indexes = new ArrayList<>();
            for (Document index : collection.listIndexes()) {
                indexes.add(String.valueOf(index.get("key")));
            }
            assertTrue(indexes.contains("Document{{location=2dsphere}}"), indexes::toString);
            assertTrue(indexes.contains("Document{{area=2dsphere}}"), indexes::toString);
        }

        @Test
        @DisplayName("a point, a polygon and a list of points read back unchanged")
        void roundTrip() throws ApiException {
            Place full = place("full", PARIS);
            full.area = polygon(1.4, 48.1, 3.6, 48.1, 3.6, 49.3, 1.4, 49.3, 1.4, 48.1);
            full.stops = List.of(new Point(1, 2), new Point(3, 4));
            places.save(full);
            Place read = (Place) places.find(Optional.empty(), Optional.of(field("uuid", "$eq", "full")),
                    Optional.empty()).get(0);
            assertEquals(PARIS, read.location);
            assertEquals(full.area, read.area);
            assertEquals(full.stops, read.stops);
        }

        @Test
        @DisplayName("a document stored field by field before ALPHA30 still reads")
        void legacyShape() throws ApiException {
            Document legacy = new Document("_id", "old").append("uuid", "old").append("name", "old")
                    .append("location", new Document("coordinates",
                            new Document("longitude", 2.0).append("latitude", 48.0)));
            database.getCollection("legacy").insertOne(legacy);
            MongoDao reader = new MongoDao(database, "legacy", MongoIndexMode.NONE);
            reader.registerDomain(definitionWithoutIndexes());
            Place read = (Place) reader.find(Optional.empty(), Optional.empty(), Optional.empty()).get(0);
            assertEquals(2.0, read.location.getCoordinates().getLongitude());
            assertEquals(48.0, read.location.getCoordinates().getLatitude());
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private IDomainDefinition definitionWithoutIndexes() {
            IDtoDefinition<Object> dto = mock(IDtoDefinition.class);
            when(dto.dtoClass()).thenReturn((IClass) IClass.getClass(Place.class));
            when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
            when(dto.compositions()).thenReturn(List.of());
            IDomainDefinition domain = mock(IDomainDefinition.class);
            when(domain.dtoDefinitions()).thenReturn(List.of(dto));
            return domain;
        }
    }

    @Nested
    @DisplayName("$near")
    class Near {

        private IFilter near(Double max, Double min) {
            return field("location", "$near", new GeoNear(PARIS, max, min));
        }

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
        @DisplayName("a paginated read counts what it reads — MongoDB refuses $near in countDocuments")
        void counted() throws ApiException {
            assertEquals(2, places.count(near(50_000.0, null)));
            assertEquals(2, places.count(near(400_000.0, 1_000.0)));
            assertEquals(4, places.count(near(null, null)));
            // Nearest first, page by page — by _id ("lyon" < "marseille" < "paris") the first page would be lyon.
            assertEquals(List.of("paris"), places.find(Optional.of(new Pageable(0, 1)),
                    Optional.of(near(null, null)), Optional.empty()).stream().map(p -> ((Place) p).name).toList());
            assertEquals(List.of("versailles"), places.find(Optional.of(new Pageable(1, 1)),
                    Optional.of(near(null, null)), Optional.empty()).stream().map(p -> ((Place) p).name).toList());
        }

        @Test
        @DisplayName("a sort the read names wins over the distance")
        void explicitSort() throws ApiException {
            List<String> sorted = new ArrayList<>();
            for (Object found : places.find(Optional.empty(), Optional.of(near(null, null)),
                    Optional.of(new Sort("name", SortDirection.asc)))) {
                sorted.add(((Place) found).name);
            }
            assertEquals(List.of("lyon", "marseille", "paris", "versailles"), sorted);
        }
    }

    @Nested
    @DisplayName("$geoWithin and $geoIntersects")
    class Shapes {

        @Test
        @DisplayName("$geoWithin finds the points inside a shape")
        void within() throws ApiException {
            assertEquals(Set.of("paris", "versailles"), Set.copyOf(names(field("location", "$geoWithin",
                    polygon(1.4, 48.1, 3.6, 48.1, 3.6, 49.3, 1.4, 49.3, 1.4, 48.1)))));
        }

        @Test
        @DisplayName("$geoIntersects finds the areas a point falls in")
        void intersects() throws ApiException {
            Place idf = place("ile-de-france", null);
            idf.area = polygon(1.4, 48.1, 3.6, 48.1, 3.6, 49.3, 1.4, 49.3, 1.4, 48.1);
            places.save(idf);
            assertEquals(List.of("ile-de-france"), names(field("area", "$geoIntersects", PARIS)));
        }
    }
}
