package com.garganttua.dao.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlDdl;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/**
 * Creates or checks the tables of a domain on SQLite.
 *
 * <p>
 * In {@link SqliteSchemaMode#CREATE} the tables are created when missing and a column the model gained is added —
 * inside one {@code IMMEDIATE} transaction, so that two processes registering the same domain on one database file
 * do it one after the other. A column stored with another type is reported and left as is: this DAO never retypes nor
 * drops. In {@link SqliteSchemaMode#VALIDATE} no DDL is issued: a missing table or column, or a column of another
 * type, refuses the start, with the statement that would fix it.
 * </p>
 */
final class SqliteSchemaManager {

    private static final Logger log = Logger.getLogger(SqliteSchemaManager.class);

    /** The columns of a child table that hold its structure, not a field: they cannot be added afterwards. */
    private static final Set<String> STRUCTURAL = Set.of(SqlChildTable.ID, SqlChildTable.OWNER, SqlChildTable.PARENT,
            SqlChildTable.ORD, SqlChildTable.KEY);

    private final DataSource dataSource;
    private final SqliteSchemaMode mode;

    SqliteSchemaManager(DataSource dataSource, SqliteSchemaMode mode) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mode = mode == null ? SqliteSchemaMode.CREATE : mode;
    }

    /** A table as the model expects it: its columns and their declared types, and the statement creating it. */
    private record Expected(String name, Map<String, String> columns, Set<String> structural, String create) {
    }

    void ensure(SqlTable table) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            SqliteFunctions.ready(connection);
            if (this.mode == SqliteSchemaMode.VALIDATE) {
                for (Expected expected : expected(table)) {
                    validate(connection, expected);
                }
            } else {
                create(connection, table);
            }
            log.debug("Schema of table {} ensured in {} mode", table.name(), this.mode);
        } catch (SQLException e) {
            throw new ApiException("Could not ensure the schema of table '" + table.name() + "' in " + this.mode
                    + " mode: " + e.getMessage(), e);
        }
    }

    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private static void create(Connection connection, SqlTable table) throws SQLException, ApiException {
        boolean autoCommit = connection.getAutoCommit();
        SqliteDialect.INSTANCE.beginTransaction(connection, true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SqlDdl.create(table)) {
                statement.execute(ddl);
            }
            for (Expected expected : expected(table)) {
                migrate(connection, expected);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private static void migrate(Connection connection, Expected expected) throws SQLException, ApiException {
        Map<String, String> stored = stored(connection, expected.name());
        for (Map.Entry<String, String> column : expected.columns().entrySet()) {
            String actual = stored.get(column.getKey());
            if (actual == null) {
                if (expected.structural().contains(column.getKey())) {
                    throw new ApiException("Table '" + expected.name() + "' exists without its structural column '"
                            + column.getKey() + "', which cannot be added to an existing table: it was not created "
                            + "by this DAO, or by an incompatible version. Drop or rename it so it can be recreated: "
                            + expected.create());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + SqlNaming.quote(expected.name()) + " ADD COLUMN "
                            + SqlNaming.quote(column.getKey()) + " " + column.getValue());
                }
                log.info("Added column {}.{} {} — the model gained this field", SqlNaming.quote(expected.name()),
                        SqlNaming.quote(column.getKey()), column.getValue());
            } else if (!matches(column.getValue(), actual)) {
                log.warn("Column {}.{} is stored as {} but the model expects {}: left as is — CREATE mode never "
                        + "retypes a column; migrate it deliberately if the model is right",
                        SqlNaming.quote(expected.name()), SqlNaming.quote(column.getKey()), actual, column.getValue());
            }
        }
    }

    private static void validate(Connection connection, Expected expected) throws SQLException, ApiException {
        Map<String, String> stored = stored(connection, expected.name());
        if (stored.isEmpty()) {
            throw new ApiException("Table '" + expected.name() + "' does not exist, and schema mode VALIDATE issues "
                    + "no DDL. Create it through your migration process: " + expected.create() + ";");
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> column : expected.columns().entrySet()) {
            String actual = stored.get(column.getKey());
            if (actual == null) {
                problems.add("column '" + column.getKey() + "' is missing (expected " + column.getValue() + ")");
            } else if (!matches(column.getValue(), actual)) {
                problems.add("column '" + column.getKey() + "' is " + actual + ", expected " + column.getValue());
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException("Table '" + expected.name() + "' does not fit the model of its domain: "
                    + String.join("; ", problems) + ". Expected: " + expected.create() + ";");
        }
    }

    private static boolean matches(String expected, String actual) {
        return expected.equalsIgnoreCase(actual.trim());
    }

    /** The columns SQLite holds for a table, by name, with their declared types; empty when it does not exist. */
    private static Map<String, String> stored(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, type FROM pragma_table_info(?)")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getString(1), rs.getString(2).toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    private static List<Expected> expected(SqlTable table) {
        List<Expected> tables = new ArrayList<>();
        Map<String, String> main = new LinkedHashMap<>();
        for (SqlColumn column : table.columns()) {
            main.put(column.name(), SqliteDialect.declared(column));
        }
        tables.add(new Expected(table.name(), main, Set.of(table.id().name()), SqlDdl.createMain(table)));
        for (SqlChildTable child : table.allChildren()) {
            Map<String, String> columns = new LinkedHashMap<>();
            if (child.hasChildren()) {
                columns.put(SqlChildTable.ID, "INTEGER");
            }
            columns.put(SqlChildTable.OWNER, "TEXT");
            if (child.nested()) {
                columns.put(SqlChildTable.PARENT, "INTEGER");
            }
            if (child.ordered()) {
                columns.put(SqlChildTable.ORD, "INTEGER");
            } else {
                columns.put(SqlChildTable.KEY, SqliteDialect.INSTANCE.ddlType(
                        SqlTypes.sqlTypeOf(child.keyType()).orElse(SqlTypes.TEXT)));
            }
            for (SqlColumn column : child.valueColumns()) {
                columns.put(column.name(), SqliteDialect.declared(column));
            }
            tables.add(new Expected(child.name(), columns, STRUCTURAL, SqlDdl.createChild(table, child)));
        }
        return tables;
    }
}
