package com.garganttua.dao.postgresql.schema;

/** What a child table holds. Every child table carries {@code _owner}, the owning row's id. */
public enum PgChildKind {

    /** A list/set of scalars: {@code (_owner, _ord, value)}. */
    SCALAR_COLLECTION,

    /** A list/set of flattenable POJOs: {@code (_owner, _ord, <flattened element columns>)}. */
    POJO_COLLECTION,

    /** A map with scalar keys: {@code (_owner, _key, value)} or {@code (_owner, _key, <flattened>)}. */
    MAP,

    /**
     * A collection of {@code @Composed} references: {@code (_owner, _ord, value)} where value is the
     * referenced uuid, resolved one level deep on read.
     */
    COMPOSITION_COLLECTION
}
