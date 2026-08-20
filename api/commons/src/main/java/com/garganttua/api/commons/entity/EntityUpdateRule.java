package com.garganttua.api.commons.entity;

import com.garganttua.core.reflection.ObjectAddress;

/**
 * One entry of an entity's UPDATE whitelist: the field a caller may valorize, the authority it
 * requires, and how a {@code null} incoming value is interpreted.
 *
 * <p>Declared via {@code entity().update(field[, authority][, ignoreNull])} on the DSL, or via
 * {@link com.garganttua.api.commons.entity.annotations.AuthorizeUpdate} on the entity field.
 *
 * @param field      address of the updatable field on the entity
 * @param authority  authority the caller must carry to write the field; {@code null} or empty means
 *                   no authority is required
 * @param ignoreNull when {@code true}, a {@code null} incoming value leaves the stored value
 *                   untouched (PATCH semantics). When {@code false} — the default — a {@code null}
 *                   incoming value overwrites the stored value with {@code null} (PUT semantics).
 */
public record EntityUpdateRule(ObjectAddress field, String authority, boolean ignoreNull) {

	/** An unguarded rule with PUT semantics: no authority required, a null incoming value erases. */
	public static EntityUpdateRule of(ObjectAddress field) {
		return new EntityUpdateRule(field, null, false);
	}

	/** A rule guarded by {@code authority}, with PUT semantics (a null incoming value erases). */
	public static EntityUpdateRule of(ObjectAddress field, String authority) {
		return new EntityUpdateRule(field, authority, false);
	}
}
