package com.garganttua.dao.sqlite;

import java.util.ArrayList;
import java.util.List;

import org.geojson.GeoJsonObject;
import org.geojson.GeometryCollection;
import org.geojson.LineString;
import org.geojson.LngLatAlt;
import org.geojson.MultiLineString;
import org.geojson.MultiPoint;
import org.geojson.MultiPolygon;
import org.geojson.Point;
import org.geojson.Polygon;

import com.garganttua.api.binding.geojson.GeoJsonCodec;

/**
 * The geographic predicates of the SQLite DAO, computed on the sphere as MongoDB computes them.
 *
 * <p>
 * SQLite has no geometry type: a GeoJSON field is stored as its text, and {@code $geoWithin}, {@code $geoIntersects}
 * and {@code $near} call functions of this class registered on the connection. Like a MongoDB {@code 2dsphere} index
 * — and PostgreSQL's {@code geography} — every edge is the great-circle arc between its two vertices, not the straight
 * segment of a flat map, and distances are measured on a sphere of radius {@value #EARTH_RADIUS} metres, MongoDB's.
 * A polygon is assumed smaller than a hemisphere, which MongoDB requires of the polygons it accepts by default.
 * </p>
 *
 * <ul>
 * <li>{@link #within}: every point of the field lies inside or on the boundary of the shape (a polygon or a
 * multipolygon, holes excluded) and no edge of the field crosses out of it — PostGIS's {@code ST_CoveredBy};</li>
 * <li>{@link #intersects}: the two geometries share at least one point;</li>
 * <li>{@link #distance}: from a point to the nearest point of the field, 0 inside a polygon.</li>
 * </ul>
 */
public final class SqliteGeo {

    /** The radius of the sphere MongoDB measures on, in metres. */
    public static final double EARTH_RADIUS = 6_378_100;

    private static final double EPSILON = 1e-12;
    private static final double LEFT_OF_HALF_TURN = Math.PI;

    private SqliteGeo() {
    }

    /** A unit vector of R³ — a point on the sphere. */
    private record Vec(double x, double y, double z) {

        static Vec of(LngLatAlt c) {
            double lng = Math.toRadians(c.getLongitude());
            double lat = Math.toRadians(c.getLatitude());
            return new Vec(Math.cos(lat) * Math.cos(lng), Math.cos(lat) * Math.sin(lng), Math.sin(lat));
        }

        double dot(Vec o) {
            return x * o.x + y * o.y + z * o.z;
        }

        Vec cross(Vec o) {
            return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
        }

        Vec minus(Vec o) {
            return new Vec(x - o.x, y - o.y, z - o.z);
        }

        Vec times(double k) {
            return new Vec(x * k, y * k, z * k);
        }

        double norm() {
            return Math.sqrt(dot(this));
        }

        Vec unit() {
            double n = norm();
            return n == 0 ? this : times(1 / n);
        }

        double angle(Vec o) {
            return Math.atan2(cross(o).norm(), dot(o));
        }
    }

    /** A geometry reduced to what the predicates need: its points, its edges, its polygons. */
    private record Parts(List<Vec> points, List<Vec[]> edges, List<List<List<Vec>>> polygons) {

        static Parts of(GeoJsonObject geometry) {
            Parts parts = new Parts(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            parts.add(geometry);
            return parts;
        }

        private void add(GeoJsonObject geometry) {
            switch (geometry) {
                case Point p -> points.add(Vec.of(p.getCoordinates()));
                // A LineString IS a MultiPoint in org.geojson: it must be matched first.
                case LineString l -> line(l.getCoordinates());
                case MultiPoint m -> m.getCoordinates().forEach(c -> points.add(Vec.of(c)));
                case MultiLineString m -> m.getCoordinates().forEach(this::line);
                case Polygon p -> polygon(p.getCoordinates());
                case MultiPolygon m -> m.getCoordinates().forEach(this::polygon);
                case GeometryCollection c -> c.getGeometries().forEach(this::add);
                default -> throw new IllegalArgumentException("not a GeoJSON geometry: "
                        + geometry.getClass().getSimpleName());
            }
        }

        private void line(List<LngLatAlt> coordinates) {
            List<Vec> vertices = coordinates.stream().map(Vec::of).toList();
            points.addAll(vertices);
            for (int i = 0; i + 1 < vertices.size(); i++) {
                edges.add(new Vec[] { vertices.get(i), vertices.get(i + 1) });
            }
        }

        private void polygon(List<List<LngLatAlt>> rings) {
            List<List<Vec>> polygon = new ArrayList<>();
            for (List<LngLatAlt> ring : rings) {
                line(ring);
                polygon.add(ring.stream().map(Vec::of).toList());
            }
            polygons.add(polygon);
        }
    }

