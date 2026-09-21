package com.garganttua.dao.postgresql.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The {@code CREATE TABLE} statements of a table model.
 *
 * <p>
 * Child tables reference their owner with {@code ON DELETE CASCADE}: deleting an entity removes its
 * collections in the same statement, so a delete can never leave orphan rows behind. Their primary
 * key is {@code (_owner, _ord)} for lists and sets — which also indexes the owner lookup every read
 * performs — and {@code (_owner, _key)} for maps. References to OTHER domains carry no foreign key:
 * the target may live in another store, and MongoDB's DBRefs are not enforced either.
 * </p>
 *
 * <p>
 * Every statement is {@code IF NOT EXISTS}, so running them twice is harmless. They create only; the
 * additive migration of an existing table, the PostGIS extension and the validate mode belong to the
 * schema manager.
 * </p>
 */
public final class PgDdl {

    private PgDdl() {
        // Static helpers
    }

    /**
     * The statements creating a table and its child tables.
     *
     * @param table the model
     * @return the statements, main table first
     */
    public static List<String> create(PgTable table) {
        List<String> statements = new ArrayList<>();
        statements.add(createMain(table));
        for (PgChildTable child : table.children()) {
            statements.add(createChild(table, child));
        }
        return statements;
    }

    /** {@return the CREATE TABLE of the main table} */
    public static String createMain(PgTable table) {
        StringJoiner columns = new StringJoiner(", ");
        for (PgColumn column : table.columns()) {
            String definition = PgNaming.quote(column.name()) + " " + column.sqlType();
            if (column.kind() == PgColumnKind.ID) {
                definition += " PRIMARY KEY";
            }
            columns.add(definition);
        }
        return "CREATE TABLE IF NOT EXISTS " + PgNaming.quote(table.name()) + " (" + columns + ")";
    }

    /** {@return the CREATE TABLE of one child table} */
    public static String createChild(PgTable table, PgChildTable child) {
        StringJoiner columns = new StringJoiner(", ");
        columns.add(PgNaming.quote(PgChildTable.OWNER) + " TEXT NOT NULL REFERENCES "
                + PgNaming.quote(table.name()) + " (" + PgNaming.quote(table.id().name()) + ") ON DELETE CASCADE");
        String position;
        if (child.ordered()) {
            position = PgChildTable.ORD;
            columns.add(PgNaming.quote(PgChildTable.ORD) + " INTEGER NOT NULL");
        } else {
            position = PgChildTable.KEY;
            String keyType = PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT);
            columns.add(PgNaming.quote(PgChildTable.KEY) + " " + keyType + " NOT NULL");
        }
        for (PgColumn column : child.valueColumns()) {
            columns.add(PgNaming.quote(column.name()) + " " + column.sqlType());
        }
        columns.add("PRIMARY KEY (" + PgNaming.quote(PgChildTable.OWNER) + ", " + PgNaming.quote(position) + ")");
        return "CREATE TABLE IF NOT EXISTS " + PgNaming.quote(child.name()) + " (" + columns + ")";
    }
}
