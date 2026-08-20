package com.garganttua.api.commons.entity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Declares a field a caller may valorize at UPDATE. The annotation counterpart of
 * {@code entity().update(field[, authority][, ignoreNull])} on the entity DSL.
 *
 * <p>Declaring any {@code @AuthorizeUpdate} turns the update into a WHITELIST: only annotated
 * fields are merged from the client body onto the stored entity, every other field is left
 * untouched.
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuthorizeUpdate {

  /** Authority the caller must carry to write this field. Empty means no authority is required. */
  String authority() default "";

  /**
   * How a {@code null} incoming value is interpreted.
   *
   * <p>{@code false} (default) — PUT semantics: a {@code null} in the client body overwrites the
   * stored value with {@code null}.
   *
   * <p>{@code true} — PATCH semantics: a {@code null} in the client body means "not supplied", and
   * the stored value is left untouched.
   */
  boolean ignoreNull() default false;

}
