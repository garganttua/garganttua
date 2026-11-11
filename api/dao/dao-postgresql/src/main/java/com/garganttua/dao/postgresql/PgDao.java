package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
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
 * <b>Round trips, and transactions only where they are needed.</b> Over a network, what an operation
 * costs is mostly how many times it waits for the server. A read is ONE statement — the collections come
 * back inside the main query, aggregated ({@link PgChildAggregate}) — and a paginated read carries its
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
public class PgDao implements IDao {

    private static final Logger log = Logger.getLogger(PgDao.class);

    private final DataSource dataSource;
    private final String domainName;
    private final PgSchemaRegistry registry;
    private final SchemaMode schemaMode;
    private final PgIndexMode indexMode;

    /** The transaction a read of several statements runs in: one snapshot, nothing written. */
    private static final String SNAPSHOT = "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY";

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
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.domainName = Objects.requireNonNull(domainName, "domainName");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schemaMode = Objects.requireNonNull(schemaMode, "schemaMode");
        this.indexMode = Objects.requireNonNull(indexMode, "indexMode");
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
        List<PgIndexPlanner.Planned> indexes = planIndexes(domainDefinition, dto, table);
        try {
            new PgSchemaManager(this.dataSource, this.schemaMode).ensure(table);
            new PgIndexManager(this.indexMode, this.schemaMode).ensure(this.dataSource, this.domainName, indexes);
        } catch (ApiException e) {
            // registerDomain cannot throw a checked exception: surface it unchecked, with its message.
            throw new IllegalStateException("PostgreSQL schema of domain '" + this.domainName
                    + "' is not usable: " + e.getMessage(), e);
        }
        this.registry.register(this.domainName, table, dtoClass);
        this.wiring = new Wiring(table, new PgWriter(table, this.registry, ensureDigests(table)),
                new PgReader(table, dtoClass, this.registry),
                new PgQueryBuilder(table, dtoClass, PgIndexPlanner.textColumn(table, indexes)));
        log.debug("PostgreSQL domain '{}' mapped to table '{}' ({} columns, {} child tables)",
                this.domainName, table.name(), table.columns().size(), table.children().size());
    }

    /**
     * Installs the list digests (CREATE) or checks them (VALIDATE), under an advisory lock so instances
     * starting together do not race on the DDL. They are an optimisation: any failure leaves every
     * collection rewritten at every write, as before, with a WARN — never a refusal to start.
     */
    private boolean ensureDigests(PgTable table) {
        if (table.children().isEmpty()) {
            return false;
        }
        try (Connection connection = this.dataSource.getConnection()) {
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

    /**
     * The indexes the ENTITY declares, resolved against this table — and the tenant's, which the framework
     * adds to every read of a tenant caller ({@code gg_<tenant>_system_standard_idx}); the uuid needs none,
     * the primary key is on it. Entity field names are translated to DTO fields first: every query this DAO
     * emits speaks in DTO fields, so an index on anything else would never be used.
     */
    @SuppressWarnings("rawtypes")
    private static List<PgIndexPlanner.Planned> planIndexes(IDomainDefinition domainDefinition,
            IDtoDefinition<?> dto, PgTable table) {
        IEntityDefinition<?> entity = domainDefinition.entityDefinition();
        if (entity == null) {
            return List.of();
        }
        List<EntityIndexRule> rules = new ArrayList<>(entity.indexes());
        ObjectAddress tenant = entity.tenantId();
        if (tenant != null && !tenant.equals(entity.uuid())
                && rules.stream().noneMatch(rule -> rule != null && rule.field().equals(tenant))) {
            rules.add(EntityIndexRule.of(tenant, false, UnicityScope.system));
        }
        if (rules.isEmpty()) {
            return List.of();
        }
        IClass<?> dtoClass = dto.dtoClass();
        return PgIndexPlanner.plan(table, rules, path -> {
            int dot = path.indexOf('.');
            return dot < 0 ? PgQueryBuilder.translateToDtoField(dtoClass, path)
                    : PgQueryBuilder.translateToDtoField(dtoClass, path.substring(0, dot)) + path.substring(dot);
        }, dto.tenantId() == null ? null : dto.tenantId().toString());
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
        return w.reader().singleStatement(query) ? single(c -> w.reader().find(c, query))
                : inSnapshot(c -> w.reader().find(c, query));
    }

    /**
     * The page and the total its filter matches, in ONE statement — {@code count(*) OVER ()} on the page —
     * where the default made a read and a count.
     */
    @Override
    public Page findPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        Wiring w = wired();
        PgQuery query = w.queries().build(pageable, filter, sort, projection);
        PgReader.Paged paged = w.reader().singleStatement(query) ? single(c -> w.reader().findPage(c, query))
                : inSnapshot(c -> w.reader().findPage(c, query));
        return new Page(paged.total(), paged.dtos());
    }

    @Override
    public Object save(Object object) throws ApiException {
        Wiring w = wired();
        Work<Object> write = c -> {
            w.writer().upsert(c, object);
            return null;
        };
        // An entity without collections is one statement: atomic on its own, no BEGIN nor COMMIT.
        if (w.table().children().isEmpty()) {
            single(write);
        } else {
            inTransaction(write);
        }
        return object;
    }

    @Override
    public void delete(Object object) throws ApiException {
        Wiring w = wired();
        // One statement — the collections go by ON DELETE CASCADE, inside it: atomic on its own.
        single(c -> {
            w.writer().delete(c, object);
            return null;
        });
    }

    @Override
    public long count(IFilter filter) throws ApiException {
        Wiring w = wired();
        PgQuery query = w.queries().count(filter);
        return single(c -> w.reader().count(c, query));
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

    /**
     * Runs a read of several statements on one consistent snapshot. The isolation is set for THIS
     * transaction only ({@code SET TRANSACTION}), which costs one statement and leaves the pooled
     * connection's session as it found it — {@code setTransactionIsolation} costs a round trip to set and
     * another to restore.
     */
    private <T> T inSnapshot(Work<T> work) throws ApiException {
        return transactional(work, true);
    }

    /**
     * Runs ONE statement in autocommit. A single statement is atomic and sees one snapshot on its own:
     * wrapped in a transaction it would only cost a {@code BEGIN} and a {@code COMMIT} more.
     */
    private <T> T single(Work<T> work) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            return work.run(connection);
        } catch (SQLException e) {
            throw new ApiException("PostgreSQL error on domain '" + this.domainName + "': " + e.getMessage(), e);
        }
    }

    private <T> T transactional(Work<T> work, boolean snapshot) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (snapshot) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(SNAPSHOT);
                    }
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new ApiException("PostgreSQL error on domain '" + this.domainName + "': " + e.getMessage(), e);
        }
    }
}
