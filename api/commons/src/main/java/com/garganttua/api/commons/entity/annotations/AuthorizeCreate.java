package com.garganttua.api.commons.entity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Declares a field a caller may valorize at CREATION. The annotation counterpart of
 * {@code entity().create(field[, authority])} on the entity DSL.
 *
 * <p>Declaring any {@code @AuthorizeCreate} turns creation into a WHITELIST: only annotated fields
 * are kept from the client body, every other client-supplied field is stripped before persist. With
 * no {@code @AuthorizeCreate} (and no {@code create(...)} on the DSL) at all, creation is
 * unrestricted.
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuthorizeCreate {

  /** Authority the caller must carry to valorize this field. Empty means no authority is required. */
  String authority() default "";

}
