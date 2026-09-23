package com.garganttua.core.aot.reflection;

/**
 * Top-level fixture for {@link AOTAnnotationsTest}: one annotated field, one
 * annotated method (with a parameter) and one annotated constructor, plus their
 * unannotated counterparts.
 */
public class AOTAnnotationsFixture {

    /** Annotated field. */
    @AOTAnnotationsMarker("field")
    public String tag;

    /** Unannotated field. */
    public int count;

    /**
     * Annotated constructor.
     *
     * @param tag the initial tag
     */
    @AOTAnnotationsMarker("constructor")
    public AOTAnnotationsFixture(String tag) {
        this.tag = tag;
    }

    /** Unannotated constructor. */
    public AOTAnnotationsFixture() {
        this("");
    }

    /**
     * Annotated method.
     *
     * @param prefix prefix to prepend
     * @return the prefixed tag
     */
    @AOTAnnotationsMarker("method")
    public String describe(String prefix) {
        return prefix + this.tag;
    }

    /**
     * Unannotated method.
     *
     * @return the tag
     */
    public String plain() {
        return this.tag;
    }
}
