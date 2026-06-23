package com.garganttua.core.mapper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;

/**
 * Copy-on-write registry of {@link IMappingListener}s with fault-tolerant fan-out:
 * a listener that throws is logged and skipped so it cannot abort a mapping.
 */
final class MappingListeners {
	private static final Logger log = Logger.getLogger(MappingListeners.class);

	private final List<IMappingListener> listeners = new CopyOnWriteArrayList<>();

	void add(IMappingListener listener) {
		this.listeners.add(Objects.requireNonNull(listener));
	}

	void fireBeforeMapping(Object source, IClass<?> destClass) {
		for (IMappingListener listener : this.listeners) {
			try {
				listener.onBeforeMapping(source, destClass);
			} catch (Exception e) {
				log.warn("Listener onBeforeMapping failed: {}", e.getMessage());
			}
		}
	}

	void fireAfterMapping(Object source, Object dest, long durationNanos) {
		for (IMappingListener listener : this.listeners) {
			try {
				listener.onAfterMapping(source, dest, durationNanos);
			} catch (Exception e) {
				log.warn("Listener onAfterMapping failed: {}", e.getMessage());
			}
		}
	}

	void fireMappingError(Object source, IClass<?> destClass, Exception error) {
		for (IMappingListener listener : this.listeners) {
			try {
				listener.onMappingError(source, destClass, error);
			} catch (Exception e) {
				log.warn("Listener onMappingError failed: {}", e.getMessage());
			}
		}
	}
}
