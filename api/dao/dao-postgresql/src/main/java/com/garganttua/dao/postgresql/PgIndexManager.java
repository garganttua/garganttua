package com.garganttua.dao.postgresql;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.postgresql.PgIndexPlanner.Planned;
import com.garganttua.dao.postgresql.PgIndexSpec.PgStoredIndex;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * Creates, at startup, the indexes a domain declares — and only those. The PostgreSQL twin of the
 * MongoDB DAO's {@code MongoIndexManager}, with the same contract.
 *
 * <h2>Additive only</h2>
 * <p>
 * Nothing is ever dropped, renamed or rebuilt. An index already in place under the declared name is
 * kept; when its definition differs from the declaration, a WARN names both sides. An index that
 * already covers the same keys under another name is kept too, and nothing is added beside it.
 * </p>
 *
 * <h2>Failure is not a reason to stay down</h2>
 * <p>
 * A table that already holds duplicates refuses a unique index ({@code 23505}). In
 * {@link PgIndexMode#CREATE} that is a WARN naming the domain, the field and a query listing the
 * offending rows, and the application starts — the constraint was not held before either. Only
 * {@link PgIndexMode#STRICT} refuses to start. A declaration PostgreSQL cannot hold (a unique index on
 * a collection, a field that maps no column) goes through the same policy.
 * </p>
 *
 * <h2>Under {@link SchemaMode#VALIDATE}</h2>
 * <p>
 * No DDL is issued. A missing index is reported with the exact statement that would create it — a
 * WARN, or a refusal to start in {@link PgIndexMode#STRICT} — for the migration process to apply.
 * </p>
 *
 * <h2>Concurrent instances</h2>
 * <p>
 * Each index is created in its own transaction, under a transaction-scoped advisory lock on its
 * name: {@code CREATE INDEX IF NOT EXISTS} is no more atomic against a concurrent create than
 * {@code CREATE TABLE IF NOT EXISTS} is. The second instance waits, then finds the index in place.
 * One index per transaction also means a refused one does not take the others with it.
 * </p>
 */
final class PgIndexManager {

    private static final Logger log = Logger.getLogger(PgIndexManager.class);

    /** PostgreSQL's unique-violation SQLSTATE — the one failure that means "the data, not the DDL". */
    static final String UNIQUE_VIOLATION = "23505";

    private static final String DOMAIN = "Domain '";
    private static final String ON_FIELD = "' on field '";

    private static final String LOCK = "SELECT pg_advisory_xact_lock(?)";

    /** Every index of the current schema on the given tables, or under the given names. */
    private static final String STORED_SQL = "SELECT c.relname, t.relname, am.amname, ix.indisunique, "
            + "pg_get_expr(ix.indpred, ix.indrelid), "
            + "ARRAY(SELECT pg_get_indexdef(ix.indexrelid, k, true) FROM generate_series(1, ix.indnkeyatts) AS k) "
            + "FROM pg_index ix JOIN pg_class c ON c.oid = ix.indexrelid JOIN pg_class t ON t.oid = ix.indrelid "
            + "JOIN pg_am am ON am.oid = c.relam JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = current_schema() AND (t.relname = ANY(?) OR c.relname = ANY(?))";

    private final PgIndexMode mode;
    private final SchemaMode schemaMode;

    /**
     * @param mode       what the manager may do, and what a failure costs; {@code null} means
     *                   {@link PgIndexMode#CREATE}
     * @param schemaMode {@link SchemaMode#VALIDATE} forbids any DDL; {@code null} means
     *                   {@link SchemaMode#CREATE}
     */
    PgIndexManager(PgIndexMode mode, SchemaMode schemaMode) {
        this.mode = mode == null ? PgIndexMode.CREATE : mode;
        this.schemaMode = schemaMode == null ? SchemaMode.CREATE : schemaMode;
    }

    /**
     * Makes the database carry the declared indexes. A domain that declares none costs nothing.
     *
     * @param dataSource the database
     * @param domainName the domain, named in every message
     * @param plans      the planned declarations
     * @throws ApiException in {@link PgIndexMode#STRICT} only, when an index cannot be held
     */
    void ensure(DataSource dataSource, String domainName, List<Planned> plans) throws ApiException {
        Objects.requireNonNull(dataSource, "dataSource");
        if (this.mode == PgIndexMode.NONE || plans == null || plans.isEmpty()) {
            return;
        }
        List<PgIndexSpec> specs = new ArrayList<>();
        for (Planned plan : plans) {
            if (plan.spec().isPresent()) {
                specs.add(plan.spec().get());
            } else {
                fail(about(domainName, plan.rule().name(), String.valueOf(plan.rule().field())) + " is not created — "
                        + plan.refusal() + ".", null);
            }
        }
        if (specs.isEmpty()) {
            return;
        }
        Optional<List<PgStoredIndex>> stored = read(dataSource, domainName, specs);
        if (stored.isEmpty()) {
            return;
        }
        for (PgIndexSpec spec : specs) {
            ensureOne(dataSource, domainName, spec, stored.get());
        }
    }

    private Optional<List<PgStoredIndex>> read(DataSource dataSource, String domainName, List<PgIndexSpec> specs)
            throws ApiException {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        specs.forEach(s -> {
            tables.add(s.table());
            names.add(s.name());
        });
        try (Connection connection = dataSource.getConnection()) {
            return Optional.of(stored(connection, tables, names));
        } catch (SQLException e) {
            fail(DOMAIN + domainName + "': the indexes it declares could not be read from the database ("
                    + e.getMessage() + "), so none of them was created.", e);
            return Optional.empty();
        }
    }

    /**
     * The indexes of the current schema on {@code tables}, or named in {@code names}, as the catalog
     * renders them back.
     *
     * @param connection the connection
     * @param tables     table names, unquoted
     * @param names      index names, unquoted
     * @return the stored indexes
     * @throws SQLException if the catalog cannot be read
     */
    static List<PgStoredIndex> stored(Connection connection, Set<String> tables, Set<String> names)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(STORED_SQL)) {
            statement.setArray(1, connection.createArrayOf("text", tables.toArray()));
            statement.setArray(2, connection.createArrayOf("text", names.toArray()));
            List<PgStoredIndex> stored = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    stored.add(new PgStoredIndex(rs.getString(1), rs.getString(2), rs.getString(3),
                            keys(rs.getArray(6)), rs.getBoolean(4), rs.getString(5)));
                }
            }
            return stored;
        }
    }

    private static List<String> keys(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private void ensureOne(DataSource dataSource, String domainName, PgIndexSpec spec, List<PgStoredIndex> stored)
            throws ApiException {
        Optional<PgStoredIndex> sameName = stored.stream().filter(i -> i.name().equals(spec.name())).findFirst();
        if (sameName.isPresent()) {
            reportExisting(domainName, spec, sameName.get());
            return;
        }
        Optional<PgStoredIndex> covering = stored.stream().filter(spec::matches).findFirst();
        if (covering.isPresent()) {
            log.info("Domain '{}': index '{}' ({}) is already held by index '{}', left as is.", domainName,
                    spec.name(), spec.describe(), covering.get().name());
            return;
        }
        if (this.schemaMode == SchemaMode.VALIDATE) {
            fail(about(domainName, spec.name(), spec.field()) + " does not exist, and schema mode VALIDATE issues no DDL. Apply it through your migration "
                    + "process: " + spec.createSql() + ";", null);
            return;
        }
        create(dataSource, domainName, spec);
    }

    private static void reportExisting(String domainName, PgIndexSpec spec, PgStoredIndex stored) {
        if (spec.matches(stored)) {
            log.debug("Domain '{}': index '{}' already in place ({})", domainName, spec.name(), spec.describe());
            return;
        }
        log.warn("Domain '{}': index '{}' exists as [{}] but is declared as [{}] — left as is. This DAO never "
                + "alters an existing index: drop it deliberately if the declaration is the right one.",
                domainName, spec.name(), stored.describe(), spec.describe());
    }

    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private void create(DataSource dataSource, String domainName, PgIndexSpec spec) throws ApiException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(LOCK);
                    Statement statement = connection.createStatement()) {
                lock.setLong(1, PgSchemaManager.lockKey("index:" + spec.name()));
                lock.execute();
                statement.execute(spec.createSql());
                connection.commit();
                log.info("Domain '{}': created index '{}' ({})", domainName, spec.name(), spec.describe());
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            fail(UNIQUE_VIOLATION.equals(e.getSQLState()) ? duplicateMessage(domainName, spec)
                    : about(domainName, spec.name(), spec.field()) + " (" + spec.describe() + ") could not be created: "
                            + e.getMessage(), e);
        }
    }

    /**
     * The message for the failure an operator can act on: the table already holds the duplicates the
     * declared index refuses. It carries the query that lists them.
     */
    private static String duplicateMessage(String domainName, PgIndexSpec spec) {
        return DOMAIN + domainName + "': unique index '" + spec.name() + ON_FIELD + spec.field()
                + "' could NOT be created — table '" + spec.table() + "' already holds duplicate values for it. "
                + "The uniqueness is therefore NOT enforced by the database. List the offending rows with: "
                + spec.duplicateQuery() + " — then merge or delete them and restart.";
    }

    /** {@code Domain 'd': index 'i' on field 'f'} — how every message names what it is about. */
    private static String about(String domainName, String index, String field) {
        return DOMAIN + domainName + "': index '" + index + ON_FIELD + field + "'";
    }

    /** WARN and carry on, or stop — the single point where the mode decides. */
    private void fail(String reason, Exception cause) throws ApiException {
        if (this.mode == PgIndexMode.STRICT) {
            throw new ApiException(reason + " (postgresql.index.auto=strict refuses to start on this.)", cause);
        }
        log.warn("{}", reason);
    }
}
