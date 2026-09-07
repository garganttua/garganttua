package com.garganttua.api.core.perfs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A small measurement harness: run an operation many times, keep every duration, report the shape
 * of the distribution.
 *
 * <p>
 * It reports a MEDIAN and a p95 rather than a mean, because the mean of a latency sample says
 * little — one garbage collection moves it, and the number that matters to a caller is the one most
 * requests get. It also reports the first and last decile separately, which is what catches a cost
 * that GROWS with the number of requests served: a per-request cache that never stops filling, a
 * listener list that accumulates, a resolution redone and re-registered each time. That kind of
 * regression is invisible to a single average.
 * </p>
 *
 * <p>
 * No dependency beyond the JDK, deliberately: a performance harness that drags in a charting
 * library is a harness nobody runs.
 * </p>
 */
final class PerfHarness {

    private PerfHarness() {
    }

    /**
     * The distribution of one measured operation, in microseconds.
     *
     * @param label        what was measured
     * @param iterations   how many times it ran
     * @param medianUs     the duration half the runs beat
     * @param p95Us        the duration 95% of the runs beat
     * @param minUs        the fastest run — the closest thing to a floor for this operation
     * @param firstDecileMinUs fastest run of the first 10%
     * @param lastDecileMinUs  fastest run of the last 10%
     */
    record Stats(String label, int iterations, double medianUs, double p95Us, double minUs,
            double firstDecileMinUs, double lastDecileMinUs) {

        /**
         * {@return how much slower the end of the run is than its start} 1.0 means a flat cost;
         * a number well above it means serving requests makes the next one more expensive.
         */
        double driftRatio() {
            return firstDecileMinUs <= 0 ? 1.0 : lastDecileMinUs / firstDecileMinUs;
        }

        String render() {
            return String.format(Locale.ROOT, "%-34s %8d %11.1f %11.1f %11.1f %10.2f",
                    label, iterations, medianUs, p95Us, minUs, driftRatio());
        }
    }

    static String header() {
        return String.format(Locale.ROOT, "%-34s %8s %11s %11s %11s %10s",
                "operation", "runs", "median(us)", "p95(us)", "min(us)", "drift");
    }

    /**
     * Runs {@code operation} {@code warmup + iterations} times and measures the last
     * {@code iterations}. The warm-up is not optional: the first hundreds of runs measure the JIT
     * compiling the pipeline, not the pipeline.
     *
     * @param label      what is being measured
     * @param warmup     runs to discard
     * @param iterations runs to keep
     * @param operation  the operation, given its run index
     * @return the distribution of the kept runs
     */
    static Stats measure(String label, int warmup, int iterations, IntConsumer operation) {
        for (int i = 0; i < warmup; i++) {
            operation.accept(i);
        }
        long[] durations = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            operation.accept(warmup + i);
            durations[i] = System.nanoTime() - start;
        }
        return summarise(label, durations);
    }

    private static Stats summarise(String label, long[] durations) {
        int n = durations.length;
        int decile = Math.max(1, n / 10);
        double first = minOf(slice(durations, 0, decile));
        double last = minOf(slice(durations, n - decile, n));

        long[] sorted = durations.clone();
        java.util.Arrays.sort(sorted);
        return new Stats(label, n,
                sorted[n / 2] / 1_000.0d,
                sorted[Math.min(n - 1, (int) (n * 0.95))] / 1_000.0d,
                sorted[0] / 1_000.0d,
                first, last);
    }

    private static long[] slice(long[] source, int from, int to) {
        return java.util.Arrays.copyOfRange(source, from, to);
    }

    /**
     * Fastest run of a slice, in MICROSECONDS.
     *
     * <p>
     * The minimum, not the median: a latency sample has a hard floor and an unbounded tail, so
     * anything that goes wrong on the machine — a GC, another process, a frequency change — can
     * only push a measurement UP. The minimum is therefore the statistic that noise cannot inflate,
     * and the only one worth comparing between two slices of the same run.
     * </p>
     */
    private static double minOf(long[] slice) {
        long best = Long.MAX_VALUE;
        for (long d : slice) {
            best = Math.min(best, d);
        }
        return best / 1_000.0d;
    }

    /**
     * Measures two operations INTERLEAVED — a, b, a, b — rather than one after the other.
     *
     * <p>
     * Running scenario A to completion and then scenario B compares them across a gap of seconds,
     * during which the JIT, the collector and whatever else the machine is doing have all moved.
     * That measures the machine as much as the code, and it shows: a first version of this harness
     * reported reading a hundred entities as FASTER than reading none. Alternating the two makes
     * every disturbance hit both sides equally, which is what leaves a difference between them
     * attributable to the code.
     * </p>
     *
     * @param labelA     what the first operation is
     * @param a          the first operation
     * @param labelB     what the second operation is
     * @param b          the second operation
     * @param warmup     runs of EACH to discard
     * @param iterations runs of EACH to keep
     * @return the two distributions, in the order given
     */
    static Stats[] compare(String labelA, IntConsumer a, String labelB, IntConsumer b,
            int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            a.accept(i);
            b.accept(i);
        }
        long[] durationsA = new long[iterations];
        long[] durationsB = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            a.accept(warmup + i);
            durationsA[i] = System.nanoTime() - start;

            start = System.nanoTime();
            b.accept(warmup + i);
            durationsB[i] = System.nanoTime() - start;
        }
        return new Stats[] { summarise(labelA, durationsA), summarise(labelB, durationsB) };
    }

    /** Renders a set of measurements as a table, most expensive first. */
    static String report(String title, List<Stats> stats) {
        List<Stats> ordered = new ArrayList<>(stats);
        ordered.sort((a, b) -> Double.compare(b.medianUs(), a.medianUs()));
        StringBuilder sb = new StringBuilder(512);
        sb.append(System.lineSeparator()).append(title).append(System.lineSeparator());
        sb.append(header()).append(System.lineSeparator());
        for (Stats s : ordered) {
            sb.append(s.render()).append(System.lineSeparator());
        }
        return sb.toString();
    }
}
