package com.garganttua.core.reflection.constructors;

import java.lang.reflect.Modifier;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IConstructor;

/**
 * {@link AutoCloseable} guard that makes an {@link IConstructor} accessible for the duration of a
 * try-with-resources block.
 *
 * <p><b>{@link #close()} deliberately does not restore the original accessibility.</b>
 * {@link IConstructor} handles are memoized and shared process-wide ({@code RuntimeConstructor}
 * caches its mirrors in a static map; the AOT fallback caches the {@code AOTConstructor}s it
 * synthesizes — a path any single-constructor class takes via the shallow-descriptor threshold),
 * so restoring the flag here would tear it down under every other consumer of the same handle.
 * See {@code FieldAccessManager} for the full rationale.</p>
 */
public class ConstructorAccessManager implements AutoCloseable {
    private static final Logger log = Logger.getLogger(ConstructorAccessManager.class);

	private final IConstructor<?> constructor;
	private final boolean originalAccessibility;

	/**
	 * Equivalent to {@link #ConstructorAccessManager(IConstructor, boolean)} with {@code force = false}.
	 *
	 * @param constructor the constructor to make accessible
	 */
	public ConstructorAccessManager(IConstructor<?> constructor) {
		this(constructor, false);
	}

	/**
	 * Makes the given constructor accessible, recording its original accessibility for later restoration.
	 *
	 * @param constructor the constructor to make accessible
	 * @param force       whether access was forced (recorded for diagnostics)
	 */
	public ConstructorAccessManager(IConstructor<?> constructor, boolean force) {
		log.trace("Creating ConstructorAccessManager for constructor={}, force={}", constructor, force);
		this.constructor = constructor;
		this.originalAccessibility = Modifier.isPublic(constructor.getModifiers())
				&& Modifier.isPublic(constructor.getDeclaringClass().getModifiers());
		this.constructor.setAccessible(true);
		log.debug("Set constructor {} accessible, original accessibility={}, force={}", constructor.getName(), originalAccessibility, force);
	}

	/**
	 * No-op: accessibility is intentionally left open because the underlying handle is shared
	 * process-wide. See the class javadoc.
	 */
	@Override
	public void close() {
		log.trace("Closing ConstructorAccessManager for constructor={} (accessibility left open: shared handle, original was {})",
				constructor.getName(), originalAccessibility);
	}
}