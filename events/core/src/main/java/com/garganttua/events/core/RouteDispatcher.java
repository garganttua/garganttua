package com.garganttua.events.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.garganttua.core.observability.Logger;

/**
 * Executes a route's per-message work either inline (ordered) or on a bounded worker pool
 * (parallel), per the subscription's {@code concurrency} and the dataflow's {@code garanteeOrder}.
 *
 * <p><b>Why no "parallel-keyed" mode:</b> at dispatch time the {@code Exchange} is still raw bytes —
 * its tenant / correlation id are populated later by the {@code protocol_in} stage — and the
 * {@code byte[]}-only consumer SPI exposes no partition key. There is therefore no stable ordering
 * key available to keep same-key messages on the same worker. Consequently
 * {@code garanteeOrder = true} forces sequential execution regardless of the requested concurrency;
 * parallelism is offered only for dataflows that do not require ordering.</p>
 *
 * @since 3.0.0-ALPHA04
 */
final class RouteDispatcher implements AutoCloseable {

	private static final Logger log = Logger.getLogger(RouteDispatcher.class);

	/** {@code null} ⇒ run tasks inline on the consumer thread (sequential, ordered). */
	private final ExecutorService pool;

	RouteDispatcher(String routeUuid, int concurrency, boolean guaranteeOrder) {
		int workers = Math.max(1, concurrency);
		if (workers > 1 && guaranteeOrder) {
			log.warn("Route {}: concurrency={} suppressed — the dataflow guarantees order, so it runs "
					+ "sequentially (parallel-keyed needs a connector message key, unavailable on the "
					+ "byte[] consumer SPI)", routeUuid, workers);
		}
		if (workers > 1 && !guaranteeOrder) {
			this.pool = Executors.newFixedThreadPool(workers, runnable -> {
				Thread thread = new Thread(runnable, "route-worker-" + routeUuid);
				thread.setDaemon(true);
				return thread;
			});
			log.info("Route {}: processing messages on {} parallel workers", routeUuid, workers);
		} else {
			this.pool = null;
		}
	}

	/** Runs the task inline (ordered) or submits it to the worker pool (parallel). */
	void dispatch(Runnable task) {
		if (pool == null) {
			task.run();
		} else {
			pool.execute(task);
		}
	}

	@Override
	public void close() {
		if (pool != null) {
			pool.shutdown();
		}
	}
}
