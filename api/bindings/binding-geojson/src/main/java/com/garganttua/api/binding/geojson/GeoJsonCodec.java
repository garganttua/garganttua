package com.garganttua.api.binding.geojson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geojson.GeoJsonObject;
import org.geojson.Geometry;
import org.geojson.GeometryCollection;
import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.MultiLineString;
import org.geojson.MultiPoint;
import org.geojson.MultiPolygon;
import org.geojson.Point;
import org.geojson.Polygon;

/**
 * An {@code org.geojson} geometry as GeoJSON, and back — without any JSON library.
 *
 * <p>
 * A geometry is a {@code type} and nested arrays of numbers (or, for a {@code GeometryCollection},
 * a list of geometries): it needs no reflection and no generic mapper. The DAOs store geometries
 * through this codec — MongoDB as a sub-document a {@code 2dsphere} index understands, PostgreSQL as
 * the text {@code ST_GeomFromGeoJSON} reads and {@code ST_AsGeoJSON} writes — so both write one
 * geometry the same way, and neither needs Jackson, nor reflection metadata in a native image, to do
 * it.
 * </p>
 *
 * <ul>
 * <li>The seven geometry types of RFC 7946 are supported. A {@code Feature} or
 * {@code FeatureCollection} is refused: its {@code properties} are arbitrary values, not a geometry,
 * and no geographic index or predicate applies to them.</li>
 * <li>{@code crs} and {@code bbox} are not carried: RFC 7946 removed {@code crs}, and the stores
 * recompute a bounding box themselves.</li>
 * <li>A position keeps its altitude, and any further element, when it has them.</li>
 * </ul>
 */
public final class GeoJsonCodec {

    /** The {@code type} of each supported geometry, as GeoJSON names it. */
    public static final Set<String> GEOMETRY_TYPES = Set.of("Point", "MultiPoint", "LineString",
            "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection");

    private static final String TYPE = "type";
    private static final String COORDINATES = "coordinates";
    private static final String GEOMETRIES = "geometries";
    private static final String COLLECTION = "GeometryCollection";
    private static final int LNG_LAT = 2;

    private GeoJsonCodec() {
    }

    /**
     * The GeoJSON object of a geometry, as nested maps, lists and {@code Double}s — insertion-ordered,
     * {@code type} first.
     *
     * @param geometry the geometry
     * @return its GeoJSON form
     * @throws IllegalArgumentException when it is not one of the seven geometries, or a coordinate is
     *                                  not finite
     */
    public static Map<String, Object> toMap(GeoJsonObject geometry) {
        if (geometry == null) {
            throw new IllegalArgumentException("A null geometry has no GeoJSON form");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TYPE, typeOf(geometry));
        if (geometry instanceof GeometryCollection collection) {
            List<Object> geometries = new ArrayList<>();
            for (GeoJsonObject member : collection.getGeometries()) {
                geometries.add(toMap(member));
            }
            map.put(GEOMETRIES, geometries);
        } else if (geometry instanceof Point point) {
            map.put(COORDINATES, position(point.getCoordinates()));
        } else if (geometry instanceof Geometry<?> shape) {
            map.put(COORDINATES, nested(shape.getCoordinates()));
        }
        return map;
    }

    /**
     * The geometry of a GeoJSON object given as nested maps and lists — what a MongoDB driver decodes,
     * or what {@link #fromJson} parses. Numbers may be of any {@link Number} type.
     *
     * @param map the GeoJSON object
     * @return the geometry
     * @throws IllegalArgumentException when the object is not a supported geometry, or is malformed
     */
    public static GeoJsonObject fromMap(Map<?, ?> map) {
        Object type = map == null ? null : map.get(TYPE);
        if (!(type instanceof String name) || !GEOMETRY_TYPES.contains(name)) {
            throw new IllegalArgumentException("Not a GeoJSON geometry: its \"type\" is " + type
                    + ", expected one of " + GEOMETRY_TYPES);
        }
        if (COLLECTION.equals(name)) {
            GeometryCollection collection = new GeometryCollection();
            for (Object member : list(map.get(GEOMETRIES), GEOMETRIES)) {
                collection.add(fromMap(object(member)));
            }
            return collection;
        }
        Object coordinates = map.get(COORDINATES);
        return switch (name) {
            case "Point" -> new Point(lngLatAlt(coordinates));
            case "MultiPoint" -> filled(new MultiPoint(), positions(coordinates));
            case "LineString" -> filled(new LineString(), positions(coordinates));
            case "MultiLineString" -> filled(new MultiLineString(), rings(coordinates));
            case "Polygon" -> filled(new Polygon(), rings(coordinates));
            default -> filled(new MultiPolygon(), polygons(coordinates));
        };
    }

