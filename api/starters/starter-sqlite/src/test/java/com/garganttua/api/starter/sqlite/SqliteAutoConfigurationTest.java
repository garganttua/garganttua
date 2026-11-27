package com.garganttua.api.starter.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.zaxxer.hikari.HikariDataSource;

@DisplayName("SqliteAutoConfiguration")
@ExtendWith(MockitoExtension.class)
class SqliteAutoConfigurationTest {

    @Mock
    private IApiBuilder apiBuilder;

    @Mock
    private IConfig config;

    private final SqliteAutoConfiguration autoConfig = new SqliteAutoConfiguration();

    @BeforeEach
    void emptyByDefault() {
        lenient().when(config.getString(anyString())).thenReturn(Optional.empty());
        lenient().when(config.getInt(anyString())).thenReturn(Optional.empty());
        lenient().when(config.getBoolean(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("runs before transport (order 0)")
    void orderIsPersistence() {
        assertEquals(0, autoConfig.order());
    }

    @Nested
    @DisplayName("refuses to start")
    class Refusals {

        @Test
        @DisplayName("without sqlite.path nor sqlite.url, naming the key and the environment variable")
        void pathIsRequired() {
            ApiException refused = assertThrows(ApiException.class,
                    () -> autoConfig.apply(new AutoConfigurationContext(apiBuilder, config)));
            assertTrue(refused.getMessage().contains("sqlite.path"));
            assertTrue(refused.getMessage().contains("GARGANTTUA_SQLITE_PATH"));
        }

        @Test
        @DisplayName("on a URL that is not a SQLite JDBC URL")
        void urlMustBeSqlite() {
            when(config.getString("sqlite.url")).thenReturn(Optional.of("jdbc:postgresql://db/app"));
            ApiException refused = assertThrows(ApiException.class, () -> SqliteAutoConfiguration.url(config));
            assertTrue(refused.getMessage().contains("jdbc:sqlite:"));
        }

        @Test
        @DisplayName("on a private in-memory database, which each pooled connection would see empty")
        void noPrivateMemory() {
            when(config.getString("sqlite.url")).thenReturn(Optional.of("jdbc:sqlite::memory:"));
            ApiException refused = assertThrows(ApiException.class, () -> SqliteAutoConfiguration.url(config));
            assertTrue(refused.getMessage().contains("in-memory"), refused.getMessage());
        }
    }

    @Nested
    @DisplayName("opens the database")
    class Pool {

        @Test
        @DisplayName("sqlite.path creates the file's directory and becomes the JDBC URL")
        void path(@TempDir Path dir) throws ApiException {
            Path file = dir.resolve("data").resolve("app.db");
            when(config.getString("sqlite.path")).thenReturn(Optional.of(file.toString()));
            assertEquals("jdbc:sqlite:" + file.toAbsolutePath(), SqliteAutoConfiguration.url(config));
            assertTrue(Files.isDirectory(file.getParent()));
        }

        @Test
        @DisplayName("every connection in WAL mode, with foreign keys and the configured busy timeout")
        void pragmas(@TempDir Path dir) throws Exception {
            when(config.getInt("sqlite.busy.timeout")).thenReturn(Optional.of(2_500));
            when(config.getInt("sqlite.pool.size")).thenReturn(Optional.of(2));
            try (HikariDataSource pool = new HikariDataSource(
                    SqliteAutoConfiguration.poolConfig("jdbc:sqlite:" + dir.resolve("p.db"), config));
                    Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                assertEquals(2, pool.getMaximumPoolSize());
                assertEquals("wal", pragma(st, "journal_mode"));
                assertEquals("1", pragma(st, "foreign_keys"));
                assertEquals("2500", pragma(st, "busy_timeout"));
            }
        }

        private static String pragma(Statement st, String name) throws Exception {
            try (ResultSet rs = st.executeQuery("PRAGMA " + name)) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Nested
    @DisplayName("reads sqlite.index.auto")
    class IndexMode {

        @Test
        @DisplayName("unset means create")
        void defaultsToCreate() throws ApiException {
            assertEquals(SqliteIndexMode.CREATE, SqliteAutoConfiguration.indexMode(config));
        }

        @Test
        @DisplayName("each mode by name, any case, and the booleans of sqlite.schema.auto")
        void spellings() throws ApiException {
            when(config.getString(SqliteAutoConfiguration.INDEX_AUTO))
                    .thenReturn(Optional.of(" Strict "), Optional.of("none"), Optional.of("true"), Optional.of("FALSE"));
            assertEquals(SqliteIndexMode.STRICT, SqliteAutoConfiguration.indexMode(config));
            assertEquals(SqliteIndexMode.NONE, SqliteAutoConfiguration.indexMode(config));
            assertEquals(SqliteIndexMode.CREATE, SqliteAutoConfiguration.indexMode(config));
            assertEquals(SqliteIndexMode.NONE, SqliteAutoConfiguration.indexMode(config));
        }

        @Test
        @DisplayName("a misspelt mode is refused, not downgraded")
        void misspelt() {
            when(config.getString(SqliteAutoConfiguration.INDEX_AUTO)).thenReturn(Optional.of("stric"));
            ApiException refused = assertThrows(ApiException.class, () -> SqliteAutoConfiguration.indexMode(config));
            assertTrue(refused.getMessage().contains("'stric'"), refused.getMessage());
        }
    }
}
