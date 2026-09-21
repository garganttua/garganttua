package com.garganttua.api.starter.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IConfig;
import com.zaxxer.hikari.HikariConfig;

@DisplayName("PostgresAutoConfiguration")
@ExtendWith(MockitoExtension.class)
class PostgresAutoConfigurationTest {

    @Mock
    private IApiBuilder apiBuilder;

    @Mock
    private IConfig config;

    private final PostgresAutoConfiguration autoConfig = new PostgresAutoConfiguration();

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
        @DisplayName("without postgresql.url, naming the key and the environment variable")
        void urlIsRequired() {
            ApiException refused = assertThrows(ApiException.class,
                    () -> autoConfig.apply(new AutoConfigurationContext(apiBuilder, config)));
            assertTrue(refused.getMessage().contains("postgresql.url"));
            assertTrue(refused.getMessage().contains("GARGANTTUA_POSTGRESQL_URL"));
        }

        @Test
        @DisplayName("on a URL that is not a PostgreSQL JDBC URL")
        void urlMustBePostgres() {
            when(config.getString("postgresql.url")).thenReturn(Optional.of("mongodb://localhost:27017"));
            ApiException refused = assertThrows(ApiException.class,
                    () -> autoConfig.apply(new AutoConfigurationContext(apiBuilder, config)));
            assertTrue(refused.getMessage().contains("jdbc:postgresql:"));
        }
    }

    @Nested
    @DisplayName("configures the pool")
    class Pool {

        @Test
        @DisplayName("from user, password and pool size")
        void readsCredentialsAndSize() {
            when(config.getString("postgresql.user")).thenReturn(Optional.of("app"));
            when(config.getString("postgresql.password")).thenReturn(Optional.of("secret"));
            when(config.getInt("postgresql.pool.size")).thenReturn(Optional.of(4));

            HikariConfig pool = PostgresAutoConfiguration.poolConfig("jdbc:postgresql://db:5432/app", config);

            assertEquals("jdbc:postgresql://db:5432/app", pool.getJdbcUrl());
            assertEquals("app", pool.getUsername());
            assertEquals("secret", pool.getPassword());
            assertEquals(4, pool.getMaximumPoolSize());
        }

        @Test
        @DisplayName("with a bounded default size, and failing fast on an unreachable server")
        void defaults() {
            HikariConfig pool = PostgresAutoConfiguration.poolConfig("jdbc:postgresql://db:5432/app", config);

            assertEquals(PostgresAutoConfiguration.DEFAULT_POOL_SIZE, pool.getMaximumPoolSize());
            assertTrue(pool.getInitializationFailTimeout() > 0,
                    "a server that cannot be reached must fail the startup, not the first request");
        }
    }
}
