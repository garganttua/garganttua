package com.garganttua.core.reflection.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Module-local equivalent of {@code edu.umd.cs.findbugs.annotations.SuppressFBWarnings}.
 *
 * <p>SpotBugs matches suppression annotations by simple name ({@code SuppressFBWarnings}) and
 * reads their {@code value} member, regardless of declaring package. This module cannot add the
 * official {@code spotbugs-annotations} dependency, so this {@code CLASS}-retained annotation
 * provides the same narrowly-scoped suppression capability for reviewed false positives /
 * design-intentional findings, without leaking onto the runtime classpath.</p>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.TYPE,
        ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface SuppressFBWarnings {

    /** @return the SpotBugs bug pattern(s) to suppress */
    String[] value() default {};

    /** @return the human-readable reason the finding is suppressed */
    String justification() default "";
}
