package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * What the database must contain for one table model: every table, with every column and its type.
 *
 * <p>
 * Both schema modes compare the catalog against THIS list, so CREATE and VALIDATE can never disagree
 * on what "complete" means. It includes the fixed columns of child tables ({@code _owner},
 * {@code _ord}/{@code _key}): VALIDATE must check them too, and CREATE must know it cannot add them
 * after the fact — {@code _owner} is {@code NOT NULL REFERENCES …}, which an {@code ADD COLUMN} on a
 * populated table cannot satisfy.
 * </p>
 */
final class PgSchemaExpectation {

    /** The PostgreSQL extension geometry columns need. */
    static final String POSTGIS = "postgis";

    /** The statement installing it — idempotent, like every statement the manager issues. */
    static final String CREATE_POSTGIS = "CREATE EXTENSION IF NOT EXISTS " + POSTGIS;

    private static final String INTEGER = "INTEGER";

    /**
     * One expected column.
     *
     * @param name    the column name, unquoted
     * @param sqlType the model's SQL type
     * @param addable whether an additive migration may add it ({@code false} for the structural
     *                child-table columns)
     */
    record Column(String name, String sqlType, boolean addable) {
    }

    /**
     * One expected table.
     *
     * @param name    the table name, unquoted
     * @param create  its {@code CREATE TABLE IF NOT EXISTS} statement
     * @param columns its columns, in DDL order
     */
    record Table(String name, String create, List<Column> columns) {
    }

    private PgSchemaExpectation() {
        // Static helpers
    }

    /**
     * Every table of a model, main table first.
     *
     * @param table the model
     * @return the expected tables
     */
    static List<Table> of(PgTable table) {
        List<Table> tables = new ArrayList<>();
        List<Column> main = new ArrayList<>();
        for (PgColumn column : table.columns()) {
            main.add(new Column(column.name(), column.sqlType(), column.kind() != PgColumnKind.ID));
        }
        tables.add(new Table(table.name(), PgDdl.createMain(table), main));
        for (PgChildTable child : table.children()) {
            tables.add(new Table(child.name(), PgDdl.createChild(table, child), childColumns(child)));
        }
        return tables;
    }

    private static List<Column> childColumns(PgChildTable child) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column(PgChildTable.OWNER, PgTypes.TEXT, false));
        if (child.ordered()) {
            columns.add(new Column(PgChildTable.ORD, INTEGER, false));
        } else {
            columns.add(new Column(PgChildTable.KEY,
                    PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT), false));
        }
        for (PgColumn column : child.valueColumns()) {
            columns.add(new Column(column.name(), column.sqlType(), true));
        }
        return columns;
    }

    /** {@return the geometry fields of a model, by dotted path — for an actionable PostGIS message} */
    static List<String> geometryFields(PgTable table) {
        List<String> fields = new ArrayList<>();
        table.columns().stream().filter(c -> c.kind() == PgColumnKind.GEOMETRY)
                .forEach(c -> fields.add(c.dottedPath()));
        for (PgChildTable child : table.children()) {
            child.valueColumns().stream().filter(c -> c.kind() == PgColumnKind.GEOMETRY)
                    .forEach(c -> fields.add(child.dottedPath() + "[]"
                            + (c.fieldPath().isEmpty() ? "" : "." + c.dottedPath())));
        }
        return fields;
    }

    /** {@return the additive statement adding a missing column} */
    static String addColumn(Table table, Column column) {
        return "ALTER TABLE " + PgNaming.quote(table.name()) + " ADD COLUMN IF NOT EXISTS "
                + PgNaming.quote(column.name()) + " " + column.sqlType();
    }

    /**
     * {@return the statement that WOULD retype a column} Only ever shown to a human in a VALIDATE
     * report — never executed: retyping converts (or loses) stored data and is a reviewed decision.
     */
    static String alterType(Table table, Column column) {
        String quoted = PgNaming.quote(column.name());
        return "ALTER TABLE " + PgNaming.quote(table.name()) + " ALTER COLUMN " + quoted + " TYPE "
                + column.sqlType() + " USING " + quoted + "::" + column.sqlType();
    }

    /**
     * The statements CREATE mode starts with, before any migration: the extension when the model
     * needs it, then the tables.
     *
     * @param table the model
     * @return the statements, in execution order
     */
    static List<String> createStatements(PgTable table) {
        List<String> statements = new ArrayList<>();
        if (table.needsPostgis()) {
            statements.add(CREATE_POSTGIS);
        }
        statements.addAll(PgDdl.create(table));
        return statements;
    }
}
