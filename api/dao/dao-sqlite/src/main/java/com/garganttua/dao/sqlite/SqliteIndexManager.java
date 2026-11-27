package com.garganttua.dao.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import javax.sql.DataSource;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.sql.SqlIndexPlanner;
import com.garganttua.dao.sql.SqlIndexPlanner.SqlIndex;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/**
 * Creates the indexes a domain declares, on SQLite — with the contract of {@code mongodb.index.auto}.
 *
 * <p>
 * A standard index becomes a B-tree on its columns — a decimal one on its numeric value, the value the DAO compares
 * it by, so that a unique decimal refuses {@code 1.5} after {@code 1.50} as MongoDB does — and a unique one is
 * restricted to the rows where the field is set, so that any number of entities may leave it unset. An index already
 * present under its name is left as is; one that cannot be created (duplicates already stored for a unique index) is
 * reported with the query listing the offending rows.
 * </p>
 *
 * <p>
 * A geo or text index has no SQLite counterpart that would give the same answers: {@code $geoWithin}, {@code $near}
 * and {@code $text} are evaluated by Java functions over the rows, and need no index to answer. The declaration is
 * accepted — a text index still chooses the field {@code $text} searches — and nothing is created for it.
 * </p>
 */
final class SqliteIndexManager {

    private static final Logger log = Logger.getLogger(SqliteIndexManager.class);
    private static final String DOMAIN = "Domain '";
    private static final String ON_FIELD = "' on field '";

    private final SqliteIndexMode mode;
    private final SqliteSchemaMode schemaMode;

    SqliteIndexManager(SqliteIndexMode mode, SqliteSchemaMode schemaMode) {
        this.mode = mode == null ? SqliteIndexMode.CREATE : mode;
        this.schemaMode = schemaMode == null ? SqliteSchemaMode.CREATE : schemaMode;
    }

    void ensure(DataSource dataSource, SqlTable table, String domainName, List<SqlIndexPlanner.Planned> plans)
            throws ApiException {
        if (this.mode == SqliteIndexMode.NONE || plans.isEmpty()) {
            return;
        }
        Map<String, String> stored;
        try (Connection connection = dataSource.getConnection()) {
            stored = stored(connection);
        } catch (SQLException e) {
            fail(DOMAIN + domainName + "': the indexes it declares could not be read from the database ("
                    + e.getMessage() + "), so none of them was created.", e);
            return;
        }
        for (SqlIndexPlanner.Planned plan : plans) {
            if (plan.index().isEmpty()) {
                fail(about(domainName, plan.rule().name(), String.valueOf(plan.rule().field())) + " is not created — "
                        + plan.refusal() + ".", null);
            } else if (plan.index().get().kind() == SqlIndexPlanner.Kind.STANDARD) {
                ensureOne(dataSource, table, domainName, plan.index().get(), stored);
            } else {
                log.debug("Domain '{}': {} index '{}' needs nothing on SQLite — the filters it serves are evaluated "
                        + "row by row", domainName, plan.index().get().kind(), plan.index().get().name());
            }
        }
    }

    private static Map<String, String> stored(Connection connection) throws SQLException {
        Map<String, String> indexes = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'index'")) {
            while (rs.next()) {
                indexes.put(rs.getString(1), rs.getString(2));
            }
        }
        return indexes;
    }

    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private void ensureOne(DataSource dataSource, SqlTable table, String domainName, SqlIndex index,
            Map<String, String> stored) throws ApiException {
        String create = createSql(table, index);
        if (stored.containsKey(index.name())) {
            log.debug("Domain '{}': index '{}' already in place", domainName, index.name());
            return;
        }
        if (this.schemaMode == SqliteSchemaMode.VALIDATE) {
            fail(about(domainName, index.name(), index.field()) + " does not exist, and schema mode VALIDATE issues no "
                    + "DDL. Apply it through your migration process: " + create + ";", null);
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(create);
            log.info("Domain '{}': created index '{}' ({})", domainName, index.name(), create);
        } catch (SQLException e) {
            boolean duplicates = e instanceof SQLiteException sqlite
                    && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;
            fail(duplicates ? duplicateMessage(domainName, index)
                    : about(domainName, index.name(), index.field()) + " could not be created (" + create + "): "
                            + e.getMessage(), e);
        }
    }

    static String createSql(SqlTable table, SqlIndex index) {
        StringJoiner keys = new StringJoiner(", ", "(", ")");
        for (String name : index.columns()) {
            keys.add(key(table, index.table(), name));
        }
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS " + SqlNaming.quote(index.name())
                + " ON " + SqlNaming.quote(index.table()) + " " + keys + predicate(index);
    }

    /** A column as the DAO compares it: a decimal by its numeric value, anything else as stored. */
    private static String key(SqlTable table, String tableName, String name) {
        Optional<SqlColumn> column = columnsOf(table, tableName).stream().filter(c -> c.name().equals(name))
                .findFirst();
        String quoted = SqlNaming.quote(name);
        return column.map(c -> SqliteDialect.INSTANCE.scalarValue(quoted, c)).orElse(quoted);
    }

    private static List<SqlColumn> columnsOf(SqlTable table, String tableName) {
        if (table.name().equals(tableName)) {
            return table.columns();
        }
        return table.allChildren().stream().filter(c -> c.name().equals(tableName)).findFirst()
                .map(SqlChildTable::valueColumns).orElse(List.of());
    }

    private static String predicate(SqlIndex index) {
        return index.predicateColumn() == null ? ""
                : " WHERE " + SqlNaming.quote(index.predicateColumn()) + " IS NOT NULL";
    }

    private static String duplicateMessage(String domainName, SqlIndex index) {
        StringJoiner columns = new StringJoiner(", ");
        index.columns().forEach(c -> columns.add(SqlNaming.quote(c)));
        return DOMAIN + domainName + "': unique index '" + index.name() + ON_FIELD + index.field()
                + "' could NOT be created — table '" + index.table() + "' already holds duplicate values for it. "
                + "The uniqueness is therefore NOT enforced by the database. List the offending rows with: SELECT "
                + columns + ", count(*) FROM " + SqlNaming.quote(index.table()) + predicate(index) + " GROUP BY "
                + columns + " HAVING count(*) > 1 — then merge or delete them and restart.";
    }

    private static String about(String domainName, String index, String field) {
        return DOMAIN + domainName + "': index '" + index + ON_FIELD + field + "'";
    }

    private void fail(String reason, Exception cause) throws ApiException {
        if (this.mode == SqliteIndexMode.STRICT) {
            throw new ApiException(reason + " (sqlite.index.auto=strict refuses to start on this.)", cause);
        }
        log.warn("{}", reason);
    }
}
