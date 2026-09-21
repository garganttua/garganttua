package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.definition.DtoComposition;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * PostgreSQL-backed {@link IDao}: one domain, one main table, one child table per collection — a
 * relational mapping, one column per field.
 *
 * <p>
 * The shape of the tables is derived once from the domain's DTO by {@link PgSchemaModel} and is the
 * single contract of the four collaborators this class wires: {@link PgSchemaManager} (DDL),
 * {@link PgWriter} (upsert, delete), {@link PgReader} (select, rebuild) and {@link PgQueryBuilder}
 * (filters, sort, pagination, projection). None of them re-derives the mapping.
 * </p>
 *
 * <p>
 * <b>Transactions.</b> An entity spans several tables, so every write runs in ONE transaction: the
 * main row and every child row land together or not at all. Reads also run in one transaction, at
 * {@code REPEATABLE READ}: a read is several queries (the main table, then each child table), and
 * without a snapshot a write committed between two of them would hand back an entity stitched from
 * two different moments — the exact interleaving a multi-instance deployment produces.
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
public class PgDao implements IDao {

    private static final Logger log = Logger.getLogger(PgDao.class);

    private final DataSource dataSource;
    private final String domainName;
    private final PgSchemaRegistry registry;
    private final SchemaMode schemaMode;

    private volatile Wiring wiring;

    /** The collaborators of one registered domain — rebuilt as a whole if the domain re-registers. */
    private record Wiring(PgTable table, PgWriter writer, PgReader reader, PgQueryBuilder queries) {
    }

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
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.domainName = Objects.requireNonNull(domainName, "domainName");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schemaMode = Objects.requireNonNull(schemaMode, "schemaMode");
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void registerDomain(IDomainDefinition domainDefinition) {
        if (domainDefinition == null || domainDefinition.dtoDefinitions() == null
                || domainDefinition.dtoDefinitions().isEmpty()) {
            return;
        }
        IDtoDefinition<?> dto = (IDtoDefinition<?>) domainDefinition.dtoDefinitions().get(0);
        IClass<?> dtoClass = dto.dtoClass();
        String uuidField = dto.uuid() == null ? null : dto.uuid().getLastElement();
        Map<String, String> compositions = new LinkedHashMap<>();
        for (DtoComposition composition : dto.compositions()) {
            compositions.put(composition.field().getLastElement(), composition.collection());
        }
        PgTable table = PgSchemaModel.of(this.domainName, dtoClass, uuidField, compositions);
        try {
            new PgSchemaManager(this.dataSource, this.schemaMode).ensure(table);
        } catch (ApiException e) {
            // registerDomain cannot throw a checked exception: surface it unchecked, with its message.
            throw new IllegalStateException("PostgreSQL schema of domain '" + this.domainName
                    + "' is not usable: " + e.getMessage(), e);
        }
        this.registry.register(this.domainName, table, dtoClass);
        this.wiring = new Wiring(table, new PgWriter(table, this.registry),
                new PgReader(table, dtoClass, this.registry), new PgQueryBuilder(table, dtoClass));
        log.debug("PostgreSQL domain '{}' mapped to table '{}' ({} columns, {} child tables)",
                this.domainName, table.name(), table.columns().size(), table.children().size());
    }

    @Override
    public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
            throws ApiException {
        return find(pageable, filter, sort, Optional.empty());
    }

    @Override
    public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        Wiring w = wired();
        PgQuery query = w.queries().build(pageable, filter, sort, projection);
        return inSnapshot(c -> w.reader().find(c, query));
    }

    @Override
    public Object save(Object object) throws ApiException {
        Wiring w = wired();
        inTransaction(c -> {
            w.writer().upsert(c, object);
            return null;
        });
        return object;
    }

    @Override
    public void delete(Object object) throws ApiException {
        Wiring w = wired();
        inTransaction(c -> {
            w.writer().delete(c, object);
            return null;
        });
    }

    @Override
    public long count(IFilter filter) throws ApiException {
        Wiring w = wired();
        PgQuery query = w.queries().count(filter);
        return inSnapshot(c -> w.reader().count(c, query));
    }

    private Wiring wired() throws ApiException {
        Wiring w = this.wiring;
        if (w == null) {
            throw new ApiException("PostgreSQL DAO of domain '" + this.domainName
                    + "' used before registerDomain: its table shape is not known yet.");
        }
        return w;
    }

    /** A unit of work on one connection. */
    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws ApiException, SQLException;
    }

    /** Runs a write atomically: everything commits, or nothing does. */
    private <T> T inTransaction(Work<T> work) throws ApiException {
        return transactional(work, false);
    }

    /** Runs a read on one consistent snapshot, however many queries it takes. */
    private <T> T inSnapshot(Work<T> work) throws ApiException {
        return transactional(work, true);
    }

    private <T> T transactional(Work<T> work, boolean readOnly) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            if (readOnly) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
            }
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                restore(connection, autoCommit, readOnly);
            }
        } catch (SQLException e) {
            throw new ApiException("PostgreSQL error on domain '" + this.domainName + "': " + e.getMessage(), e);
        }
    }

    /** Hands a pooled connection back as it was lent: pools reuse it for the next caller. */
    private static void restore(Connection connection, boolean autoCommit, boolean readOnly) throws SQLException {
        if (readOnly) {
            connection.setReadOnly(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        connection.setAutoCommit(autoCommit);
    }
}