    /**
     * Whether a decoded object IS a GeoJSON geometry — a map whose {@code type} names one — rather
     * than some other sub-document.
     *
     * @param value a decoded value
     * @return {@code true} for a GeoJSON geometry
     */
    public static boolean isGeometry(Object value) {
        return value instanceof Map<?, ?> map && map.get(TYPE) instanceof String type
                && GEOMETRY_TYPES.contains(type);
    }

    /**
     * The GeoJSON text of a geometry.
     *
     * @param geometry the geometry
     * @return compact GeoJSON
     * @throws IllegalArgumentException as {@link #toMap}
     */
    public static String toJson(GeoJsonObject geometry) {
        StringBuilder out = new StringBuilder();
        GeoJsonText.write(toMap(geometry), out);
        return out.toString();
    }

    /**
     * The geometry of a GeoJSON text.
     *
     * @param json the text
     * @return the geometry
     * @throws IllegalArgumentException when the text is not JSON, or not a supported geometry
     */
    public static GeoJsonObject fromJson(String json) {
        return fromMap(object(GeoJsonText.parse(json)));
    }

    private static String typeOf(GeoJsonObject geometry) {
        String name = geometry.getClass().getSimpleName();
        if (!GEOMETRY_TYPES.contains(name)) {
            throw new IllegalArgumentException("A " + name + " is not a geometry: only " + GEOMETRY_TYPES
                    + " can be stored in a geographic field (a Feature's properties are arbitrary values)");
        }
        return name;
    }

    private static Object nested(Object coordinates) {
        if (coordinates instanceof LngLatAlt position) {
            return position(position);
        }
        List<Object> out = new ArrayList<>();
        for (Object element : (List<?>) coordinates) {
            out.add(nested(element));
        }
        return out;
    }

    private static List<Double> position(LngLatAlt position) {
        if (position == null) {
            throw new IllegalArgumentException("A GeoJSON position cannot be null");
        }
        List<Double> out = new ArrayList<>();
        out.add(finite(position.getLongitude()));
        out.add(finite(position.getLatitude()));
        if (!Double.isNaN(position.getAltitude())) {
            out.add(finite(position.getAltitude()));
            for (double extra : position.getAdditionalElements()) {
                out.add(finite(extra));
            }
        }
        return out;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("A GeoJSON coordinate must be a finite number, got " + value);
        }
        return value;
    }

    private static LngLatAlt lngLatAlt(Object value) {
        List<?> numbers = list(value, COORDINATES);
        if (numbers.size() < LNG_LAT) {
            throw new IllegalArgumentException("A GeoJSON position has at least a longitude and a latitude, got "
                    + numbers);
        }
        double[] values = new double[numbers.size()];
        for (int i = 0; i < values.length; i++) {
            if (!(numbers.get(i) instanceof Number number)) {
                throw new IllegalArgumentException("A GeoJSON position holds numbers, got " + numbers);
            }
            values[i] = finite(number.doubleValue());
        }
        if (values.length == LNG_LAT) {
            return new LngLatAlt(values[0], values[1]);
        }
        double[] additional = new double[values.length - 3];
        System.arraycopy(values, 3, additional, 0, additional.length);
        return new LngLatAlt(values[0], values[1], values[2], additional);
    }

    private static List<LngLatAlt> positions(Object value) {
        List<LngLatAlt> out = new ArrayList<>();
        for (Object position : list(value, COORDINATES)) {
            out.add(lngLatAlt(position));
        }
        return out;
    }

    private static List<List<LngLatAlt>> rings(Object value) {
        List<List<LngLatAlt>> out = new ArrayList<>();
        for (Object ring : list(value, COORDINATES)) {
            out.add(positions(ring));
        }
        return out;
    }

    private static List<List<List<LngLatAlt>>> polygons(Object value) {
        List<List<List<LngLatAlt>>> out = new ArrayList<>();
        for (Object polygon : list(value, COORDINATES)) {
            out.add(rings(polygon));
        }
        return out;
    }

    private static <T, G extends Geometry<T>> G filled(G geometry, List<T> coordinates) {
        geometry.setCoordinates(coordinates);
        return geometry;
    }

    private static List<?> list(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("GeoJSON \"" + what + "\" must be an array, got " + value);
        }
        return list;
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("A GeoJSON geometry is an object, got " + value);
        }
        return map;
    }
}
