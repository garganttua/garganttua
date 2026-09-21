package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.PgSchemaInspector.StoredColumn;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The VALIDATE mode: compares the database with the model and issues no DDL at all.
 *
 * <p>
 * It collects EVERY problem before failing. A schema reviewed and migrated by a separate process is
 * fixed in one pass only if the report lists everything at once — failing on the first missing
 * column would turn one deployment into as many round trips as there are gaps. The report ends with
 * the DDL that would close each gap, written exactly as CREATE mode would have run it.
 * </p>
 */
final class PgSchemaValidator {

    private final List<String> problems = new ArrayList<>();
    private final List<String> fixes = new ArrayList<>();

    private PgSchemaValidator() {
    }

    /**
     * Checks a model against the database.
     *
     * @param connection the connection
     * @param table      the model
     * @throws ApiException listing every problem and its fix, when the schema is incomplete
     * @throws SQLException if the catalog cannot be read
     */
    static void validate(Connection connection, PgTable table) throws ApiException, SQLException {
        PgSchemaValidator validator = new PgSchemaValidator();
        validator.checkExtension(connection, table);
        for (PgSchemaExpectation.Table expected : PgSchemaExpectation.of(table)) {
            validator.checkTable(expected, PgSchemaInspector.columns(connection, expected.name()));
        }
        if (!validator.problems.isEmpty()) {
            throw new ApiException(validator.report(table));
        }
    }

    private void checkExtension(Connection connection, PgTable table) throws SQLException {
        if (table.needsPostgis()
                && !PgSchemaInspector.hasExtension(connection, PgSchemaExpectation.POSTGIS)) {
            problems.add("extension '" + PgSchemaExpectation.POSTGIS + "' is not installed (geometry fields: "
                    + PgSchemaExpectation.geometryFields(table) + ")");
            fixes.add(PgSchemaExpectation.CREATE_POSTGIS + ";");
        }
    }

    private void checkTable(PgSchemaExpectation.Table expected, Map<String, StoredColumn> stored) {
        if (stored.isEmpty()) {
            problems.add("table " + PgNaming.quote(expected.name()) + " does not exist");
            fixes.add(expected.create() + ";");
            return;
        }
        for (PgSchemaExpectation.Column column : expected.columns()) {
            StoredColumn actual = stored.get(column.name());
            if (actual == null) {
                problems.add("column " + qualified(expected, column) + " does not exist");
                fixes.add(PgSchemaExpectation.addColumn(expected, column) + ";");
            } else if (!PgSchemaInspector.matches(column.sqlType(), actual)) {
                problems.add("column " + qualified(expected, column) + " is " + actual.describe()
                        + ", the model expects " + column.sqlType());
                fixes.add(PgSchemaExpectation.alterType(expected, column)
                        + "; -- review first: this converts the stored data");
            }
        }
    }

    private static String qualified(PgSchemaExpectation.Table table, PgSchemaExpectation.Column column) {
        return PgNaming.quote(table.name()) + "." + PgNaming.quote(column.name());
    }

    private String report(PgTable table) {
        StringBuilder message = new StringBuilder()
                .append("The database schema does not match the model of table '").append(table.name())
                .append("', and schema mode ").append(SchemaMode.VALIDATE)
                .append(" issues no DDL. ").append(problems.size()).append(" problem(s):");
        problems.forEach(p -> message.append("\n  - ").append(p));
        message.append("\nApply this DDL through your migration process, or switch to schema mode ")
                .append(SchemaMode.CREATE).append(" to let the DAO create what is missing:");
        fixes.forEach(f -> message.append("\n  ").append(f));
        return message.toString();
    }
}
