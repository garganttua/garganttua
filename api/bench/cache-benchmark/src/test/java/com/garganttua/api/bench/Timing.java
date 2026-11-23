package com.garganttua.api.bench;

import java.util.Arrays;

/** The latencies of one run, and what they say. */
final class Timing {

    private long[] nanos = new long[1024];
    private int count;
    private long startedAt;
    private long totalNanos;

    void start() {
        this.startedAt = System.nanoTime();
    }

    void stop() {
        this.totalNanos = System.nanoTime() - this.startedAt;
    }

    void add(long latency) {
        if (this.count == this.nanos.length) {
            this.nanos = Arrays.copyOf(this.nanos, this.count * 2);
        }
        this.nanos[this.count++] = latency;
    }

    private double percentileMicros(double p) {
        long[] sorted = Arrays.copyOf(this.nanos, this.count);
        Arrays.sort(sorted);
        int index = (int) Math.min(this.count - 1, Math.round(p * (this.count - 1)));
        return sorted[index] / 1_000d;
    }

    double opsPerSecond() {
        return this.count / (this.totalNanos / 1_000_000_000d);
    }

    double meanMicros() {
        return Arrays.stream(this.nanos, 0, this.count).average().orElse(0) / 1_000d;
    }

    /** {@return a Markdown table row: label, ops/s, mean, p50, p99, store queries} */
    String row(String label, long storeQueries) {
        return String.format(java.util.Locale.ROOT, "| %s | %,.0f | %.1f | %.1f | %.1f | %,d |", label, opsPerSecond(),
                meanMicros(), percentileMicros(0.50), percentileMicros(0.99), storeQueries);
    }
}
