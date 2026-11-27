package com.garganttua.dao.sqlite;

import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.dao.sql.AbstractSqlDao;
import com.garganttua.dao.sql.SqlIndexPlanner;
import com.garganttua.dao.sql.SqlSchemaRegistry;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/**
 * The SQLite DAO: the relational mapping of the shared SQL core — one column per scalar field, one child table per
 * collection, every MongoDB filter, sort and page translated with MongoDB's semantics — on an embedded SQLite
 * database.
 *
 * <p>
 * It serves one domain; its main table is named after it. The {@link DataSource} is the caller's: one connection per
 * thread of a pool on one database file (the starter configures WAL mode and a busy timeout, which let readers run
 * while one writer writes). Each connection is readied by the DAO before its first use — foreign keys on, the Java
 * functions its SQL calls registered ({@link SqliteFunctions}).
 * </p>
 *
 * <p>
 * Every DAO of one database should share one {@link SqlSchemaRegistry}: that is how a {@code @Composed} field of one
 * domain resolves against another domain's table.
 * </p>
 */
@Reflected
public class SqliteDao extends AbstractSqlDao {

    private final SqliteSchemaMode schemaMode;
    private final SqliteIndexMode indexMode;

    /**
     * A DAO creating its tables and indexes, with a registry of its own.
     *
     * @param dataSource the database
     * @param domainName the domain served
     */
    public SqliteDao(DataSource dataSource, String domainName) {
        this(dataSource, domainName, new SqlSchemaRegistry(), SqliteSchemaMode.CREATE);
    }

    /**
     * A DAO creating its indexes.
     *
     * @param dataSource the database
     * @param domainName the domain served
     * @param registry   the shape of every domain of this database
     * @param schemaMode who owns the schema
     */
    public SqliteDao(DataSource dataSource, String domainName, SqlSchemaRegistry registry,
            SqliteSchemaMode schemaMode) {
        this(dataSource, domainName, registry, schemaMode, SqliteIndexMode.CREATE);
    }

    /**
     * A DAO.
     *
     * @param dataSource the database
     * @param domainName the domain served
     * @param registry   the shape of every domain of this database
     * @param schemaMode who owns the schema
     * @param indexMode  who creates the declared indexes
     */
    public SqliteDao(DataSource dataSource, String domainName, SqlSchemaRegistry registry, SqliteSchemaMode schemaMode,
            SqliteIndexMode indexMode) {
        super(SqliteDialect.INSTANCE, dataSource, domainName, Objects.requireNonNull(registry, "registry"));
        this.schemaMode = Objects.requireNonNull(schemaMode, "schemaMode");
        this.indexMode = Objects.requireNonNull(indexMode, "indexMode");
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected Prepared prepare(SqlTable table, IDomainDefinition domainDefinition, IDtoDefinition<?> dto)
            throws ApiException {
        List<SqlIndexPlanner.Planned> indexes = SqlIndexPlanner.plan(table, domainDefinition, dto);
        new SqliteSchemaManager(dataSource(), this.schemaMode).ensure(table);
        new SqliteIndexManager(this.indexMode, this.schemaMode).ensure(dataSource(), table, domainName(), indexes);
        return new Prepared(false, SqlIndexPlanner.textColumn(table, indexes));
    }
}
