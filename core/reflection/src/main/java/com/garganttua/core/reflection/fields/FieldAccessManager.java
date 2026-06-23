package com.garganttua.core.reflection.fields;

import java.lang.reflect.Modifier;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.SuppressFBWarnings;

/**
 * {@link AutoCloseable} guard that temporarily makes an {@link IField} accessible
 * for the duration of a try-with-resources block and restores its original
 * accessibility on {@link #close()}.
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

	@Override
	public void close() {
		log.trace("Closing FieldAccessManager, restoring accessibility={} for field={}", originalAccessibility, field.getName());
		this.field.setAccessible(originalAccessibility);
	}
}