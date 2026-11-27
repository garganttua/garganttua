package com.garganttua.api.starter.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.sql.SqlSchemaRegistry;
import com.garganttua.dao.sqlite.SqliteDao;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Auto-configures SQLite persistence: reads {@code sqlite.*} from the application config, opens ONE pooled
 * {@code DataSource} on the database file, and registers a default DAO factory that yields a {@link SqliteDao} per
 * domain (table = domain name). A domain whose DTO sets an explicit {@code .db(...)} keeps it — the factory is only a
 * fallback.
 *
 * <p>
 * Every DAO it builds shares one {@link SqlSchemaRegistry}: that is what lets a {@code @Composed} field of one domain
 * be resolved against another domain's table.
 * </p>
 *
 * <p>
 * Every pooled connection is opened in WAL mode (readers never wait for the writer), with a busy timeout (a second
 * writer waits for the first instead of failing) and foreign keys on — the child tables of the collections cascade
 * from their entity.
 * </p>
 *
 * <table>
 * <caption>Configuration</caption>
 * <tr><th>Key</th><th>Required</th><th>Meaning</th></tr>
 * <tr><td>{@code sqlite.path}</td><td>one of the two</td><td>the database file — created, with its directory, when
 * missing</td></tr>
 * <tr><td>{@code sqlite.url}</td><td>one of the two</td><td>a JDBC URL, {@code jdbc:sqlite:/var/lib/app/app.db}</td></tr>
 * <tr><td>{@code sqlite.pool.size}</td><td>no</td><td>maximum pooled connections (default 4)</td></tr>
 * <tr><td>{@code sqlite.busy.timeout}</td><td>no</td><td>how long a writer waits for the write lock, in milliseconds
 * (default 10000)</td></tr>
 * <tr><td>{@code sqlite.schema.auto}</td><td>no</td><td>{@code true} (default): create missing tables and columns;
 * {@code false}: create nothing and refuse to start on a schema that does not fit</td></tr>
 * <tr><td>{@code sqlite.index.auto}</td><td>no</td><td>{@code create} (default), {@code none} or {@code strict} — the
 * same contract as {@code mongodb.index.auto}</td></tr>
 * </table>
 *
 * <p>
 * Runs at {@code order() = 0} (persistence before transport). Discovered via {@link java.util.ServiceLoader}.
 * </p>
 */
public final class SqliteAutoConfiguration implements IApiAutoConfiguration {

    /** The setting naming who creates the declared indexes — the twin of {@code mongodb.index.auto}. */
    static final String INDEX_AUTO = "sqlite.index.auto";

    /** Default pool ceiling: SQLite runs one writer at a time; a few connections serve the concurrent readers. */
    static final int DEFAULT_POOL_SIZE = 4;

    /** Default wait for the write lock, in milliseconds. */
    static final int DEFAULT_BUSY_TIMEOUT_MS = 10_000;

    private static final String URL_PREFIX = "jdbc:sqlite:";

    private static final Map<String, SqliteIndexMode> BOOLEAN_ALIASES = Map.of(
            "true", SqliteIndexMode.CREATE,
            "false", SqliteIndexMode.NONE);

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
        String url = url(config);
        HikariDataSource dataSource = new HikariDataSource(poolConfig(url, config));
        context.registerResource(dataSource);

        SqliteSchemaMode mode = config.getBoolean("sqlite.schema.auto").orElse(Boolean.TRUE)
                ? SqliteSchemaMode.CREATE : SqliteSchemaMode.VALIDATE;
        SqliteIndexMode indexMode = indexMode(config);
        SqlSchemaRegistry registry = new SqlSchemaRegistry();

        context.registerDefaultDao(
                (domainName, dtoClass) -> new SqliteDao(dataSource, domainName, registry, mode, indexMode));
    }

    /**
     * The JDBC URL: {@code sqlite.url} as given, or {@code sqlite.path} made one — its directory created.
     *
     * @param config the application configuration
     * @return the URL
     * @throws ApiException when neither is set, the URL is not SQLite's, or names a private in-memory database
     */
    static String url(IConfig config) throws ApiException {
        String url = config.getString("sqlite.url").orElse(null);
        if (url == null) {
            String path = config.getString("sqlite.path").orElseThrow(() -> new ApiException(
                    "sqlite.path (or sqlite.url) is required by the SQLite starter. Set it in application.yaml "
                            + "(sqlite.path: /var/lib/myapp/myapp.db) or via GARGANTTUA_SQLITE_PATH."));
            Path file = Path.of(path).toAbsolutePath();
            Path directory = file.getParent();
            try {
                if (directory != null) {
                    Files.createDirectories(directory);
                }
            } catch (IOException e) {
                throw new ApiException("sqlite.path is '" + path + "', whose directory cannot be created: "
                        + e.getMessage(), e);
            }
            url = URL_PREFIX + file;
        }
        if (!url.startsWith(URL_PREFIX)) {
            throw new ApiException("sqlite.url must be a SQLite JDBC URL starting with '" + URL_PREFIX + "', got '"
                    + url + "'.");
        }
        if (url.substring(URL_PREFIX.length()).isBlank() || url.contains(":memory:")) {
            throw new ApiException("sqlite.url '" + url + "' names a private in-memory database: every pooled "
                    + "connection would see its own, empty one. Name a database file.");
        }
        return url;
    }

    /**
     * Reads {@code sqlite.index.auto}. Unset means {@link SqliteIndexMode#CREATE}; a value that names no mode is an
     * error rather than a silent fallback.
     *
     * @param config the application configuration
     * @return the mode to build every DAO with
     * @throws ApiException when the configured value names no mode
     */
    static SqliteIndexMode indexMode(IConfig config) throws ApiException {
        String configured = config.getString(INDEX_AUTO).orElse("");
        if (configured.isBlank()) {
            return SqliteIndexMode.CREATE;
        }
        SqliteIndexMode fromBoolean = BOOLEAN_ALIASES.get(configured.trim().toLowerCase(Locale.ROOT));
        if (fromBoolean != null) {
            return fromBoolean;
        }
        return SqliteIndexMode.parse(configured)
                .orElseThrow(() -> new ApiException(INDEX_AUTO + " is '" + configured
                        + "', which names no mode. Use 'create' (the default: create the declared indexes "
                        + "that are missing), 'none' (touch no index), or 'strict' (create, and refuse to "
                        + "start when one cannot be created)."));
    }

    /** The pool settings. Package-private so the test can check them without opening a connection. */
    static HikariConfig poolConfig(String url, IConfig config) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setMaximumPoolSize(config.getInt("sqlite.pool.size").orElse(DEFAULT_POOL_SIZE));
        pool.setPoolName("garganttua-sqlite");
        // The pragmas sqlite-jdbc applies to every connection it opens.
        pool.addDataSourceProperty("journal_mode", "WAL");
        pool.addDataSourceProperty("synchronous", "NORMAL");
        pool.addDataSourceProperty("foreign_keys", "true");
        pool.addDataSourceProperty("busy_timeout",
                String.valueOf(config.getInt("sqlite.busy.timeout").orElse(DEFAULT_BUSY_TIMEOUT_MS)));
        // Fail at startup on a file that cannot be opened, not at the first request.
        pool.setInitializationFailTimeout(1);
        return pool;
    }
}
