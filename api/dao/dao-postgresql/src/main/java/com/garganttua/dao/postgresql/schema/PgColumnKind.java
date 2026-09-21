package com.garganttua.dao.postgresql.schema;

/**
 * What a main-table (or child-table value) column holds, and therefore how the writer binds it and
 * the reader decodes it.
 */
public enum PgColumnKind {

    /** The domain uuid — the table's primary key. Always {@code TEXT}. */
    ID,

    /** One scalar DTO field (possibly flattened out of an embedded POJO), in its natural SQL type. */
    SCALAR,

    /**
     * A field with no relational shape, stored as {@code JSONB}: untyped ({@code Object}, raw
     * collections), nested collections, type-recursive POJOs. Relational flattening would need an
     * infinite or unknowable set of columns for these.
     */
    JSONB,

    /** Crypto key material ({@code IKey}), stored as a self-describing {@code JSONB} descriptor. */
    IKEY,

    /** A GeoJSON geometry, stored as a PostGIS {@code geometry(Geometry, 4326)}. Needs PostGIS. */
    GEOMETRY,

    /**
     * A single {@code @Composed} reference to another domain's entity: holds the referenced uuid
     * ({@code TEXT}), resolved one level deep on read — the relational counterpart of a DBRef.
     */
    COMPOSITION
}
