package com.garganttua.core.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A probe left off — the default, and what every request runs with — must cost a hot path nothing: no counter per
 * site, nothing recorded. (The probe is fixed at class load from {@code garganttua.perf.probe}, so the enabled side is
 * exercised by the benchmarks that switch it on, not here.)
 */
@DisplayName("HotPathProbe sites, probe off")
class HotPathProbeSiteTest {

    @Test
    @DisplayName("every site of a disabled probe is the same no-op, and records nothing")
    void disabledSitesRecordNothing() {
        assertFalse(HotPathProbe.isEnabled(), "the suite runs with the probe off");
        HotPathProbe.Site one = HotPathProbe.site("test.one");
        HotPathProbe.Site other = HotPathProbe.site("test.other:" + System.nanoTime());
        assertSame(one, other, "no counter is created per label");
        one.end(HotPathProbe.start());
        other.end(HotPathProbe.start());
        assertTrue(HotPathProbe.snapshot().isEmpty());
    }
}
