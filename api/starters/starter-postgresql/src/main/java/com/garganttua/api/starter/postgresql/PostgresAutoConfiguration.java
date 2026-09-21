package com.garganttua.api.starter.postgresql;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.postgresql.PgDao;
import com.garganttua.dao.postgresql.PgSchemaRegistry;
import com.garganttua.dao.postgresql.schema.SchemaMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Auto-configures PostgreSQL persistence: reads {@code postgresql.*} from the application config,
 * opens ONE pooled {@code DataSource}, and registers a default DAO factory that yields a
 * {@link PgDao} per domain (table = domain name). A domain whose DTO sets an explicit
 * {@code .db(...)} keeps it — the factory is only a fallback.
 *
 * <p>
 * Every DAO it builds shares one {@link PgSchemaRegistry}: that is what lets a {@code @Composed} field
 * of one domain be resolved against another domain's table.
 * </p>
 *
 * <table>
 * <caption>Configuration</caption>
 * <tr><th>Key</th><th>Required</th><th>Meaning</th></tr>
 * <tr><td>{@code postgresql.url}</td><td>yes</td><td>JDBC URL, {@code jdbc:postgresql://host:5432/db}</td></tr>
 * <tr><td>{@code postgresql.user}</td><td>no</td><td>user name</td></tr>
 * <tr><td>{@code postgresql.password}</td><td>no</td><td>password</td></tr>
 * <tr><td>{@code postgresql.pool.size}</td><td>no</td><td>maximum pooled connections (default 10)</td></tr>
 * <tr><td>{@code postgresql.schema.auto}</td><td>no</td><td>{@code true} (default): create missing tables
 * and columns; {@code false}: create nothing and refuse to start on a schema that does not fit</td></tr>
 * </table>
 *
 * <p>
 * Runs at {@code order() = 0} (persistence before transport). Discovered via
 * {@link java.util.ServiceLoader}.
 * </p>
 */
public final class PostgresAutoConfiguration implements IApiAutoConfiguration {

    /** Default pool ceiling — enough for a small service, low enough not to exhaust a shared server. */
    static final int DEFAULT_POOL_SIZE = 10;

    @Override
    public int order() {
        return 0;
    }

    // CloseResource: the pool is intentionally long-lived and handed to the framework via
    // context.registerResource(...), which owns and closes it on shutdown.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void apply(AutoConfigurationContext context) throws ApiException {
        IConfig config = context.config();
        String url = config.getString("postgresql.url")
                .orElseThrow(() -> new ApiException(
                        "postgresql.url is required by the PostgreSQL starter. Set it in application.yaml "
                                + "(postgresql.url: jdbc:postgresql://host:5432/myapp) or via "
                                + "GARGANTTUA_POSTGRESQL_URL."));
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new ApiException("postgresql.url must be a PostgreSQL JDBC URL starting with "
                    + "'jdbc:postgresql:', got '" + url + "'.");
        }

        HikariDataSource dataSource = new HikariDataSource(poolConfig(url, config));
        context.registerResource(dataSource);

        SchemaMode mode = config.getBoolean("postgresql.schema.auto").orElse(Boolean.TRUE)
                ? SchemaMode.CREATE : SchemaMode.VALIDATE;
        PgSchemaRegistry registry = new PgSchemaRegistry();

        // One PgDao per domain, all sharing the pool and the registry; the table is the domain name.
        context.registerDefaultDao((domainName, dtoClass) -> new PgDao(dataSource, domainName, registry, mode));
    }

    /** The pool settings. Package-private so the test can check them without opening a connection. */
    static HikariConfig poolConfig(String url, IConfig config) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        config.getString("postgresql.user").ifPresent(pool::setUsername);
        config.getString("postgresql.password").ifPresent(pool::setPassword);
        pool.setMaximumPoolSize(config.getInt("postgresql.pool.size").orElse(DEFAULT_POOL_SIZE));
        pool.setPoolName("garganttua-postgresql");
        // Fail at startup on a server that cannot be reached, not at the first request.
        pool.setInitializationFailTimeout(1);
        return pool;
    }
}
