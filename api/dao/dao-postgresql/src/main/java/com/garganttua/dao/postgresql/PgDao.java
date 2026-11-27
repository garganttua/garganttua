package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;
import com.garganttua.dao.sql.AbstractSqlDao;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * PostgreSQL-backed {@link com.garganttua.api.commons.dao.IDao}: one domain, one main table, one child table per collection — a
 * relational mapping, one column per field.
 *
 * <p>
 * The shape of the tables is derived once from the domain's DTO by {@link SqlSchemaModel} and is the
 * single contract of the four collaborators this class wires: {@link PgSchemaManager} (DDL),
 * {@link SqlWriter} (upsert, delete), {@link SqlReader} (select, rebuild) and {@link SqlQueryBuilder}
 * (filters, sort, pagination, projection). None of them re-derives the mapping.
 * </p>
 *
 * <p>
 * <b>Round trips, and transactions only where they are needed.</b> Over a network, what an operation
 * costs is mostly how many times it waits for the server. A read is ONE statement — the collections come
 * back inside the main query, aggregated ({@link SqlChildAggregate}) — and a paginated read carries its
 * total in the same statement ({@link #findPage}); one statement is one snapshot, so these run in
 * autocommit, with no {@code BEGIN}, {@code COMMIT} or {@code SET}. So do the writes that are one
 * statement: an entity without collections, and every delete (the collections go by cascade). An entity
 * WITH collections spans several tables, so its write runs in ONE transaction: the main row and every
 * child row land together or not at all. A read that still needs several statements — references to
 * other domains, a collection too wide to aggregate — runs in one {@code REPEATABLE READ} snapshot, set
 * for that transaction only ({@code SET TRANSACTION}), so the pooled connection's session is left as it
 * was lent.
 * </p>
 *
 * <p>
 * <b>Wiring.</b> The starter shares ONE {@link PgSchemaRegistry} among every domain of a database,
 * which is what lets a {@code @Composed} field be resolved against another domain's table. Wired by
 * hand with {@link #PgDao(DataSource, String)}, each DAO has a registry of its own: fine for a domain
 * without compositions, not for one that references another — pass a shared registry then.
 * </p>
 */
@Reflected
public class PgDao extends AbstractSqlDao {

    private static final Logger log = Logger.getLogger(PgDao.class);

    private final SchemaMode schemaMode;
    private final PgIndexMode indexMode;

    /**
     * A DAO with its own registry, creating its tables if they are missing.
     *
     * @param dataSource where the tables live
     * @param domainName the domain served — the main table is named after it
     */
    public PgDao(DataSource dataSource, String domainName) {
        this(dataSource, domainName, new PgSchemaRegistry(), SchemaMode.CREATE);
    }

    /**
     * A DAO sharing a registry with the other domains of the same database.
     *
     * @param dataSource where the tables live
     * @param domainName the domain served
     * @param registry   the shape of every domain of this database — shared, so references resolve
     * @param schemaMode whether this DAO creates what is missing or only checks it
     */
    public PgDao(DataSource dataSource, String domainName, PgSchemaRegistry registry, SchemaMode schemaMode) {
        this(dataSource, domainName, registry, schemaMode, PgIndexMode.CREATE);
    }

    /**
     * A DAO sharing a registry, with an explicit policy for the indexes its domain declares.
     *
     * @param dataSource where the tables live
     * @param domainName the domain served
     * @param registry   the shape of every domain of this database
     * @param schemaMode whether this DAO creates what is missing or only checks it
     * @param indexMode  who creates the declared indexes, and what a failure costs
     */
    public PgDao(DataSource dataSource, String domainName, PgSchemaRegistry registry, SchemaMode schemaMode,
            PgIndexMode indexMode) {
        super(PgDialect.INSTANCE, dataSource, domainName, Objects.requireNonNull(registry, "registry"));
        this.schemaMode = Objects.requireNonNull(schemaMode, "schemaMode");
        this.indexMode = Objects.requireNonNull(indexMode, "indexMode");
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected Prepared prepare(SqlTable table, IDomainDefinition domainDefinition, IDtoDefinition<?> dto)
            throws ApiException {
        List<PgIndexPlanner.Planned> indexes = PgIndexPlanner.plan(table, domainDefinition, dto);
        new PgSchemaManager(dataSource(), this.schemaMode).ensure(table);
        new PgIndexManager(this.indexMode, this.schemaMode).ensure(dataSource(), domainName(), indexes);
        return new Prepared(ensureDigests(table), PgIndexPlanner.textColumn(table, indexes));
    }

    private boolean ensureDigests(SqlTable table) {
        if (table.children().isEmpty()) {
            return false;
        }
        try (Connection connection = dataSource().getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, PgSchemaManager.lockKey("digests:" + table.name()));
                lock.execute();
                boolean ready = PgListDigests.ensure(connection, table, this.schemaMode);
                connection.commit();
                return ready;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("Table '{}': the list digests could not be installed ({}); every write rewrites every "
                    + "collection of the entity.", table.name(), e.getMessage());
            return false;
        }
    }
}
