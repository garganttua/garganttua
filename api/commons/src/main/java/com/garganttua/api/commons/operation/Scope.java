package com.garganttua.api.commons.operation;

public enum Scope {
    allEntities("all"),
    oneEntity("one"),
    listOfEntities("listOf"),
    /**
     * The CALLER'S OWN entity — resolved from the verified caller, never named by the request.
     *
     * <p>
     * Distinct from {@link #oneEntity} because the entity is not designated by an input: there is
     * no path segment and no body field by which a caller could ask for someone else's. That is
     * what lets the operation carry its own authority, separate from a read over the domain.
     * </p>
     */
    self("self");

    private String label;

    Scope(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return this.label;
    }
}
