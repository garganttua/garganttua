package com.garganttua.dao.postgresql.schema;

import java.util.List;

import com.garganttua.dao.sql.schema.SqlNaming;

/**
 * How the PostgreSQL DAO names its tables, columns and indexes — kept for the code that computes those names itself
 * (a migration, a hand-written query). The naming now lives in the relational core shared by the SQL DAOs,
 * {@link SqlNaming}, which every dialect uses: the same DTO gets the same table and column names on every database.
 */
public final class PgNaming {

    /** @see SqlNaming#PATH_SEPARATOR */
    public static final String PATH_SEPARATOR = SqlNaming.PATH_SEPARATOR;
    /** @see SqlNaming#MAX_IDENTIFIER_BYTES */
    public static final int MAX_IDENTIFIER_BYTES = SqlNaming.MAX_IDENTIFIER_BYTES;

    private PgNaming() {
        // Static helpers
    }

    /** @see SqlNaming#quote(String) */
    public static String quote(String identifier) {
        return SqlNaming.quote(identifier);
    }

    /** @see SqlNaming#column(List) */
    public static String column(List<String> fieldPath) {
        return SqlNaming.column(fieldPath);
    }

    /** @see SqlNaming#table(String) */
    public static String table(String domainName) {
        return SqlNaming.table(domainName);
    }

    /** @see SqlNaming#childTable(String, List) */
    public static String childTable(String table, List<String> fieldPath) {
        return SqlNaming.childTable(table, fieldPath);
    }

    /** @see SqlNaming#index(String, String) */
    public static String index(String table, String indexName) {
        return SqlNaming.index(table, indexName);
    }
}
