package com.garganttua.core.workflow.aot;

import com.garganttua.core.reflection.annotations.Reflected;

/**
 * The consumer's shape, reduced: one {@code public static final} constant next
 * to {@code private} annotated fields.
 *
 * <p>This is what used to break. The processor dropped every private member, so
 * the generated {@code AOTClass_*} held exactly one field — the constant. Its
 * array was no longer empty, so {@code AOTClass.getDeclaredFields()} stopped
 * falling back to the live class, and {@code id} / {@code count} simply did not
 * exist as far as the framework was concerned: resolving the entity identifier
 * ({@code entity().id("id")}, which walks {@code getDeclaredFields()}) failed
 * and the whole API refused to start. Remove the constant and the descriptor's
 * array was empty again, the fallback fired, and everything worked — which is
 * why the defect looked like "adding a constant breaks the app".</p>
 */
@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true,
        queryAllDeclaredConstructors = true)
public class ConstantAndPrivateFieldsBean {

    /** The one non-private field — enough, on its own, to hide all the others. */
    public static final String VERSION = "1";

    @MemberMarker("id")
    private String id;

    private int count;

    /** Package-private, so the type keeps a direct binder alongside the reflective ones. */
    String label;

    /** Only constructor, and private: the type used to lose it entirely. */
    @MemberMarker("constructor")
    private ConstantAndPrivateFieldsBean() {
        this.id = "";
    }

    /**
     * Factory, since the constructor is private.
     *
     * @param id the identifier to carry
     * @return a new bean
     */
    public static ConstantAndPrivateFieldsBean of(String id) {
        ConstantAndPrivateFieldsBean bean = new ConstantAndPrivateFieldsBean();
        bean.id = id;
        return bean;
    }

    @MemberMarker("method")
    private String secret() {
        return this.id + '/' + this.count;
    }

    /**
     * Public reader, so {@code secret()} is not dead code.
     *
     * @return the private method's result
     */
    public String describe() {
        return secret();
    }
}
