package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A REAL PostgreSQL WITH PostGIS for the geographic tests — which the embedded engine
 * ({@link PgTestDatabase}) cannot provide: its Maven-fetched binaries carry no PostGIS.
 *
 * <p>
 * The server is given by {@code -Dgarganttua.test.postgis.url} (or the
 * {@code GARGANTTUA_TEST_POSTGIS_URL} environment variable), with {@code .user} / {@code .password}
 * (or {@code _USER} / {@code _PASSWORD}) — for instance the {@code postgis/postgis:17-3.5} image:
 * </p>
 *
 * <pre>
 * docker run -d -e POSTGRES_PASSWORD=gg -e POSTGRES_DB=tests -p 55432:5432 postgis/postgis:17-3.5
 * mvn test -pl :garganttua-api-dao-postgresql -Dgarganttua.test.postgis.url=jdbc:postgresql://localhost:55432/tests \
 *     -Dgarganttua.test.postgis.user=postgres -Dgarganttua.test.postgis.password=gg
 * </pre>
 *
 * <p>
 * Without one, the tests that need it are SKIPPED, not passed: a geographic predicate is only proved
 * by the engine that evaluates it. Each caller gets a fresh schema, with {@code public} — where the
 * extension lives — kept on the search path.
 * </p>
 */
public final class PgPostgisDatabase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private PgPostgisDatabase() {
        // Static holder
    }

    /**
     * A data source on a brand-new schema of the PostGIS server, or the test is skipped.
     *
     * @return the data source
     */
    public static DataSource freshDatabase() {
        String url = setting("garganttua.test.postgis.url", "GARGANTTUA_TEST_POSTGIS_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "PostGIS tests skipped: no server given (-Dgarganttua.test.postgis.url)");
        String schema = "g" + ProcessHandle.current().pid() + "_" + SEQUENCE.incrementAndGet();
        PGSimpleDataSource shared = source(url);
        try (Connection c = shared.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            s.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the PostGIS test schema " + schema, e);
        }
        PGSimpleDataSource source = source(url);
        source.setCurrentSchema(schema + ",public");
        return source;
    }

    private static PGSimpleDataSource source(String url) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        String user = setting("garganttua.test.postgis.user", "GARGANTTUA_TEST_POSTGIS_USER");
        String password = setting("garganttua.test.postgis.password", "GARGANTTUA_TEST_POSTGIS_PASSWORD");
        if (user != null) {
            source.setUser(user);
        }
        if (password != null) {
            source.setPassword(password);
        }
        return source;
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property);
        return value != null ? value : System.getenv(environment);
    }
}
