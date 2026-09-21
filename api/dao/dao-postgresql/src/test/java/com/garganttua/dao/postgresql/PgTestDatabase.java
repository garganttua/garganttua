package com.garganttua.dao.postgresql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.postgresql.ds.PGSimpleDataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * A REAL PostgreSQL for the tests — one server per JVM, one fresh, empty SCHEMA per caller.
 *
 * <p>
 * The server is a genuine PostgreSQL 17 process started from Maven-fetched binaries: no Docker and
 * no local install needed. Tests of this DAO prove behaviour against the engine itself, never
 * against a stand-in — a fake that is kinder than the real thing proves nothing, and the platform
 * has already shipped a feature that worked against a fake and failed against every real
 * implementation.
 * </p>
 *
 * <p>
 * <b>Why schemas and not databases.</b> Each caller used to get a new DATABASE, and a
 * {@code CREATE DATABASE} copies the template: about 7.5 MB each. The parity suite asks for several
 * hundred, which filled the host's temp filesystem until PostgreSQL PANICked on a WAL write. A schema
 * isolates tables just as well — the data source pins it with {@code currentSchema} — and costs
 * nothing to create. For the same reason the server's data lives under
 * {@code ~/.cache/garganttua-test}, on disk, not in a RAM-backed {@code /tmp}.
 * </p>
 *
 * <p>
 * The C locale is forced: {@code initdb} refuses to start when the host's {@code LANG} names a locale
 * that is not generated, which is common on developer machines.
 * </p>
 */
public final class PgTestDatabase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String DATABASE = "tests";
    private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "garganttua-test");
    private static EmbeddedPostgres server;

    private PgTestDatabase() {
        // Static holder
    }

    /**
     * A data source on a brand-new, empty schema: tests never see each other's tables.
     *
     * @return the data source
     */
    public static synchronized DataSource freshDatabase() {
        String schema = "t" + ProcessHandle.current().pid() + "_" + SEQUENCE.incrementAndGet();
        DataSource shared = server().getDatabase("postgres", DATABASE);
        try (Connection c = shared.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test schema " + schema, e);
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(server().getJdbcUrl("postgres", DATABASE) + "&currentSchema=" + schema);
        return source;
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder()
                        .setLocaleConfig("locale", "C")
                        .setLocaleConfig("encoding", "UTF8")
                        .setOverrideWorkingDirectory(CACHE.resolve("pg-binaries").toFile())
                        .setDataDirectory(CACHE.resolve("pg-data-" + ProcessHandle.current().pid()))
                        .setCleanDataDirectory(true)
                        .start();
                try (Connection c = server.getPostgresDatabase().getConnection();
                        Statement s = c.createStatement()) {
                    s.execute("CREATE DATABASE " + DATABASE);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the embedded PostgreSQL", e);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create the shared test database", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // The JVM is going away; the process dies with it.
                }
            }, "embedded-postgres-shutdown"));
        }
        return server;
    }
}
