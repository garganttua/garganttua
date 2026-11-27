package com.garganttua.dao.postgresql.parity;

import javax.sql.DataSource;

import com.garganttua.dao.sql.AbstractSqlDao;
import com.garganttua.dao.sql.SqlSchemaRegistry;

/**
 * The SQL database the parity scenarios run against MongoDB: PostgreSQL by default, another SQL DAO of the shared
 * core when {@value #PROPERTY} names its engine class — the SQLite module runs the same scenarios this way.
 */
public interface ParityEngine {

    /** The system property naming the engine class; unset means {@link PgParityEngine}. */
    String PROPERTY = "garganttua.parity.engine";

    /** {@return the database's name, for messages} */
    String name();

    /** {@return a fresh, empty database} */
    DataSource freshDatabase();

    /**
     * A DAO of one domain, creating its tables.
     *
     * @param database   the database
     * @param domainName the domain
     * @param registry   the registry every domain of the scenario shares
     * @return the DAO
     */
    AbstractSqlDao dao(DataSource database, String domainName, SqlSchemaRegistry registry);

    /** {@return whether a TEXT column can hold U+0000} — MongoDB can; PostgreSQL cannot. */
    boolean storesNul();

    /** {@return whether this engine is PostgreSQL} — for the few scenarios that inspect its catalogs. */
    default boolean isPostgres() {
        return false;
    }

    /** {@return the engine of this run} */
    static ParityEngine current() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            return new PgParityEngine();
        }
        try {
            return (ParityEngine) Class.forName(configured.trim()).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException(PROPERTY + " names no usable ParityEngine: " + configured, e);
        }
    }
}
