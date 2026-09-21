package com.garganttua.dao.postgresql.schema;

/** Who owns the DDL of a domain's tables. */
public enum SchemaMode {

    /**
     * The DAO creates what is missing — tables, and columns added to the DTO since — and never
     * drops or alters anything that exists. The default: an application starts on an empty database
     * with no manual step, as it does on MongoDB.
     */
    CREATE,

    /**
     * The DAO issues no DDL at all. It checks that every table and column it needs exists, and
     * refuses to start otherwise, with the DDL it expected in the message. For environments where
     * the schema is migrated by a separate, reviewed process.
     */
    VALIDATE
}
