package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads what the database actually holds, and compares a stored column type with a model type.
 *
 * <p>
 * The comparison is the delicate part. The model speaks DDL ({@code TIMESTAMPTZ}, {@code BIGINT},
 * {@code geometry(Geometry, 4326)}); {@code information_schema} answers in SQL-standard spelling
 * ({@code timestamp with time zone}, {@code bigint}) and reports every extension type as
 * {@code USER-DEFINED}, with the real name only in {@code udt_name}. A naive string comparison would
 * flag every timestamp and every geometry as mistyped — so both sides are normalised first.
 * </p>
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // accessor style: a constant/field and the method using it share a name
final class PgSchemaInspector {

    /**
     * A column as the catalog reports it.
     *
     * @param dataType {@code information_schema.columns.data_type}
     * @param udtName  {@code information_schema.columns.udt_name}
     */
    record StoredColumn(String dataType, String udtName) {

        /** {@return the type as a human reads it — the extension's name rather than USER-DEFINED} */
        String describe() {
            return USER_DEFINED.equals(dataType) ? udtName : dataType;
        }
    }

    private static final String USER_DEFINED = "USER-DEFINED";

    /** DDL spellings whose catalog spelling differs; every other type is reported as written. */
    private static final Map<String, String> CATALOG_SPELLING = Map.of(
            "timestamptz", "timestamp with time zone",
            "timestamp", "timestamp without time zone",
            "timetz", "time with time zone",
            "time", "time without time zone",
            "int", "integer",
            "int4", "integer",
            "int8", "bigint",
            "int2", "smallint",
            "float8", "double precision",
            "float4", "real");

    private static final String COLUMNS = "SELECT column_name, data_type, udt_name "
            + "FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position";

    private static final String EXTENSION = "SELECT 1 FROM pg_extension WHERE extname = ?";

    private PgSchemaInspector() {
        // Static helpers
    }

    /**
     * The columns of one table in the current schema.
     *
     * @param connection the connection — inside the caller's transaction, so it sees tables that
     *                   transaction just created
     * @param table      the table name, unquoted
     * @return column name to stored type; EMPTY when the table does not exist
     * @throws SQLException if the catalog cannot be read
     */
    static Map<String, StoredColumn> columns(Connection connection, String table) throws SQLException {
        Map<String, StoredColumn> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1), new StoredColumn(rows.getString(2), rows.getString(3)));
                }
            }
        }
        return columns;
    }

    /** {@return whether an extension is installed in the current database} */
    static boolean hasExtension(Connection connection, String extension) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(EXTENSION)) {
            statement.setString(1, extension);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Whether a stored column has the type the model declares.
     *
     * @param modelType the model's SQL type, as written in DDL
     * @param stored    what the catalog reports
     * @return true when they denote the same PostgreSQL type
     */
    static boolean matches(String modelType, StoredColumn stored) {
        String expected = modelType.trim().toLowerCase(Locale.ROOT);
        if (expected.startsWith("geometry")) {
            // PostGIS types are USER-DEFINED; the subtype/SRID modifier is not in information_schema.
            return USER_DEFINED.equals(stored.dataType()) && "geometry".equals(stored.udtName());
        }
        int modifier = expected.indexOf('(');
        if (modifier >= 0) {
            // NUMERIC(10,2) and NUMERIC are the same data_type; precision is not what we check.
            expected = expected.substring(0, modifier).trim();
        }
        expected = CATALOG_SPELLING.getOrDefault(expected, expected);
        return expected.equals(stored.dataType());
    }
}
