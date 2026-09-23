package com.garganttua.api.commons.entity.annotations;

/**
 * What kind of index a field declares through {@link EntityIndexed}.
 *
 * <p>
 * The kind is what the store is asked to build, not what the framework checks: a declared index is
 * a statement about the database, and only the database can hold it. The DAO layer maps each kind
 * onto its own vocabulary — a plain b-tree, a geospatial index, a full-text index.
 * </p>
 */
public enum IndexKind {

    /** An ordinary index on the field value — the default, and what a uniqueness constraint needs. */
    standard,

    /** A geospatial index, for a field carrying coordinates queried by proximity or containment. */
    geo,

    /** A full-text index, for a field queried by words rather than by value. */
    text
}
