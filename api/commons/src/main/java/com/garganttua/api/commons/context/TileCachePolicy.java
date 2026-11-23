package com.garganttua.api.commons.context;

import java.util.Objects;

/**
 * How a domain caches its map searches: those that bound a latitude field and a longitude field on both sides,
 * without page or sort — a viewport. The viewport is cut into tiles of a fixed grid (their size follows the
 * zoom, by powers of two, at most three per axis), each tile is cached under its cell and the rest of the query —
 * access filter included — and their union is trimmed to the bounds asked, exactly as the store compares them.
 * Panning or zooming reuses the tiles already read. Any write of the domain empties the tiles.
 *
 * @param cache     the cache holding the tiles
 * @param latitude  the entity's latitude field, a number
 * @param longitude the entity's longitude field, a number
 */
public record TileCachePolicy(CachePolicy cache, String latitude, String longitude) {

    public TileCachePolicy {
        Objects.requireNonNull(cache, "Cache policy cannot be null");
        Objects.requireNonNull(latitude, "Latitude field cannot be null");
        Objects.requireNonNull(longitude, "Longitude field cannot be null");
    }
}
