package com.garganttua.api.commons.entity;

import java.util.Objects;

import com.garganttua.api.commons.entity.annotations.EntityIndexed;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * One index an entity declares on the store: which field it covers, whether it refuses duplicates,
 * the scope it spans, what kind of index it is, and the name it is created under.
 *
 * <p>
 * Declared via {@code entity().index(field[, unique][, scope][, kind][, name])} on the DSL, or via
 * {@link EntityIndexed} on the entity field. The framework does not build the index — it carries
 * the declaration down to the DAO, which is the only layer that can speak to the store.
 * </p>
 *
 * @param field  address of the indexed field on the entity
 * @param unique {@code true} when the store must refuse a duplicate value
 * @param scope  {@link UnicityScope#tenant} for an index composed with the tenant identifier,
 *               {@link UnicityScope#system} for an index on the field alone
 * @param kind   what kind of index the store must build
 * @param name   the index name — never blank: an empty declaration is replaced by
 *               {@link #derivedName(ObjectAddress, boolean, UnicityScope, IndexKind)}
 */
public record EntityIndexRule(ObjectAddress field, boolean unique, UnicityScope scope, IndexKind kind, String name) {

	/** Prefix every derived name carries, so an index the framework created is recognisable. */
	private static final String DERIVED_NAME_PREFIX = "gg";

	/**
	 * Normalises the declaration: a null scope or kind falls back to the annotation's own defaults,
	 * and a blank name is replaced by the derived one.
	 */
	public EntityIndexRule {
		Objects.requireNonNull(field, "Index field address cannot be null");
		scope = scope == null ? UnicityScope.tenant : scope;
		kind = kind == null ? IndexKind.standard : kind;
		name = (name == null || name.isBlank()) ? derivedName(field, unique, scope, kind) : name;
	}

	/** A non-unique, tenant-scoped standard index on {@code field}, named by derivation. */
	public static EntityIndexRule of(ObjectAddress field) {
		return new EntityIndexRule(field, false, UnicityScope.tenant, IndexKind.standard, null);
	}

	/** A standard index on {@code field} with an explicit uniqueness and scope, named by derivation. */
	public static EntityIndexRule of(ObjectAddress field, boolean unique, UnicityScope scope) {
		return new EntityIndexRule(field, unique, scope, IndexKind.standard, null);
	}

	/**
	 * The name an index carries when the declaration names none.
	 *
	 * <p>
	 * It is a pure function of the declaration, so it is <strong>stable</strong> across restarts:
	 * the same field, uniqueness, scope and kind always derive the same name, and a store asked
	 * twice for that index creates it once. Every part of the declaration appears in the name
	 * because two indexes that differ only by one of them are two different indexes — dropping the
	 * distinction would make the second silently collide with the first.
	 * </p>
	 *
	 * @param field  address of the indexed field
	 * @param unique whether the index refuses duplicates
	 * @param scope  the scope the index spans
	 * @param kind   the kind of index
	 * @return the derived name, e.g. {@code gg_email_tenant_standard_unique}
	 */
	public static String derivedName(ObjectAddress field, boolean unique, UnicityScope scope, IndexKind kind) {
		Objects.requireNonNull(field, "Index field address cannot be null");
		String path = field.toString().replaceAll("[^a-zA-Z0-9_]", "_");
		return String.join("_", DERIVED_NAME_PREFIX, path,
				(scope == null ? UnicityScope.tenant : scope).name(),
				(kind == null ? IndexKind.standard : kind).name(),
				unique ? "unique" : "idx");
	}
}
