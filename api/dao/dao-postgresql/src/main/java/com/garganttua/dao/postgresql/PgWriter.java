package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The write side of the PostgreSQL DAO: one entity in, one main row plus its collection rows out.
 *
 * <p>
 * An upsert is the relational twin of MongoDB's {@code save()}: {@code INSERT … ON CONFLICT (id) DO
 * UPDATE} on the main row, then every collection REPLACED (see {@link PgWriteChildren}) — every collection
 * that CHANGED, when the list digests are installed ({@link PgListDigests}): the digest of the rows a
 * collection would be written as is stored on the main row, and a collection whose digest is the stored
 * one is left as it is, rows, indexes and WAL untouched. It spans
 * several statements, so it is only atomic inside a transaction — which the CALLER owns: the writer
 * never commits nor rolls back, so the DAO can put a write and whatever surrounds it in one unit.
 * </p>
 *
 * <p>
 * Every value is a bind parameter converted by {@link PgValues}; every identifier comes from the
 * {@link PgTable} model, quoted. Nothing from the entity is ever concatenated into SQL.
 * </p>
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // accessor style: a constant/field and the method using it share a name
public final class PgWriter {

    private static final Logger LOG = Logger.getLogger(PgWriter.class);

    private final PgTable table;
    private final PgWriteReferences references;
    private final PgWriteChildren children;
    private final Map<PgChildTable, String> digestColumns;
    private final String upsertSql;
    private final String digestsSql;
    private final String deleteSql;

    /**
     * A writer that rewrites every collection at every write.
     *
     * @param table    the domain's table model
     * @param registry the shapes of the other domains, to read the uuid of a composed entity
     */
    public PgWriter(PgTable table, PgSchemaRegistry registry) {
        this(table, registry, false);
    }

