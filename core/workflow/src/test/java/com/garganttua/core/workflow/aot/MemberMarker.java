package com.garganttua.core.workflow.aot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RUNTIME-retained member marker used by {@link MemberAnnotationAotTest}. It
 * stands in for the real member annotations a consumer relies on
 * ({@code @EntityId}, {@code @Inject}, mapping rules…), which AOT descriptors
 * used to drop on the floor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface MemberMarker {

    /**
     * Label asserted by the test, so a passing assertion proves the descriptor
     * carries <em>that</em> member's annotation.
     *
     * @return the label
     */
    String value();
}
