package com.garganttua.dao.sqlite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * A fresh SQLite database file per caller, configured as the starter configures its pool: WAL journal, a busy
 * timeout, foreign keys on.
 */
public final class SqliteTestDatabase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Path DIRECTORY = Path.of("target", "sqlite-tests");
    private static final int BUSY_TIMEOUT_MS = 10_000;

    private SqliteTestDatabase() {
    }

    /** {@return a data source on a new, empty database file} */
    public static DataSource freshDatabase() {
        try {
            Files.createDirectories(DIRECTORY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path file = DIRECTORY.resolve("db-" + ProcessHandle.current().pid() + "-" + SEQUENCE.incrementAndGet()
                + ".sqlite");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        config.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return source;
    }
}
