package com.garganttua.dao.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlSchemaModel;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * An {@link IDao} over a relational database: one domain, one main table, one child table per collection — the part
 * of every SQL DAO that does not depend on the database.
 *
 * <p>
 * The shape of the tables is derived once from the domain's DTO by {@link SqlSchemaModel}, and is the single contract
 * of the collaborators wired here: {@link SqlWriter} (upsert, delete), {@link SqlReader} (select, rebuild) and
 * {@link SqlQueryBuilder} (filters, sort, pagination, projection). What a database does differently, its
 * {@link ISqlDialect} spells; what it must do to make the tables ready — create or check them, their indexes, their
 * digests — a subclass does in {@link #prepare}.
 * </p>
 *
 * <p>
 * <b>Round trips, and transactions only where they are needed.</b> A read is ONE statement — the collections come
 * back inside the main query, aggregated — and a paginated read carries its total in the same statement; one
 * statement is one snapshot, so these run in autocommit. So do the writes that are one statement: an entity without
 * collections, and every delete (the collections go by cascade). An entity WITH collections spans several tables, so
 * its write runs in ONE transaction. A read that still needs several statements runs in one snapshot, opened by the
 * dialect's {@link ISqlDialect#snapshotStatement()}.
 * </p>
 */
public abstract class AbstractSqlDao implements IDao {

    private static final Logger log = Logger.getLogger(AbstractSqlDao.class);

    private final ISqlDialect dialect;
    private final DataSource dataSource;
    private final String domainName;
    private final SqlSchemaRegistry registry;

    private volatile Wiring wiring;

    /** The collaborators of one registered domain — rebuilt as a whole if the domain re-registers. */
    private record Wiring(SqlTable table, SqlWriter writer, SqlReader reader, SqlQueryBuilder queries) {
    }

    /**
     * What {@link #prepare} found out about the tables it made ready.
     *
     * @param digests    whether the collection digests are installed — the writer then skips unchanged collections
     * @param textColumn the column a declared text index covers, which {@code $text} then searches alone
     */
    protected record Prepared(boolean digests, Optional<SqlColumn> textColumn) {

        public Prepared {
            Objects.requireNonNull(textColumn, "textColumn");
        }
    }

    /**
     * A DAO of one domain.
     *
     * @param dialect    the database's dialect
     * @param dataSource where the tables live
     * @param domainName the domain served — the main table is named after it
     * @param registry   the shape of every domain of this database — shared, so references resolve
     */
    protected AbstractSqlDao(ISqlDialect dialect, DataSource dataSource, String domainName,
            SqlSchemaRegistry registry) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.domainName = Objects.requireNonNull(domainName, "domainName");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Makes the tables of a newly registered domain ready: creates or checks them, their indexes, their digests.
     *
     * @param table            the domain's model
     * @param domainDefinition the domain, for its declared indexes
     * @param dto              the persisted DTO's definition
     * @return what the wiring needs to know
     * @throws ApiException when the schema is not usable
     */
    @SuppressWarnings("rawtypes")
    protected abstract Prepared prepare(SqlTable table, IDomainDefinition domainDefinition, IDtoDefinition<?> dto)
            throws ApiException;

    /** {@return the database the tables live on} */
    protected final ISqlDialect dialect() {
        return this.dialect;
    }

    /** {@return where the tables live} */
    protected final DataSource dataSource() {
        return this.dataSource;
    }

    /** {@return the domain served} */
    protected final String domainName() {
        return this.domainName;
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
        SqlTable table = SqlSchemaModel.of(this.dialect, this.domainName, dtoClass, uuidField, compositions);
        Prepared prepared;
        try {
            prepared = prepare(table, domainDefinition, dto);
        } catch (ApiException e) {
            // registerDomain cannot throw a checked exception: surface it unchecked, with its message.
            throw new IllegalStateException(this.dialect.name() + " schema of domain '" + this.domainName
                    + "' is not usable: " + e.getMessage(), e);
        }
        this.registry.register(this.domainName, table, dtoClass);
        this.wiring = new Wiring(table, new SqlWriter(table, this.registry, prepared.digests()),
                new SqlReader(table, dtoClass, this.registry),
                new SqlQueryBuilder(table, dtoClass, prepared.textColumn()));
        log.debug("{} domain '{}' mapped to table '{}' ({} columns, {} child tables)", this.dialect.name(),
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
        SqlQuery query = w.queries().build(pageable, filter, sort, projection);
        return w.reader().singleStatement(query) ? single(c -> w.reader().find(c, query))
                : inSnapshot(c -> w.reader().find(c, query));
    }

    @Override
    public Page findPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        Wiring w = wired();
        SqlQuery query = w.queries().build(pageable, filter, sort, projection);
        SqlReader.Paged paged = w.reader().singleStatement(query) ? single(c -> w.reader().findPage(c, query))
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
        SqlQuery query = w.queries().count(filter);
        return single(c -> w.reader().count(c, query));
    }

    private Wiring wired() throws ApiException {
        Wiring w = this.wiring;
        if (w == null) {
            throw new ApiException(this.dialect.name() + " DAO of domain '" + this.domainName
                    + "' used before registerDomain: its table shape is not known yet.");
        }
        return w;
    }

    /** One unit of work on a connection. */
    @FunctionalInterface
    protected interface Work<T> {
        T run(Connection connection) throws ApiException, SQLException;
    }

    private <T> T inTransaction(Work<T> work) throws ApiException {
        return transactional(work, false);
    }

    private <T> T inSnapshot(Work<T> work) throws ApiException {
        return transactional(work, true);
    }

    private <T> T single(Work<T> work) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            this.dialect.prepareConnection(connection);
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            return work.run(connection);
        } catch (SQLException e) {
            throw new ApiException(this.dialect.name() + " error on domain '" + this.domainName + "': "
                    + e.getMessage(), e);
        }
    }

    private <T> T transactional(Work<T> work, boolean snapshot) throws ApiException {
        try (Connection connection = this.dataSource.getConnection()) {
            this.dialect.prepareConnection(connection);
            boolean autoCommit = connection.getAutoCommit();
            this.dialect.beginTransaction(connection, !snapshot);
            connection.setAutoCommit(false);
            try {
                Optional<String> opening = snapshot ? this.dialect.snapshotStatement() : Optional.empty();
                if (opening.isPresent()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(opening.get());
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
            throw new ApiException(this.dialect.name() + " error on domain '" + this.domainName + "': "
                    + e.getMessage(), e);
        }
    }
}
