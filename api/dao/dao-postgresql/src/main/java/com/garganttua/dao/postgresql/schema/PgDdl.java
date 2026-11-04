package com.garganttua.dao.postgresql.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The {@code CREATE TABLE} statements of a table model.
 *
 * <p>
 * Child tables reference their owner with {@code ON DELETE CASCADE}: deleting an entity removes its
 * collections in the same statement, so a delete can never leave orphan rows behind. A top-level child
 * table's primary key is {@code (_owner, _ord)} for lists and sets and {@code (_owner, _key)} for maps,
 * which indexes its owner too; a NESTED table's is {@code (_parent, …)}, so it gets an index of its own on
 * {@code _owner} ({@link #ownerIndex}) — without it, the cascade that deletes an entity scans every nested
 * table in full, once per entity. References to OTHER domains carry no foreign key:
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
        for (PgChildTable child : table.allChildren()) {
            statements.add(createChild(table, child));
            if (child.nested()) {
                statements.add(ownerIndex(child));
            }
        }
        return statements;
    }

    /**
     * The index on {@code _owner} of a nested child table, whose primary key starts with {@code _parent}:
     * what the {@code ON DELETE CASCADE} from the root row looks rows up by. Idempotent, so an existing
     * table gets it at the next start in CREATE mode.
     *
     * @param child a nested child table
     * @return the statement
     */
    public static String ownerIndex(PgChildTable child) {
        return "CREATE INDEX IF NOT EXISTS " + PgNaming.quote(PgNaming.index(child.name(), PgChildTable.OWNER))
                + " ON " + PgNaming.quote(child.name()) + " (" + PgNaming.quote(PgChildTable.OWNER) + ")";
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

    /**
     * The CREATE TABLE of one child table, at any depth.
     *
     * <p>
     * A table with children gets an identity {@code _id} — the key its children's {@code _parent}
     * references. Every table keeps {@code _owner}, the ROOT's id, so one query per table loads a whole
     * page's descendants; a nested table adds {@code _parent}. Both cascade on delete: rewriting or
     * deleting an entity takes its entire tree with it, in one statement.
     * </p>
     *
     * @param table the root table
     * @param child the child table
     * @return the statement
     */
    public static String createChild(PgTable table, PgChildTable child) {
        StringJoiner columns = new StringJoiner(", ");
        if (child.hasChildren()) {
            columns.add(PgNaming.quote(PgChildTable.ID) + " BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE");
        }
        columns.add(PgNaming.quote(PgChildTable.OWNER) + " TEXT NOT NULL REFERENCES "
                + PgNaming.quote(table.name()) + " (" + PgNaming.quote(table.id().name()) + ") ON DELETE CASCADE");
        if (child.nested()) {
            columns.add(PgNaming.quote(PgChildTable.PARENT) + " BIGINT NOT NULL REFERENCES "
                    + PgNaming.quote(child.parent()) + " (" + PgNaming.quote(PgChildTable.ID) + ") ON DELETE CASCADE");
        }
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
        String scope = child.nested() ? PgChildTable.PARENT : PgChildTable.OWNER;
        columns.add("PRIMARY KEY (" + PgNaming.quote(scope) + ", " + PgNaming.quote(position) + ")");
        return "CREATE TABLE IF NOT EXISTS " + PgNaming.quote(child.name()) + " (" + columns + ")";
    }
}
