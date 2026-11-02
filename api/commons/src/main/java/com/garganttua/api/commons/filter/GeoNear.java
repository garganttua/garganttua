package com.garganttua.api.commons.filter;

import java.util.Objects;

/**
 * The value of a {@code $near} / {@code $nearSphere} filter: a point, and the ring of distances around
 * it the matching geometries must lie in — MongoDB's
 * <code>{$near: {$geometry: &lt;Point&gt;, $maxDistance: m, $minDistance: m}}</code>.
 *
 * <p>
 * Distances are in METRES, on the sphere, as MongoDB measures them against a {@code 2dsphere} index.
 * The point is the GeoJSON object the filter was given (an {@code org.geojson.Point}); it is typed
 * {@code Object} here because the contract layer carries no GeoJSON library — each DAO serialises it
 * as it serialises every other geometry.
 * </p>
 *
 * @param geometry    the GeoJSON point distances are measured from
 * @param maxDistance the greatest distance a match may lie at, or {@code null} for no bound
 * @param minDistance the smallest distance a match may lie at, or {@code null} for no bound
 */
public record GeoNear(Object geometry, Double maxDistance, Double minDistance) {

    /**
     * Refuses what no store could answer: no point, or a negative distance.
     */
    public GeoNear {
        Objects.requireNonNull(geometry, "$near requires a GeoJSON point ($geometry)");
        if (maxDistance != null && maxDistance < 0 || minDistance != null && minDistance < 0) {
            throw new IllegalArgumentException("$near distances must be zero or positive, got $maxDistance="
                    + maxDistance + ", $minDistance=" + minDistance);
        }
    }
}
