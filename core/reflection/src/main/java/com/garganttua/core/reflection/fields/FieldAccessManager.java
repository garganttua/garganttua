package com.garganttua.core.reflection.fields;

import java.lang.reflect.Modifier;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.SuppressFBWarnings;

/**
 * {@link AutoCloseable} guard that makes an {@link IField} accessible for the duration of a
 * try-with-resources block.
 *
 * <p><b>{@link #close()} deliberately does not restore the original accessibility.</b>
 * {@link IField} handles are memoized and shared process-wide — {@code RuntimeField},
 * {@code RuntimeMethod} and {@code RuntimeConstructor} cache their mirrors in static maps keyed by
 * the JDK member, and the AOT fallback caches the {@code AOTField}s it synthesizes, each of which
 * memoizes one {@link java.lang.reflect.Field}. The {@code accessible} flag on such a handle is
 * therefore shared mutable state: restoring it here would tear the flag down under every other
 * consumer of the same handle, including ones that legitimately called
 * {@code setAccessible(true)} and have not yet performed their {@code get}/{@code set}. That race
 * surfaced as random {@link IllegalAccessException}s on concurrent CRUD traffic.</p>
 */
public class FieldAccessManager implements AutoCloseable {
    private static final Logger log = Logger.getLogger(FieldAccessManager.class);

	private final IField field;
	private final boolean originalAccessibility;

	/**
	 * Creates a manager that makes the given field accessible without forcing
	 * access to otherwise inaccessible members.
	 *
	 * @param field the field to make accessible
	 */
	public FieldAccessManager(IField field) {
		this(field, false);
	}

	/**
	 * Creates a manager that makes the given field accessible.
	 *
	 * @param field the field to make accessible
	 * @param force whether to force access to non-public members
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
			justification = "The IField is a shared reflection handle the guard must mutate in place to restore accessibility; copying it would defeat the purpose.")
	public FieldAccessManager(IField field, boolean force) {
		log.trace("Creating FieldAccessManager for field={}, force={}", field, force);
		this.field = field;
		this.originalAccessibility = Modifier.isPublic(field.getModifiers())
				&& Modifier.isPublic(field.getDeclaringClass().getModifiers());
		this.field.setAccessible(true);
		log.debug("Set field {} accessible, original accessibility={}, force={}", field.getName(), originalAccessibility, force);
	}

	/**
	 * No-op: accessibility is intentionally left open because the underlying handle is shared
	 * process-wide. See the class javadoc.
	 */
	@Override
	public void close() {
		log.trace("Closing FieldAccessManager for field={} (accessibility left open: shared handle, original was {})",
				field.getName(), originalAccessibility);
	}
}