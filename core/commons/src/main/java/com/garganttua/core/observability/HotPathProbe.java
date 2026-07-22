package com.garganttua.core.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ultra-light, opt-in attribution probe for hot paths (the per-request CRUD read path in
 * particular). Accumulates wall-clock nanos and a call count per label, so a run can be broken
 * down stage by stage without an external profiler.
 *
 * <p><b>Disabled by default, and free when disabled.</b> The switch is a {@code static final}
 * boolean resolved once from the {@code garganttua.perf.probe} system property — same pattern as
 * {@link Logger}'s level threshold — so both the JIT and GraalVM's closed-world compiler fold the
 * branch away and the probe calls become dead code. Enable with {@code -Dgarganttua.perf.probe=true}.
 *
 * <p>Usage at a call site:
 * <pre>{@code
 * long t = HotPathProbe.start();
 * ... work to attribute ...
 * HotPathProbe.end("injection.copy", t);
 * }</pre>
 *
 * <p>This is a measurement aid, NOT an observability event source: it deliberately does not emit
 * {@link ObservableEvent}s, because the whole point is to add as close to zero overhead as possible
 * on the path being measured. For user-facing instrumentation use {@link ObservabilityEmitter}.
 *
 * <p>Thread-safe: counters are {@link LongAdder}s in a {@link ConcurrentHashMap}, so concurrent
 * requests accumulate without contention on the probe itself.
 */
public final class HotPathProbe {

	/** Resolved once at class initialisation so the branch constant-folds when off. */
	private static final boolean ENABLED = Boolean.getBoolean("garganttua.perf.probe");

	private static final ConcurrentHashMap<String, Counter> COUNTERS = new ConcurrentHashMap<>();

	private HotPathProbe() {
	}

	/** Accumulated nanos + invocation count for one label. */
	private static final class Counter {
		private final LongAdder nanos = new LongAdder();
		private final LongAdder calls = new LongAdder();
	}

	/** {@return whether the probe is active} — {@code -Dgarganttua.perf.probe=true}. */
	public static boolean isEnabled() {
		return ENABLED;
	}

	/**
	 * {@return a start timestamp to hand back to {@link #end}} — {@code 0L} (and no clock read)
	 * when the probe is disabled.
	 */
	public static long start() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	/**
	 * Records the elapsed time since {@code startNanos} under {@code label}. No-op when the probe
	 * is disabled.
	 *
	 * @param label      attribution bucket, e.g. {@code "injection.copy"}
	 * @param startNanos the value previously returned by {@link #start()}
	 */
	public static void end(String label, long startNanos) {
		if (!ENABLED) {
			return;
		}
		Counter counter = COUNTERS.computeIfAbsent(label, k -> new Counter());
		counter.nanos.add(System.nanoTime() - startNanos);
		counter.calls.increment();
	}

	/** Clears every accumulated measurement (e.g. between a warm-up and the measured run). */
	public static void reset() {
		COUNTERS.clear();
	}

	/**
	 * {@return the accumulated totals per label}, as an immutable snapshot of
	 * {@code label -> [calls, totalNanos]}. Empty when the probe is disabled.
	 */
	public static Map<String, long[]> snapshot() {
		Map<String, long[]> out = new ConcurrentHashMap<>();
		COUNTERS.forEach((label, counter) -> out.put(label, new long[] { counter.calls.sum(), counter.nanos.sum() }));
		return out;
	}

	/**
	 * {@return a human-readable attribution report}, one line per label sorted by total time
	 * descending: total ms, call count, and average µs per call.
	 */
	public static String report() {
		if (!ENABLED) {
			return "HotPathProbe disabled (enable with -Dgarganttua.perf.probe=true)";
		}
		List<Map.Entry<String, Counter>> entries = new ArrayList<>(COUNTERS.entrySet());
		entries.sort(Comparator.comparingLong((Map.Entry<String, Counter> e) -> e.getValue().nanos.sum()).reversed());

		StringBuilder sb = new StringBuilder(256);
		sb.append(String.format("%-40s %12s %10s %12s%n", "label", "total(ms)", "calls", "avg(us)"));
		for (Map.Entry<String, Counter> entry : entries) {
			long nanos = entry.getValue().nanos.sum();
			long calls = entry.getValue().calls.sum();
			double totalMs = nanos / 1_000_000.0d;
			double avgUs = calls == 0 ? 0.0d : (nanos / 1_000.0d) / calls;
			sb.append(String.format("%-40s %12.3f %10d %12.1f%n", entry.getKey(), totalMs, calls, avgUs));
		}
		return sb.toString();
	}
}
