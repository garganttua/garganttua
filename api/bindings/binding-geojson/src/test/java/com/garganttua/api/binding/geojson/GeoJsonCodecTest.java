package com.garganttua.api.binding.geojson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.GeoJsonObject;
import org.geojson.GeometryCollection;
import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.MultiLineString;
import org.geojson.MultiPoint;
import org.geojson.MultiPolygon;
import org.geojson.Point;
import org.geojson.Polygon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GeoJsonCodec — geometries as GeoJSON, without a JSON library")
class GeoJsonCodecTest {

    private static Polygon square(double from, double to) {
        return new Polygon(new LngLatAlt(from, from), new LngLatAlt(to, from), new LngLatAlt(to, to),
                new LngLatAlt(from, to), new LngLatAlt(from, from));
    }

    private static List<GeoJsonObject> everyGeometry() {
        Polygon holed = square(0, 10);
        holed.addInteriorRing(new LngLatAlt(2, 2), new LngLatAlt(3, 2), new LngLatAlt(3, 3), new LngLatAlt(2, 2));
        MultiLineString lines = new MultiLineString(List.of(new LngLatAlt(0, 0), new LngLatAlt(1, 1)));
        lines.add(List.of(new LngLatAlt(5, 5), new LngLatAlt(6, 6)));
        MultiPolygon polygons = new MultiPolygon(square(0, 1));
        polygons.add(holed);
        GeometryCollection collection = new GeometryCollection();
        collection.add(new Point(1, 2)).add(new LineString(new LngLatAlt(0, 0), new LngLatAlt(1, 1)));
        return List.of(new Point(2.3522, 48.8566), new MultiPoint(new LngLatAlt(1, 2), new LngLatAlt(3, 4)),
                new LineString(new LngLatAlt(0, 0), new LngLatAlt(1, 1)), lines, holed, polygons, collection);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("every geometry comes back equal, through the map form and through the text")
        void everyGeometry() {
            for (GeoJsonObject geometry : GeoJsonCodecTest.everyGeometry()) {
                assertEquals(geometry, GeoJsonCodec.fromMap(GeoJsonCodec.toMap(geometry)),
                        geometry.getClass().getSimpleName());
                assertEquals(geometry, GeoJsonCodec.fromJson(GeoJsonCodec.toJson(geometry)),
                        geometry.getClass().getSimpleName());
            }
        }

        @Test
        @DisplayName("a point is the GeoJSON a 2dsphere index reads: type first, [longitude, latitude]")
        void pointShape() {
            assertEquals(Map.of("type", "Point", "coordinates", List.of(2.3522, 48.8566)),
                    GeoJsonCodec.toMap(new Point(2.3522, 48.8566)));
            assertEquals("{\"type\":\"Point\",\"coordinates\":[2.3522,48.8566]}",
                    GeoJsonCodec.toJson(new Point(2.3522, 48.8566)));
        }

        @Test
        @DisplayName("the altitude and further elements are kept when present")
        void altitude() {
            Point high = new Point(new LngLatAlt(1, 2, 300, 7));
            assertEquals(List.of(1.0, 2.0, 300.0, 7.0), GeoJsonCodec.toMap(high).get("coordinates"));
            assertEquals(high, GeoJsonCodec.fromJson(GeoJsonCodec.toJson(high)));
        }

        @Test
        @DisplayName("integer coordinates, as a store may decode them, read as numbers")
        void anyNumberType() {
            Point point = (Point) GeoJsonCodec.fromMap(Map.of("type", "Point", "coordinates", List.of(2, 48L)));
            assertEquals(new LngLatAlt(2, 48), point.getCoordinates());
        }
    }

    @Nested
    @DisplayName("what a store renders back")
    class Rendered {

        @Test
        @DisplayName("PostGIS's ST_AsGeoJSON with crs and bbox, blanks and exponents, reads")
        void postgis() {
            GeoJsonObject read = GeoJsonCodec.fromJson(" { \"type\" : \"Point\", \"crs\": {\"type\":\"name\","
                    + "\"properties\":{\"name\":\"EPSG:4326\"}}, \"bbox\":[1,2,1,2], \"coordinates\": [1.5E1, -2e-1] } ");
            assertEquals(new Point(15, -0.2), read);
        }

        @Test
        @DisplayName("isGeometry tells a GeoJSON sub-document from any other")
        void isGeometry() {
            assertTrue(GeoJsonCodec.isGeometry(Map.of("type", "Polygon", "coordinates", List.of())));
            assertFalse(GeoJsonCodec.isGeometry(Map.of("coordinates", Map.of("longitude", 1.0))));
            assertFalse(GeoJsonCodec.isGeometry(Map.of("type", "Feature")));
            assertFalse(GeoJsonCodec.isGeometry("Point"));
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a Feature or a FeatureCollection is not a geometry")
        void features() {
            IllegalArgumentException feature = assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.toMap(new Feature()));
            assertTrue(feature.getMessage().contains("Feature"), feature.getMessage());
            assertThrows(IllegalArgumentException.class, () -> GeoJsonCodec.toMap(new FeatureCollection()));
            assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.fromJson("{\"type\":\"Feature\",\"geometry\":null}"));
        }

        @Test
        @DisplayName("a coordinate that is not a finite number")
        void notFinite() {
            assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.toMap(new Point(Double.POSITIVE_INFINITY, 1)));
            assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.fromJson("{\"type\":\"Point\",\"coordinates\":[\"a\",1]}"));
            assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.fromJson("{\"type\":\"Point\",\"coordinates\":[1]}"));
        }

        @Test
        @DisplayName("text that is not JSON, named with its offset")
        void malformed() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.fromJson("{\"type\":\"Point\",\"coordinates\":[1,2"));
            assertTrue(e.getMessage().contains("offset"), e.getMessage());
            assertInstanceOf(IllegalArgumentException.class, assertThrows(IllegalArgumentException.class,
                    () -> GeoJsonCodec.fromJson("{} trailing")));
        }
    }
}
