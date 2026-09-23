package com.garganttua.core.workflow.aot;

import com.garganttua.core.reflection.annotations.Reflected;

/**
 * The case that accidentally kept working: a type whose fields are <em>all</em>
 * private. The processor dropped them all, the descriptor's field array was
 * empty, and {@code AOTClass.getDeclaredFields()} fell back to the live class —
 * complete and annotated. Pinned here so the move to reflective descriptors
 * does not regress it: the answer must stay complete, now from the descriptor
 * itself.
 */
@Reflected(allDeclaredFields = true)
public class AllPrivateFieldsBean {

    @MemberMarker("only")
    private String only;

    private long size;

    /**
     * Reader, so the fields are not dead code.
     *
     * @return the private label
     */
    public String only() {
        return this.only + this.size;
    }
}
