package com.garganttua.api.starter.postgresql;

import java.util.Locale;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.postgresql.PgDao;
import com.garganttua.dao.postgresql.PgSchemaRegistry;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
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
 * <tr><td>{@code postgresql.index.auto}</td><td>no</td><td>{@code create} (default): create the
 * declared indexes that are missing, warn about anything that cannot be created and start anyway;
 * {@code none}: touch no index at all; {@code strict}: as {@code create}, but refuse to start when a
 * declared index cannot be created — or, with {@code postgresql.schema.auto: false}, is missing. The
 * same contract as {@code mongodb.index.auto}</td></tr>
 * </table>
 *
 * <p>
 * Runs at {@code order() = 0} (persistence before transport). Discovered via
 * {@link java.util.ServiceLoader}.
 * </p>
 */
public final class PostgresAutoConfiguration implements IApiAutoConfiguration {

    /** The setting naming who creates the declared indexes — the twin of {@code mongodb.index.auto}. */
    static final String INDEX_AUTO = "postgresql.index.auto";

    /** The boolean spellings {@code postgresql.schema.auto} uses, mapped onto the modes they mean here. */
    private static final Map<String, PgIndexMode> BOOLEAN_ALIASES = Map.of(
            "true", PgIndexMode.CREATE,
            "false", PgIndexMode.NONE);

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
        PgIndexMode indexMode = indexMode(config);
        PgSchemaRegistry registry = new PgSchemaRegistry();

        // One PgDao per domain, all sharing the pool and the registry; the table is the domain name.
        context.registerDefaultDao(
                (domainName, dtoClass) -> new PgDao(dataSource, domainName, registry, mode, indexMode));
    }

    /**
     * Reads {@code postgresql.index.auto}. Unset means {@link PgIndexMode#CREATE}. A value that names no
     * mode is an error rather than a silent fallback: a misspelt {@code strict} quietly downgraded to
     * {@code create} would be a database not holding the constraint someone deliberately demanded.
     *
     * @param config the application configuration
     * @return the mode to build every DAO with
     * @throws ApiException when the configured value names no mode
     */
    static PgIndexMode indexMode(IConfig config) throws ApiException {
        String configured = config.getString(INDEX_AUTO).orElse("");
        if (configured.isBlank()) {
            return PgIndexMode.CREATE;
        }
        PgIndexMode fromBoolean = BOOLEAN_ALIASES.get(configured.trim().toLowerCase(Locale.ROOT));
        if (fromBoolean != null) {
            return fromBoolean;
        }
        return PgIndexMode.parse(configured)
                .orElseThrow(() -> new ApiException(INDEX_AUTO + " is '" + configured
                        + "', which names no mode. Use 'create' (the default: create the declared indexes "
                        + "that are missing), 'none' (touch no index), or 'strict' (create, and refuse to "
                        + "start when one cannot be created)."));
    }

    /** The pool settings. Package-private so the test can check them without opening a connection. */
    static HikariConfig poolConfig(String url, IConfig config) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        config.getString("postgresql.user").ifPresent(pool::setUsername);
        config.getString("postgresql.password").ifPresent(pool::setPassword);
        pool.setMaximumPoolSize(config.getInt("postgresql.pool.size").orElse(DEFAULT_POOL_SIZE));
        pool.setPoolName("garganttua-postgresql");
        // A batch of INSERTs — the rows of a collection — goes as ONE multi-row INSERT rather than one
        // statement per row: the server parses, plans and executes once.
        pool.addDataSourceProperty("reWriteBatchedInserts", "true");
        // Fail at startup on a server that cannot be reached, not at the first request.
        pool.setInitializationFailTimeout(1);
        return pool;
    }
}
