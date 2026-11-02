package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.core.observability.Logger;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The schema behind the list digests: one digest column per collection on the main row, and the
 * triggers that keep a digest from ever lying.
 *
 * <p>
 * The writer skips rewriting a collection whose digest has not changed (see
 * {@code PgWriteChildren#digest}). That is only safe if a stored digest always describes the rows
 * that are really there. The DAO keeps it true for its own writes; for every OTHER writer — a SQL
 * script, a migration, an instance still running a version without digests during a rolling
 * upgrade — a trigger on every child table, at every depth, sets the owner's digest to NULL whenever
 * a row is inserted, updated or deleted, and every digest of the table on a {@code TRUNCATE}. A NULL
 * digest is never equal to anything, so the next write through the DAO rewrites that collection.
 * The DAO's own writes set a transaction-local flag ({@link #FLAG}) that the trigger honours, so they
 * do not erase the digest they have just written.
 * </p>
 *
 * <p>
 * Under {@link SchemaMode#CREATE} the columns, the function and the triggers are created, additively.
 * Under {@link SchemaMode#VALIDATE} nothing is created: when any piece is missing, the digests are
 * simply not used — every write rewrites every collection, as before — and a WARN gives the DDL that
 * would turn them on. A deployment that validates its schema is never refused for lacking them.
 * </p>
 */
final class PgListDigests {

    private static final Logger log = Logger.getLogger(PgListDigests.class);

    /** The transaction-local setting the DAO raises before writing child rows itself. */
    static final String FLAG = "garganttua.dao_writes_lists";

    /** The statement raising it, for the current transaction only. */
    static final String RAISE_FLAG = "SELECT set_config('" + FLAG + "', 'on', true)";

    private static final String FUNCTION = "garganttua_list_digest_invalidate";
    private static final String DIGEST_PREFIX = "_digest";
    private static final String TRIGGER_SUFFIX = "digest";
    private static final String TRUNCATE_TRIGGER_SUFFIX = "digest_truncate";
    private static final String NULL_WHERE = "UPDATE %I.%I SET %I = NULL WHERE %I = $1";

    private static final String CREATE_FUNCTION = "CREATE OR REPLACE FUNCTION " + FUNCTION
            + "() RETURNS trigger LANGUAGE plpgsql AS $fn$\n"
            + "BEGIN\n"
            + "  IF current_setting('" + FLAG + "', true) = 'on' THEN RETURN NULL; END IF;\n"
            + "  IF TG_OP = 'TRUNCATE' THEN\n"
            + "    EXECUTE format('UPDATE %I.%I SET %I = NULL', TG_TABLE_SCHEMA, TG_ARGV[0], TG_ARGV[1]);\n"
            + "    RETURN NULL;\n"
            + "  END IF;\n"
            + "  IF TG_OP <> 'INSERT' THEN\n"
            + "    EXECUTE format('" + NULL_WHERE + "', TG_TABLE_SCHEMA, TG_ARGV[0], TG_ARGV[1], TG_ARGV[2])"
            + " USING OLD._owner;\n"
            + "  END IF;\n"
            + "  IF TG_OP <> 'DELETE' THEN\n"
            + "    EXECUTE format('" + NULL_WHERE + "', TG_TABLE_SCHEMA, TG_ARGV[0], TG_ARGV[1], TG_ARGV[2])"
            + " USING NEW._owner;\n"
            + "  END IF;\n"
            + "  RETURN NULL;\n"
            + "END $fn$";

    private static final String FUNCTION_EXISTS = "SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
            + "WHERE n.nspname = current_schema() AND p.proname = ?";

    private static final String TRIGGER_EXISTS = "SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = current_schema() AND c.relname = ? AND t.tgname = ?";

    private static final String COLUMN_EXISTS = "SELECT 1 FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?";

    private PgListDigests() {
    }

    /**
     * {@return the digest column of each top-level collection of a model, in declaration order}
     *
     * @param table the model
     */
    static Map<PgChildTable, String> columns(PgTable table) {
        Map<PgChildTable, String> columns = new LinkedHashMap<>();
        for (PgChildTable child : table.children()) {
            List<String> path = new ArrayList<>();
            path.add(DIGEST_PREFIX);
            path.addAll(child.absolutePath());
            columns.put(child, PgNaming.column(path));
        }
        return columns;
    }

    /**
     * Makes the digests usable, per schema mode.
     *
     * @param connection a connection, in autocommit or not — the caller's transaction is used as is
     * @param table      the model
     * @param mode       who owns the DDL
     * @return {@code true} when every column, the function and every trigger are in place — the
     *         writer may then skip unchanged collections
     * @throws SQLException when the catalog cannot be read, or CREATE mode cannot create a piece
     */
    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    static boolean ensure(Connection connection, PgTable table, SchemaMode mode) throws SQLException {
        if (table.children().isEmpty()) {
            return false;
        }
        List<String> missing = missing(connection, table);
        if (missing.isEmpty()) {
            return true;
        }
        if (mode == SchemaMode.VALIDATE) {
            log.warn("Table '{}': the list digests are not installed, so every write rewrites every collection of "
                    + "the entity. Schema mode VALIDATE issues no DDL; apply this to turn them on:\n  {}", table.name(),
                    String.join(";\n  ", missing) + ";");
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            for (String ddl : missing) {
                statement.execute(ddl);
            }
        }
        log.info("Table '{}': list digests installed ({} statement(s))", table.name(), missing.size());
        return true;
    }

    /** The DDL still to apply — empty when everything is in place. */
    private static List<String> missing(Connection connection, PgTable table) throws SQLException {
        List<String> ddl = new ArrayList<>();
        Map<PgChildTable, String> columns = columns(table);
        for (String column : columns.values()) {
            if (!exists(connection, COLUMN_EXISTS, table.name(), column)) {
                ddl.add("ALTER TABLE " + PgNaming.quote(table.name()) + " ADD COLUMN IF NOT EXISTS "
                        + PgNaming.quote(column) + " TEXT");
            }
        }
        if (!exists(connection, FUNCTION_EXISTS, FUNCTION)) {
            ddl.add(CREATE_FUNCTION);
        }
        for (Map.Entry<PgChildTable, String> top : columns.entrySet()) {
            for (PgChildTable child : subtree(top.getKey())) {
                triggers(connection, table, child, top.getValue(), ddl);
            }
        }
        return ddl;
    }

    private static void triggers(Connection connection, PgTable table, PgChildTable child, String digestColumn,
            List<String> ddl) throws SQLException {
        String arguments = "('" + table.name().replace("'", "''") + "', '" + digestColumn.replace("'", "''") + "', '"
                + table.id().name().replace("'", "''") + "')";
        String rowTrigger = PgNaming.index(child.name(), TRIGGER_SUFFIX);
        if (!exists(connection, TRIGGER_EXISTS, child.name(), rowTrigger)) {
            ddl.add("CREATE TRIGGER " + PgNaming.quote(rowTrigger) + " AFTER INSERT OR UPDATE OR DELETE ON "
                    + PgNaming.quote(child.name()) + " FOR EACH ROW EXECUTE FUNCTION " + FUNCTION + arguments);
        }
        String truncateTrigger = PgNaming.index(child.name(), TRUNCATE_TRIGGER_SUFFIX);
        if (!exists(connection, TRIGGER_EXISTS, child.name(), truncateTrigger)) {
            ddl.add("CREATE TRIGGER " + PgNaming.quote(truncateTrigger) + " AFTER TRUNCATE ON "
                    + PgNaming.quote(child.name()) + " FOR EACH STATEMENT EXECUTE FUNCTION " + FUNCTION + arguments);
        }
    }

    /** A top-level child table and every table below it. */
    private static List<PgChildTable> subtree(PgChildTable top) {
        List<PgChildTable> out = new ArrayList<>();
        out.add(top);
        for (PgChildTable child : top.children()) {
            out.addAll(subtree(child));
        }
        return out;
    }

    private static boolean exists(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
