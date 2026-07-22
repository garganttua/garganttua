package com.garganttua.core.reflection.methods;

import java.lang.reflect.Modifier;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IMethod;

/**
 * Scoped accessibility toggle for an {@link IMethod}, for use in a try-with-resources block.
 *
 * <p><b>{@link #close()} deliberately does not restore the original accessibility.</b>
 * {@link IMethod} handles are memoized and shared process-wide ({@code RuntimeMethod} caches its
 * mirrors in a static map; the AOT fallback caches the {@code AOTMethod}s it synthesizes, each
 * memoizing one {@link java.lang.reflect.Method}), so restoring the flag here would tear it down
 * under every other consumer of the same handle. See {@code FieldAccessManager} for the full
 * rationale.</p>
 */
public class MethodAccessManager implements AutoCloseable {
    private static final Logger log = Logger.getLogger(MethodAccessManager.class);

	private final IMethod method;
	private final boolean originalAccessibility;

	/**
	 * Makes {@code method} accessible without forcing access on inaccessible members.
	 *
	 * @param method the method whose accessibility is managed
	 */
	public MethodAccessManager(IMethod method) {
		this(method, false);
	}

	/**
	 * Makes {@code method} accessible.
	 *
	 * @param method the method whose accessibility is managed
	 * @param force  whether to force access even for non-public members
	 */
	public MethodAccessManager(IMethod method, boolean force) {
		log.trace("Creating MethodAccessManager for method={}, force={}", method, force);
		this.method = method;
		this.originalAccessibility = Modifier.isPublic(method.getModifiers())
				&& Modifier.isPublic(method.getDeclaringClass().getModifiers());
		this.method.setAccessible(true);
		log.debug("Set method {} accessible, original accessibility={}, force={}", method.getName(), originalAccessibility, force);
	}

	/**
	 * No-op: accessibility is intentionally left open because the underlying handle is shared
	 * process-wide. See the class javadoc.
	 */
	@Override
	public void close() {
		log.trace("Closing MethodAccessManager for method={} (accessibility left open: shared handle, original was {})",
				method.getName(), originalAccessibility);
	}
}