    /**
     * {@code $geoWithin}: whether {@code field} lies wholly inside {@code shape}, boundary included.
     *
     * @param field the stored GeoJSON
     * @param shape the GeoJSON of the filter — a Polygon or a MultiPolygon
     * @return whether it is covered
     */
    public static boolean within(String field, String shape) {
        Parts f = Parts.of(GeoJsonCodec.fromJson(field));
        Parts s = Parts.of(GeoJsonCodec.fromJson(shape));
        if (s.polygons().isEmpty()) {
            return false;
        }
        for (Vec p : f.points()) {
            if (!covered(p, s)) {
                return false;
            }
        }
        for (Vec[] e : f.edges()) {
            for (Vec[] b : s.edges()) {
                if (crossesProperly(e[0], e[1], b[0], b[1])) {
                    return false;
                }
            }
            if (!covered(midpoint(e[0], e[1]), s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code $geoIntersects}: whether the two geometries share a point.
     *
     * @param field the stored GeoJSON
     * @param shape the GeoJSON of the filter
     * @return whether they intersect
     */
    public static boolean intersects(String field, String shape) {
        Parts a = Parts.of(GeoJsonCodec.fromJson(field));
        Parts b = Parts.of(GeoJsonCodec.fromJson(shape));
        for (Vec[] e : a.edges()) {
            for (Vec[] g : b.edges()) {
                if (arcsMeet(e[0], e[1], g[0], g[1])) {
                    return true;
                }
            }
        }
        return touches(a, b) || touches(b, a);
    }

    /** Whether a point of {@code a} lies on {@code b}: on one of its points, edges, or inside one of its polygons. */
    private static boolean touches(Parts a, Parts b) {
        for (Vec p : a.points()) {
            if (b.points().stream().anyMatch(q -> p.angle(q) < EPSILON)
                    || b.edges().stream().anyMatch(e -> onArc(p, e[0], e[1]))
                    || b.polygons().stream().anyMatch(poly -> inPolygon(p, poly))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The distance, in metres, from the point ({@code longitude}, {@code latitude}) to the nearest point of
     * {@code field} — 0 when the point lies inside one of its polygons.
     *
     * @param field     the stored GeoJSON
     * @param longitude the point's longitude, in degrees
     * @param latitude  the point's latitude, in degrees
     * @return the distance on MongoDB's sphere
     */
    public static double distance(String field, double longitude, double latitude) {
        Parts f = Parts.of(GeoJsonCodec.fromJson(field));
        Vec p = Vec.of(new LngLatAlt(longitude, latitude));
        if (f.polygons().stream().anyMatch(poly -> inPolygon(p, poly))) {
            return 0;
        }
        double best = Double.POSITIVE_INFINITY;
        for (Vec q : f.points()) {
            best = Math.min(best, p.angle(q));
        }
        for (Vec[] e : f.edges()) {
            best = Math.min(best, toArc(p, e[0], e[1]));
        }
        return best * EARTH_RADIUS;
    }

    private static boolean covered(Vec p, Parts shape) {
        return shape.polygons().stream().anyMatch(poly -> inPolygon(p, poly));
    }

    /** Inside the outer ring (or on it), and not strictly inside a hole. */
    private static boolean inPolygon(Vec p, List<List<Vec>> rings) {
        if (rings.isEmpty() || !inRing(p, rings.get(0))) {
            return false;
        }
        for (int i = 1; i < rings.size(); i++) {
            List<Vec> hole = rings.get(i);
            if (inRing(p, hole) && !onRing(p, hole)) {
                return false;
            }
        }
        return true;
    }

    /** By the winding of the ring around {@code p}: a full turn inside, none outside; the boundary counts as in. */
    private static boolean inRing(Vec p, List<Vec> ring) {
        if (onRing(p, ring)) {
            return true;
        }
        double winding = 0;
        for (int i = 0; i + 1 < ring.size(); i++) {
            Vec a = ring.get(i).minus(p.times(ring.get(i).dot(p)));
            Vec b = ring.get(i + 1).minus(p.times(ring.get(i + 1).dot(p)));
            winding += Math.atan2(p.dot(a.cross(b)), a.dot(b));
        }
        return Math.abs(winding) > LEFT_OF_HALF_TURN;
    }

    private static boolean onRing(Vec p, List<Vec> ring) {
        for (int i = 0; i + 1 < ring.size(); i++) {
            if (onArc(p, ring.get(i), ring.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code p} lies on the minor arc from {@code a} to {@code b}. */
    private static boolean onArc(Vec p, Vec a, Vec b) {
        return Math.abs(a.angle(p) + p.angle(b) - a.angle(b)) < EPSILON;
    }

    /** The angular distance from {@code p} to the minor arc from {@code a} to {@code b}. */
    private static double toArc(Vec p, Vec a, Vec b) {
        Vec n = a.cross(b);
        if (n.norm() < EPSILON) {
            return Math.min(p.angle(a), p.angle(b));
        }
        Vec u = n.unit();
        Vec foot = p.minus(u.times(p.dot(u))).unit();
        if (foot.norm() > 0 && onArc(foot, a, b)) {
            return p.angle(foot);
        }
        return Math.min(p.angle(a), p.angle(b));
    }

    private static Vec midpoint(Vec a, Vec b) {
        Vec m = new Vec(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
        return m.norm() < EPSILON ? a : m.unit();
    }

    /** Whether the arcs share a point — crossing, touching, or overlapping. */
    private static boolean arcsMeet(Vec a, Vec b, Vec c, Vec d) {
        if (onArc(a, c, d) || onArc(b, c, d) || onArc(c, a, b) || onArc(d, a, b)) {
            return true;
        }
        return crossesProperly(a, b, c, d);
    }

    /** Whether the arcs cross at a point interior to both. */
    private static boolean crossesProperly(Vec a, Vec b, Vec c, Vec d) {
        Vec n1 = a.cross(b);
        Vec n2 = c.cross(d);
        Vec t = n1.cross(n2);
        if (t.norm() < EPSILON) {
            return false;
        }
        Vec unit = t.unit();
        for (Vec x : new Vec[] { unit, unit.times(-1) }) {
            if (strictlyOnArc(x, a, b) && strictlyOnArc(x, c, d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean strictlyOnArc(Vec x, Vec a, Vec b) {
        return onArc(x, a, b) && x.angle(a) > EPSILON && x.angle(b) > EPSILON;
    }
}
