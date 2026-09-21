package com.garganttua.dao.postgresql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * A REAL PostgreSQL for the tests — one server per JVM, one fresh database per caller.
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
 * The C locale is forced: {@code initdb} refuses to start when the host's {@code LANG} names a locale
 * that is not generated, which is common on developer machines.
 * </p>
 */
public final class PgTestDatabase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static EmbeddedPostgres server;

    private PgTestDatabase() {
        // Static holder
    }

    /**
     * A data source on a brand-new, empty database: tests never see each other's tables.
     *
     * @return the data source
     */
    public static synchronized DataSource freshDatabase() {
        String name = "t" + ProcessHandle.current().pid() + "_" + SEQUENCE.incrementAndGet();
        try (Connection c = server().getPostgresDatabase().getConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        return server().getDatabase("postgres", name);
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder()
                        .setLocaleConfig("locale", "C")
                        .setLocaleConfig("encoding", "UTF8")
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start the embedded PostgreSQL", e);
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
