package com.garganttua.dao.postgresql;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.postgresql.PgSchemaInspector.StoredColumn;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * Makes the database fit a domain's table model before the DAO touches it.
 *
 * <p>
 * MongoDB needs nothing of the kind: a collection springs into existence on first insert and takes
 * any document shape. A relational store has neither property, so this is what lets an application
 * start on an EMPTY PostgreSQL with no manual step ({@link SchemaMode#CREATE}), and what keeps it
 * starting after its DTOs gained fields — while never destroying data. Where the schema is owned by
 * a reviewed migration process instead, {@link SchemaMode#VALIDATE} checks and refuses to start with
 * the exact DDL that is missing.
 * </p>
 *
 * <h2>CREATE mode</h2>
 * <ul>
 * <li>Additive only: missing tables and columns are created; nothing is dropped, renamed or retyped.
 * A column whose stored type differs from the model is reported as a WARN and left alone — retyping
 * converts data, which is a human's decision.</li>
 * <li>One transaction: PostgreSQL DDL is transactional, so a failure leaves nothing
 * half-created.</li>
 * <li>Serialised across instances: two instances starting together would run {@code CREATE TABLE IF
 * NOT EXISTS} concurrently, and PostgreSQL can then fail one of them with a unique violation on its
 * own catalog ({@code pg_type}) — {@code IF NOT EXISTS} is not atomic against a concurrent create.
 * A transaction-scoped advisory lock keyed on the table name makes the second instance wait, then
 * find everything in place.</li>
 * </ul>
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // accessor style: a constant/field and the method using it share a name
public final class PgSchemaManager {

    private static final Logger log = Logger.getLogger(PgSchemaManager.class);

    /** Namespaces the advisory-lock keys, so they do not collide with an application's own locks. */
    private static final String LOCK_NAMESPACE = "garganttua-dao-postgresql:";

    private static final String LOCK = "SELECT pg_advisory_xact_lock(?)";

    private final DataSource dataSource;
    private final SchemaMode mode;

    /**
     * @param dataSource the database
     * @param mode       who owns the DDL; {@code null} means the default, {@link SchemaMode#CREATE}
     */
    public PgSchemaManager(DataSource dataSource, SchemaMode mode) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mode = mode == null ? SchemaMode.CREATE : mode;
    }

    /**
     * Makes the database fit the table model, per mode.
     *
     * @param table the model of one domain
     * @throws ApiException in CREATE mode, when the schema cannot be created (PostGIS missing, a
     *                      structural column missing from an existing child table, a database
     *                      error); in VALIDATE mode, when anything is missing or mistyped
     */
    public void ensure(PgTable table) throws ApiException {
        Objects.requireNonNull(table, "table");
        try (Connection connection = dataSource.getConnection()) {
            if (mode == SchemaMode.VALIDATE) {
                PgSchemaValidator.validate(connection, table);
            } else {
                inTransaction(connection, () -> create(connection, table));
            }
            log.debug("Schema of table {} ensured in {} mode", table.name(), mode);
        } catch (SQLException e) {
            throw new ApiException("Could not ensure the schema of table '" + table.name() + "' in "
                    + mode + " mode: " + e.getMessage(), e);
        }
    }

    /** A unit of schema work that may fail with either a database or an API error. */
    @FunctionalInterface
    private interface Work {
        void run() throws SQLException, ApiException;
    }

    private static void inTransaction(Connection connection, Work work) throws SQLException, ApiException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
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
    private static void create(Connection connection, PgTable table) throws SQLException, ApiException {
        lock(connection, table.name());
        if (table.needsPostgis()) {
            // Last lock taken, always: no two sessions can then wait on each other's table lock.
            lock(connection, PgSchemaExpectation.POSTGIS);
            createPostgis(connection, table);
        }
        try (Statement statement = connection.createStatement()) {
            for (String ddl : PgSchemaExpectation.createStatements(table)) {
                if (!PgSchemaExpectation.CREATE_POSTGIS.equals(ddl)) {
                    statement.execute(ddl);
                }
            }
        }
        for (PgSchemaExpectation.Table expected : PgSchemaExpectation.of(table)) {
            migrate(connection, expected);
        }
    }

    private static void createPostgis(Connection connection, PgTable table) throws ApiException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(PgSchemaExpectation.CREATE_POSTGIS);
        } catch (SQLException e) {
            throw new ApiException("Table '" + table.name() + "' has geometry field(s) "
                    + PgSchemaExpectation.geometryFields(table) + ", stored as PostGIS geometry columns, "
                    + "which need the PostGIS extension — and it could not be created ("
                    + e.getMessage() + "). Install PostGIS on the database server (e.g. the "
                    + "postgresql-<version>-postgis-3 package, or the postgis/postgis Docker image), then "
                    + "have a superuser run '" + PgSchemaExpectation.CREATE_POSTGIS + ";' in this database, "
                    + "or grant the application role the right to create it.", e);
        }
    }

    /**
     * Adds the model columns an existing table lacks — the DTO gained fields since it was created.
     */
    private static void migrate(Connection connection, PgSchemaExpectation.Table expected)
            throws SQLException, ApiException {
        Map<String, StoredColumn> stored = PgSchemaInspector.columns(connection, expected.name());
        for (PgSchemaExpectation.Column column : expected.columns()) {
            StoredColumn actual = stored.get(column.name());
            if (actual == null) {
                addColumn(connection, expected, column);
            } else if (!PgSchemaInspector.matches(column.sqlType(), actual)) {
                log.warn("Column {}.{} is stored as {} but the model expects {}: left as is — CREATE mode "
                        + "never retypes a column; migrate it deliberately if the model is right",
                        PgNaming.quote(expected.name()), PgNaming.quote(column.name()), actual.describe(),
                        column.sqlType());
            }
        }
    }

    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private static void addColumn(Connection connection, PgSchemaExpectation.Table expected,
            PgSchemaExpectation.Column column) throws SQLException, ApiException {
        if (!column.addable()) {
            throw new ApiException("Table '" + expected.name() + "' exists without its structural column '"
                    + column.name() + "', which cannot be added to an existing table: it was not created "
                    + "by this DAO, or by an incompatible version. Drop or rename it so it can be recreated: "
                    + expected.create());
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(PgSchemaExpectation.addColumn(expected, column));
        }
        log.info("Added column {}.{} {} — the model gained this field", PgNaming.quote(expected.name()),
                PgNaming.quote(column.name()), column.sqlType());
    }

    private static void lock(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK)) {
            statement.setLong(1, lockKey(name));
            statement.execute();
        }
    }

    /**
     * A stable 64-bit key for a name: every instance, every JVM, every restart must derive the SAME
     * key, which rules out {@code String.hashCode()} widening (32 bits, collision-prone).
     */
    static long lockKey(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((LOCK_NAMESPACE + name).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
