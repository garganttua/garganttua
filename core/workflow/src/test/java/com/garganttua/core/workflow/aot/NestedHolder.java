package com.garganttua.core.workflow.aot;

import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Holder for a nested {@code @Reflected} type.
 *
 * <p>Its descriptor was generated and shipped, then never used: the processor
 * registered it under the dotted canonical name
 * ({@code …aot.NestedHolder.Inner}) while {@code AOTReflectionProvider} only
 * ever looks up {@code Class#getName()} ({@code …aot.NestedHolder$Inner}). The
 * lookup missed, a shallow descriptor was synthesised in its place, and the
 * type silently fell back to live reflection — invisible on the JVM, fatal in a
 * closed-world native image.</p>
 */
public class NestedHolder {

    private NestedHolder() {
    }

    /** Nested {@code @Reflected} type whose generated descriptor must actually be used. */
    @Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true,
            queryAllDeclaredConstructors = true)
    public static class Inner {

        @MemberMarker("nested-field")
        private String label;

        /**
         * Creates an inner value.
         *
         * @param label the label to carry
         */
        public Inner(String label) {
            this.label = label;
        }

        /**
         * Reads the label.
         *
         * @return the label
         */
        public String label() {
            return this.label;
        }
    }
}