    /**
     * @param table    the domain's table model
     * @param registry the shapes of the other domains, to read the uuid of a composed entity
     * @param digests  whether the list digests are installed — the writer then rewrites only the
     *                 collections that changed
     */
    public PgWriter(PgTable table, PgSchemaRegistry registry, boolean digests) {
        this.table = Objects.requireNonNull(table, "table");
        this.references = new PgWriteReferences(table, Objects.requireNonNull(registry, "registry"));
        this.children = new PgWriteChildren(table, references);
        this.digestColumns = digests && !table.children().isEmpty() ? PgListDigests.columns(table) : Map.of();
        this.upsertSql = buildUpsertSql(table, this.digestColumns.values());
        this.digestsSql = this.digestColumns.isEmpty() ? "" : buildDigestsSql(table, this.digestColumns.values());
        this.deleteSql = buildDeleteSql(table);
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
        Map<PgChildTable, String> next = new LinkedHashMap<>();
        for (PgChildTable child : digestColumns.keySet()) {
            next.put(child, children.digest(child, dto));
        }
        int rewritten = 0;
        try {
            Map<PgChildTable, String> stored = storedDigests(connection, id);
            try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
                int index = bindMainRow(statement, dto);
                for (String digest : next.values()) {
                    statement.setString(index, digest);
                    index++;
                }
                statement.executeUpdate();
            }
            for (PgChildTable child : table.children()) {
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
    private Map<PgChildTable, String> storedDigests(Connection connection, Object id) throws SQLException {
        if (digestsSql.isEmpty()) {
            return Map.of();
        }
        Map<PgChildTable, String> stored = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(digestsSql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    int index = 2;
                    for (PgChildTable child : digestColumns.keySet()) {
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
    private void raiseFlag(Connection connection) throws SQLException {
        if (table.children().isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(PgListDigests.RAISE_FLAG);
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
            statement.setObject(1, id);
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
        if (!PgIndexManager.UNIQUE_VIOLATION.equals(e.getSQLState())) {
            return new ApiException("Failed to save entity " + id + " into table '" + table.name() + "': "
                    + e.getMessage(), e);
        }
        ServerErrorMessage server = e instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
        String constraint = server == null ? null : server.getConstraint();
        String detail = server == null ? e.getMessage() : server.getDetail();
        return ApiException.conflict("Unicity constraint violated on table '" + table.name() + "'"
                + (constraint == null ? "" : " (index '" + constraint + "')") + ", refused by the database: "
                + detail);
    }

    /** {@return the main-row upsert statement} Package-private so a test can inspect placeholders. */
    String upsertSql() {
        return upsertSql;
    }

    private Object idOf(Object dto, String operation) throws ApiException {
        if (dto == null) {
            throw new ApiException("Cannot " + operation + " a null entity into table '" + table.name() + "'");
        }
        Object uuid = PgWriteSupport.valueAt(dto, table.id().fieldPath());
        if (uuid == null) {
            throw new ApiException("Cannot " + operation + " a " + dto.getClass().getName() + " without a uuid ('"
                    + table.id().dottedPath() + "' is null): a relational row needs its primary key");
        }
        return PgValues.toJdbc(table.id(), uuid);
    }

    /** Binds every model column of the main row; {@return the next parameter index} */
    private int bindMainRow(PreparedStatement statement, Object dto) throws SQLException, ApiException {
        int index = 1;
        for (PgColumn column : table.columns()) {
            Object value = PgWriteSupport.valueAt(dto, column.fieldPath());
            if (column.kind() == PgColumnKind.COMPOSITION) {
                String target = table.compositionTarget(column.dottedPath()).orElse(null);
                value = references.uuidOf(target, value);
            }
            PgWriteSupport.bind(statement, index, column, PgValues.toJdbc(column, value));
            index++;
        }
        return index;
    }

    /**
     * Reads the stored digests, locking the row, and raises the DAO's flag for the transaction — in ONE
     * statement, and always one row: a new entity has no row to lock, yet its collections are written too.
     */
    private static String buildDigestsSql(PgTable table, Collection<String> digestColumns) {
        StringJoiner columns = new StringJoiner(", ");
        digestColumns.forEach(c -> columns.add("d." + PgNaming.quote(c)));
        StringJoiner stored = new StringJoiner(", ");
        digestColumns.forEach(c -> stored.add(PgNaming.quote(c)));
        return "SELECT " + PgListDigests.FLAG_EXPRESSION + ", " + columns + " FROM (SELECT 1) one LEFT JOIN (SELECT "
                + stored + " FROM " + PgNaming.quote(table.name()) + " WHERE " + PgNaming.quote(table.id().name())
                + " = ? FOR UPDATE) d ON TRUE";
    }

    /**
     * Deletes the main row; its collections go by {@code ON DELETE CASCADE}. With collections, the DAO's
     * flag is raised by the same statement — evaluated while the row is matched, so before the cascade
     * runs: the digest triggers have nothing to keep true for rows that disappear with their owner.
     */
    private static String buildDeleteSql(PgTable table) {
        String delete = "DELETE FROM " + PgNaming.quote(table.name()) + " AS " + PgQuery.ALIAS + " WHERE "
                + PgQuery.ALIAS + "." + PgNaming.quote(table.id().name()) + " = ?";
        return table.children().isEmpty() ? delete
                : "WITH flag AS (SELECT " + PgListDigests.FLAG_EXPRESSION + ") " + delete
                        + " AND EXISTS (SELECT 1 FROM flag)";
    }

    private static String buildUpsertSql(PgTable table, Collection<String> digestColumns) {
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        StringJoiner updates = new StringJoiner(", ");
        for (PgColumn column : table.columns()) {
            String quoted = PgNaming.quote(column.name());
            names.add(quoted);
            values.add(PgValues.placeholder(column));
            if (column.kind() != PgColumnKind.ID) {
                updates.add(quoted + " = EXCLUDED." + quoted);
            }
        }
        for (String digest : digestColumns) {
            String quoted = PgNaming.quote(digest);
            names.add(quoted);
            values.add("?");
            updates.add(quoted + " = EXCLUDED." + quoted);
        }
        String conflict = updates.length() == 0 ? "DO NOTHING" : "DO UPDATE SET " + updates;
        return "INSERT INTO " + PgNaming.quote(table.name()) + " AS " + PgQuery.ALIAS + " (" + names
                + ") VALUES (" + values + ") ON CONFLICT (" + PgNaming.quote(table.id().name()) + ") " + conflict;
    }
}
