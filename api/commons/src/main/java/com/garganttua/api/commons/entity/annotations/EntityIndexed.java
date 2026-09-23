package com.garganttua.api.commons.entity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Declares that the database must carry an index on the annotated field.
 *
 * <p>
 * It exists because {@link EntityUnicity} never reached the database. Uniqueness was enforced by
 * reading the collection and then writing — two calls with a gap between them, which two concurrent
 * requests walk straight through. A declared constraint only the application checks is not a
 * constraint: the store has to hold it, and a store holds it with a unique index.
 * </p>
 *
 * <p>
 * The annotation is deliberately separate from {@link EntityUnicity}: an index is also worth
 * declaring for a field that is merely queried often, and a uniqueness constraint left without one
 * is worth naming out loud at startup rather than silently trusted.
 * </p>
 *
 * <p>
 * Do not confuse it with {@link Indexed}, which indexes an <em>annotation</em> at compile time for
 * classpath scanning. This one indexes data.
 * </p>
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EntityIndexed {

	/** {@return whether the index refuses duplicates} Defaults to a plain, non-unique index. */
	boolean unique() default false;

	/**
	 * {@return the scope the index spans}
	 *
	 * <p>
	 * {@link UnicityScope#tenant} — the default — makes the index composite: the tenant identifier
	 * first, then the field, so a value is unique <em>within</em> a tenant and two tenants may hold
	 * the same one. {@link UnicityScope#system} indexes the field alone.
	 * </p>
	 */
	UnicityScope scope() default UnicityScope.tenant;

	/** {@return what kind of index the store must build} */
	IndexKind kind() default IndexKind.standard;

	/**
	 * {@return the index name, or the empty string to derive one}
	 *
	 * <p>
	 * A derived name is <strong>stable</strong> — the same declaration always derives the same name,
	 * so a restart recognises the index it created at the previous one instead of creating a second.
	 * See {@code EntityIndexRule.derivedName}.
	 * </p>
	 */
	String name() default "";
}
