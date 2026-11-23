package com.garganttua.core.cache;

/**
 * The counters of a cache, on the node that reads them.
 *
 * @param hits          reads served from the cache
 * @param misses        reads that went to the source — including those of a cache bypassed because it could not
 *                      vouch for its entries
 * @param invalidations keys removed by {@code invalidate}, plus one per {@code invalidateAll}
 * @param size          entries held on this node, {@code -1} when the implementation does not know
 */
public record CacheStats(long hits, long misses, long invalidations, long size) {

    /** {@return the share of reads served from the cache, {@code 0} before any read} */
    public double hitRate() {
        long reads = this.hits + this.misses;
        return reads == 0 ? 0d : (double) this.hits / reads;
    }
}
