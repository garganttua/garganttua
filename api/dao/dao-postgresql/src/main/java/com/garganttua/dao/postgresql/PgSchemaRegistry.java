package com.garganttua.dao.postgresql;

import com.garganttua.dao.sql.SqlSchemaRegistry;

/**
 * The shape of every domain of one PostgreSQL database, shared by their DAOs so that a {@code @Composed} field
 * reaches another domain's table. Kept under its name for the code that wires {@link PgDao} by hand; the registry
 * itself is the relational core's, {@link SqlSchemaRegistry}.
 */
public final class PgSchemaRegistry extends SqlSchemaRegistry {
}
