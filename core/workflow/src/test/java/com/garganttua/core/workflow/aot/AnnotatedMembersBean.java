package com.garganttua.core.workflow.aot;

import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Top-level {@code @Reflected} fixture whose field, method and constructor each
 * carry a {@link MemberMarker}. The AOT annotation processor runs over this
 * module's test sources ({@code garganttua.direct.binders=true}), so the build
 * really generates {@code AOTField_AnnotatedMembersBean_tag},
 * {@code AOTMethod_AnnotatedMembersBean_describe_0} and
 * {@code AOTConstructor_AnnotatedMembersBean_0} — which
 * {@link MemberAnnotationAotTest} then inspects.
 *
 * <p>Top-level on purpose: a nested {@code @Reflected} type is registered under
 * its dotted name but looked up under its binary name, so its descriptor is
 * never used and the test would silently measure live reflection instead.</p>
 */
@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true,
        queryAllDeclaredConstructors = true)
public class AnnotatedMembersBean {

    /** Annotated field. */
    @MemberMarker("field")
    public String tag;

    /** Unannotated field. */
    public int count;

    /**
     * Annotated constructor.
     *
     * @param tag the initial tag
     */
    @MemberMarker("constructor")
    public AnnotatedMembersBean(String tag) {
        this.tag = tag;
    }

    /**
     * Annotated method.
     *
     * @param prefix prefix to prepend
     * @return the prefixed tag
     */
    @MemberMarker("method")
    public String describe(String prefix) {
        return prefix + this.tag;
    }
}
