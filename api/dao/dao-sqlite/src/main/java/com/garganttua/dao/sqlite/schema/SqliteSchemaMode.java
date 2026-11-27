package com.garganttua.dao.sqlite.schema;

/**
 * Who owns the schema of the SQLite DAO's tables.
 */
public enum SqliteSchemaMode {
    /** The DAO creates the missing tables and adds the missing columns at registration; it never drops nor retypes. */
    CREATE,
    /** The DAO issues no DDL: it checks that every table and column exists, and refuses to start otherwise. */
    VALIDATE
}
