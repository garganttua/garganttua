package com.garganttua.core.aot.reflection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RUNTIME-retained marker used by {@link AOTAnnotationsTest} to stand in for
 * the real member annotations ({@code @EntityId}, {@code @Inject}, mapping
 * rules…) that AOT descriptors used to drop.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AOTAnnotationsMarker {

    /**
     * Free-form label, asserted by the test to prove the recovered annotation
     * is the member's own instance and not some other member's.
     *
     * @return the label
     */
    String value();
}
