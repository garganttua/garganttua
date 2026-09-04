package com.garganttua.api.commons.entity.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Marks a field the entity must carry.
 *
 * <p>
 * The bare form keeps its historical meaning — "not {@code null}". Declare
 * {@code @EntityMandatory(MandatoryPolicy.nonBlank)} to also refuse the empty string and a string
 * of whitespace, at creation and on any value the client actually sends at update.
 * </p>
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface EntityMandatory {

	/** {@return what the field is required to carry} Defaults to the historical "not null". */
	MandatoryPolicy value() default MandatoryPolicy.anyValue;
}
