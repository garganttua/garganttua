package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.StringJoiner;

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
 * UPDATE} on the main row, then every collection REPLACED (see {@link PgWriteChildren}). It spans
 * several statements, so it is only atomic inside a transaction — which the CALLER owns: the writer
 * never commits nor rolls back, so the DAO can put a write and whatever surrounds it in one unit.
 * </p>
 *
 * <p>
 * Every value is a bind parameter converted by {@link PgValues}; every identifier comes from the
 * {@link PgTable} model, quoted. Nothing from the entity is ever concatenated into SQL.
 * </p>
 */
public final class PgWriter {

    private static final Logger LOG = Logger.getLogger(PgWriter.class);

    private final PgTable table;
    private final PgWriteReferences references;
    private final PgWriteChildren children;
    private final String upsertSql;
    private final String deleteSql;

    /**
     * @param table    the domain's table model
     * @param registry the shapes of the other domains, to read the uuid of a composed entity
     */
    public PgWriter(PgTable table, PgSchemaRegistry registry) {
        this.table = Objects.requireNonNull(table, "table");
        this.references = new PgWriteReferences(table, Objects.requireNonNull(registry, "registry"));
        this.children = new PgWriteChildren(table, references);
        this.upsertSql = buildUpsertSql(table);
        this.deleteSql = "DELETE FROM " + PgNaming.quote(table.name()) + " AS " + PgQuery.ALIAS + " WHERE "
                + PgQuery.ALIAS + "." + PgNaming.quote(table.id().name()) + " = ?";
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
    public void upsert(Connection connection, Object dto) throws ApiException {
        Object id = idOf(dto, "save");
        try {
            try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
                bindMainRow(statement, dto);
                statement.executeUpdate();
            }
            for (PgChildTable child : table.children()) {
                children.write(connection, child, id, dto);
            }
        } catch (SQLException e) {
            throw new ApiException("Failed to save entity " + id + " into table '" + table.name() + "': "
                    + e.getMessage(), e);
        }
        LOG.debug("Upserted {} into table '{}' with {} collection(s)", id, table.name(), table.children().size());
    }

    /**
     * Deletes by id; child rows go by {@code ON DELETE CASCADE}.
     *
     * @param connection the caller's connection
     * @param dto        the entity, of which only the uuid is read
     * @throws ApiException when the entity has no uuid, no row has it, or the database refuses
     */
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

    private void bindMainRow(PreparedStatement statement, Object dto) throws SQLException, ApiException {
        int index = 1;
        for (PgColumn column : table.columns()) {
            Object value = PgWriteSupport.valueAt(dto, column.fieldPath());
            if (column.kind() == PgColumnKind.COMPOSITION) {
                String target = table.compositionTarget(column.dottedPath()).orElse(null);
                value = references.uuidOf(target, value);
            }
            PgWriteSupport.bind(statement, index++, column, PgValues.toJdbc(column, value));
        }
    }

    private static String buildUpsertSql(PgTable table) {
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
        String conflict = updates.length() == 0 ? "DO NOTHING" : "DO UPDATE SET " + updates;
        return "INSERT INTO " + PgNaming.quote(table.name()) + " AS " + PgQuery.ALIAS + " (" + names
                + ") VALUES (" + values + ") ON CONFLICT (" + PgNaming.quote(table.id().name()) + ") " + conflict;
    }
}
