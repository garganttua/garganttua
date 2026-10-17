package com.garganttua.api.commons.entity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Annotation form of {@code .shares(field)}: names the field holding the entity's share grants.
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EntityShares {
	/** The grants field: a {@code List<String>} of {@link com.garganttua.api.commons.entity.ShareGrant} tokens. */
	String value();

}
