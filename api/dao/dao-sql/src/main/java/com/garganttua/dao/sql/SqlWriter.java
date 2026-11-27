package com.garganttua.dao.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;


import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * The write side of the PostgreSQL DAO: one entity in, one main row plus its collection rows out.
 *
 * <p>
 * An upsert is the relational twin of MongoDB's {@code save()}: {@code INSERT … ON CONFLICT (id) DO
 * UPDATE} on the main row, then every collection REPLACED (see {@link SqlWriteChildren}) — every collection
 * that CHANGED, when the list digests are installed (the dialect's digest capability ({@link ISqlListDigests})): the digest of the rows a
 * collection would be written as is stored on the main row, and a collection whose digest is the stored
 * one is left as it is, rows, indexes and WAL untouched. It spans
 * several statements, so it is only atomic inside a transaction — which the CALLER owns: the writer
 * never commits nor rolls back, so the DAO can put a write and whatever surrounds it in one unit.
 * </p>
 *
 * <p>
 * Every value is a bind parameter converted by {@link SqlValues}; every identifier comes from the
 * {@link SqlTable} model, quoted. Nothing from the entity is ever concatenated into SQL.
 * </p>
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // accessor style: a constant/field and the method using it share a name
public final class SqlWriter {

    private static final Logger LOG = Logger.getLogger(SqlWriter.class);

    private final SqlTable table;
    /** The database's digest capability, when the table has collections and the database provides it. */
    private final Optional<ISqlListDigests> digests;
    private final SqlWriteReferences references;
    private final SqlWriteChildren children;
    private final Map<SqlChildTable, String> digestColumns;
    private final String upsertSql;
    private final String digestsSql;
    private final String deleteSql;

    /**
     * A writer that rewrites every collection at every write.
     *
     * @param table    the domain's table model
     * @param registry the shapes of the other domains, to read the uuid of a composed entity
     */
    public SqlWriter(SqlTable table, SqlSchemaRegistry registry) {
        this(table, registry, false);
    }

    /**
     * @param table    the domain's table model
     * @param registry the shapes of the other domains, to read the uuid of a composed entity
     * @param digests  whether the list digests are installed — the writer then rewrites only the
     *                 collections that changed
     */
    public SqlWriter(SqlTable table, SqlSchemaRegistry registry, boolean digests) {
        this.table = Objects.requireNonNull(table, "table");
        this.references = new SqlWriteReferences(table, Objects.requireNonNull(registry, "registry"));
        this.children = new SqlWriteChildren(table, references);
        this.digests = table.children().isEmpty() ? Optional.empty() : table.dialect().listDigests();
        this.digestColumns = digests && this.digests.isPresent() ? this.digests.get().columns(table) : Map.of();
        this.upsertSql = buildUpsertSql(table, this.digestColumns.values());
        this.digestsSql = this.digestColumns.isEmpty() ? "" : buildDigestsSql(table, this.digests.get(), this.digestColumns.values());
        this.deleteSql = buildDeleteSql(table, this.digests);
    }

    /**
     * Inserts or replaces the entity AND all its collections. The CALLER owns the transaction
     * (autocommit off): on failure, rolling it back leaves no partial write.
     *
     * @param connection the caller's connection
     * @param dto        the entity
     * @throws ApiException when the entity has no uuid, a value does not fit its column, or the
     *                      database refuses the write
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    public void upsert(Connection connection, Object dto) throws ApiException {
        Object id = idOf(dto, "save");
        Map<SqlChildTable, String> next = new LinkedHashMap<>();
        for (SqlChildTable child : digestColumns.keySet()) {
            next.put(child, children.digest(child, dto));
        }
        int rewritten = 0;
        try {
            Map<SqlChildTable, String> stored = storedDigests(connection, id);
            try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
                int index = bindMainRow(statement, dto);
                for (String digest : next.values()) {
                    statement.setString(index, digest);
                    index++;
                }
                statement.executeUpdate();
            }
            for (SqlChildTable child : table.children()) {
                String digest = next.get(child);
                if (digest != null && digest.equals(stored.get(child))) {
                    continue;
                }
                if (rewritten == 0 && digestsSql.isEmpty()) {
                    // With digests, the flag rode with the digest read; without, it costs a statement.
                    raiseFlag(connection);
                }
                children.write(connection, child, id, dto);
                rewritten++;
            }
        } catch (SQLException e) {
            throw writeFailure(id, e);
        }
        LOG.debug("Upserted {} into table '{}', {} of {} collection(s) rewritten", id, table.name(), rewritten,
                table.children().size());
    }

    /**
     * The digests stored on the entity's row, LOCKED until the end of the caller's transaction: a
     * concurrent write of the same entity waits, then compares against what this one wrote. Empty for a
     * new entity — nothing to compare with, everything is written.
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    private Map<SqlChildTable, String> storedDigests(Connection connection, Object id) throws SQLException {
        if (digestsSql.isEmpty()) {
            return Map.of();
        }
        Map<SqlChildTable, String> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(digestsSql)) {
            statement.setObject(1, table.dialect().parameter(id));
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    int index = 2;
                    for (SqlChildTable child : digestColumns.keySet()) {
                        stored.put(child, rs.getString(index));
                        index++;
                    }
                }
            }
        }
        return stored;
    }

    /**
     * Tells the digest triggers that the DAO itself is writing child rows, for this transaction only — it
     * writes the digest those rows match. Raised whenever the table has collections, digests installed or
     * not: a trigger left by an earlier installation must not erase anything the DAO writes either.
     */
    @SuppressFBWarnings(value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification = "the digest capability's own constant flag expression")
    private void raiseFlag(Connection connection) throws SQLException {
        if (this.digests.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT " + this.digests.get().flagExpression());
        }
    }

    /**
     * Deletes by id; child rows go by {@code ON DELETE CASCADE}.
     *
     * @param connection the caller's connection
     * @param dto        the entity, of which only the uuid is read
     * @throws ApiException when the entity has no uuid, no row has it, or the database refuses
     */
    @SuppressFBWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
            justification = SuppressFBWarnings.GENERATED_SQL)
    public void delete(Connection connection, Object dto) throws ApiException {
        Object id = idOf(dto, "delete");
        int deleted;
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setObject(1, table.dialect().parameter(id));
            deleted = statement.executeUpdate();
        } catch (SQLException e) {
            throw new ApiException("Failed to delete entity " + id + " from table '" + table.name() + "': "
                    + e.getMessage(), e);
        }
        if (deleted == 0) {
            throw new ApiException("Row not found for deletion in table '" + table.name() + "': "
                    + table.id().name() + "=" + id);
        }
        LOG.debug("Deleted {} from table '{}'", id, table.name());
    }

    /**
     * Translates a write the database refused. A unique violation is the SAME refusal the framework's
     * own unicity check reports, only decided by the database — under concurrency the database is the
     * one that wins the race — so it reads as a conflict (HTTP 409), as on MongoDB. The upsert itself
     * resolves a conflict on the primary key, so any {@code 23505} here comes from another unique
     * index: a declared uniqueness.
     */
    private ApiException writeFailure(Object id, SQLException e) {
        Optional<ISqlDialect.UniqueViolation> unique = table.dialect().uniqueViolation(e);
        if (unique.isEmpty()) {
            return new ApiException("Failed to save entity " + id + " into table '" + table.name() + "': "
                    + e.getMessage(), e);
        }
        return ApiException.conflict("Unicity constraint violated on table '" + table.name() + "'"
                + unique.get().index().map(index -> " (index '" + index + "')").orElse("")
                + ", refused by the database: " + unique.get().detail());
    }

    /** {@return the main-row upsert statement} Package-private so a test can inspect placeholders. */
    public String upsertSql() {
        return upsertSql;
    }

    private Object idOf(Object dto, String operation) throws ApiException {
        if (dto == null) {
            throw new ApiException("Cannot " + operation + " a null entity into table '" + table.name() + "'");
        }
        Object uuid = SqlWriteSupport.valueAt(dto, table.id().fieldPath());
        if (uuid == null) {
            throw new ApiException("Cannot " + operation + " a " + dto.getClass().getName() + " without a uuid ('"
                    + table.id().dottedPath() + "' is null): a relational row needs its primary key");
        }
        return SqlValues.toJdbc(table.dialect(), table.id(), uuid);
    }

    /** Binds every model column of the main row; {@return the next parameter index} */
    private int bindMainRow(PreparedStatement statement, Object dto) throws SQLException, ApiException {
        int index = 1;
        for (SqlColumn column : table.columns()) {
            Object value = SqlWriteSupport.valueAt(dto, column.fieldPath());
            if (column.kind() == SqlColumnKind.COMPOSITION) {
                String target = table.compositionTarget(column.dottedPath()).orElse(null);
                value = references.uuidOf(target, value);
            }
            SqlWriteSupport.bind(table.dialect(), statement, index, column, SqlValues.toJdbc(table.dialect(), column, value));
            index++;
        }
        return index;
    }

    /**
     * Reads the stored digests, locking the row, and raises the DAO's flag for the transaction — in ONE
     * statement, and always one row: a new entity has no row to lock, yet its collections are written too.
     */
    private static String buildDigestsSql(SqlTable table, ISqlListDigests digests, Collection<String> digestColumns) {
        StringJoiner columns = new StringJoiner(", ");
        digestColumns.forEach(c -> columns.add("d." + SqlNaming.quote(c)));
        StringJoiner stored = new StringJoiner(", ");
        digestColumns.forEach(c -> stored.add(SqlNaming.quote(c)));
        return "SELECT " + digests.flagExpression() + ", " + columns + " FROM (SELECT 1) one LEFT JOIN (SELECT "
                + stored + " FROM " + SqlNaming.quote(table.name()) + " WHERE " + SqlNaming.quote(table.id().name())
                + " = ?" + digests.lockForUpdate() + ") d ON TRUE";
    }

    /**
     * Deletes the main row; its collections go by {@code ON DELETE CASCADE}. With collections, the DAO's
     * flag is raised by the same statement — evaluated while the row is matched, so before the cascade
     * runs: the digest triggers have nothing to keep true for rows that disappear with their owner.
     */
    private static String buildDeleteSql(SqlTable table, Optional<ISqlListDigests> digests) {
        String delete = "DELETE FROM " + SqlNaming.quote(table.name()) + " AS " + SqlQuery.ALIAS + " WHERE "
                + SqlQuery.ALIAS + "." + SqlNaming.quote(table.id().name()) + " = ?";
        return digests.isEmpty() ? delete
                : "WITH flag AS (SELECT " + digests.get().flagExpression() + ") " + delete
                        + " AND EXISTS (SELECT 1 FROM flag)";
    }

    private static String buildUpsertSql(SqlTable table, Collection<String> digestColumns) {
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        StringJoiner updates = new StringJoiner(", ");
        for (SqlColumn column : table.columns()) {
            String quoted = SqlNaming.quote(column.name());
            names.add(quoted);
            values.add(SqlValues.placeholder(table.dialect(), column));
            if (column.kind() != SqlColumnKind.ID) {
                updates.add(quoted + " = EXCLUDED." + quoted);
            }
        }
        for (String digest : digestColumns) {
            String quoted = SqlNaming.quote(digest);
            names.add(quoted);
            values.add("?");
            updates.add(quoted + " = EXCLUDED." + quoted);
        }
        String conflict = updates.length() == 0 ? "DO NOTHING" : "DO UPDATE SET " + updates;
        return "INSERT INTO " + SqlNaming.quote(table.name()) + " AS " + SqlQuery.ALIAS + " (" + names
                + ") VALUES (" + values + ") ON CONFLICT (" + SqlNaming.quote(table.id().name()) + ") " + conflict;
    }
}
