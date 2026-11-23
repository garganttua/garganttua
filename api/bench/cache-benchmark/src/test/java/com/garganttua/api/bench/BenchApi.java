package com.garganttua.api.bench;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.core.cache.ICacheCodec;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.cache.dsl.CacheManagerBuilder;
import com.garganttua.core.cache.redis.RedisCache;
import com.garganttua.core.cache.redis.RedisCacheFactory;
import com.garganttua.core.cache.redis.RedisNearCache;
import com.garganttua.core.cache.redis.RedisNearCacheFactory;
import com.garganttua.core.dsl.dependency.IDependentBuilder;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.injection.context.dsl.InjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.RuntimeContextFactory;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.core.workflow.dsl.IWorkflowsBuilder;
import com.garganttua.core.workflow.dsl.WorkflowsBuilder;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.mongodb.MongoIndexMode;
import com.garganttua.dao.postgresql.PgDao;
import com.garganttua.dao.postgresql.PgSchemaRegistry;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** The stores, the caches and the api assembly the benchmark runs on. */
final class BenchApi implements AutoCloseable {

    /** The store a run reads from. */
    enum Store { MONGODB, POSTGRESQL }

    static final String SUPER_TENANT = "SUPER_TENANT";

    private final MongoClient mongo;
    final MongoDatabase mongoDatabase;
    final HikariDataSource postgres;
    final JedisPool redis;
    private final RedisNearCacheFactory nearCaches;
    private final RedisCacheFactory sharedCaches;

    BenchApi() {
        this.mongo = MongoClients.create(System.getProperty("bench.mongo", "mongodb://127.0.0.1:27099"));
        this.mongoDatabase = this.mongo.getDatabase("bench");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("bench.postgres", "jdbc:postgresql://127.0.0.1:54399/bench"));
        config.setUsername(System.getProperty("bench.postgres.user", "postgres"));
        config.setPassword(System.getProperty("bench.postgres.password", "bench"));
        config.setMaximumPoolSize(8);
        this.postgres = new HikariDataSource(config);
        String[] redisAddress = System.getProperty("bench.redis", "127.0.0.1:6392").split(":");
        this.redis = new JedisPool(redisAddress[0], Integer.parseInt(redisAddress[1]));
        // One factory each for the whole run, as an application has: the near caches share one subscription.
        this.nearCaches = new RedisNearCacheFactory(this.redis);
        this.sharedCaches = new RedisCacheFactory(this.redis);
    }

    /** Empties every store. */
    void reset() throws Exception {
        this.mongoDatabase.drop();
        try (Connection c = this.postgres.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }
        flushRedis();
    }

    void flushRedis() {
        try (Jedis jedis = this.redis.getResource()) {
            jedis.flushAll();
        }
    }

    /** {@return the DAO of {@code collection} in {@code store}, counting the queries it receives} */
    IDao dao(Store store, String collection, AtomicLong queries) {
        IDao target = store == Store.MONGODB ? new MongoDao(this.mongoDatabase, collection, MongoIndexMode.CREATE)
                : new PgDao(this.postgres, collection, new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.CREATE);
        return (IDao) Proxy.newProxyInstance(IDao.class.getClassLoader(), new Class<?>[] { IDao.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("find".equals(name) || "findPage".equals(name) || "count".equals(name)) {
                        queries.incrementAndGet();
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** {@return a cache manager serving the in-memory and the redis implementations} */
    ICacheManager caches() {
        return CacheManagerBuilder.builder()
                .withFactory(IClass.getClass(RedisNearCache.class), this.nearCaches)
                .withFactory(IClass.getClass(RedisCache.class), this.sharedCaches)
                .build();
    }

    /** Java serialization — what RedisCache needs to carry the cached DTOs. */
    static final ICacheCodec<Object> JAVA = new ICacheCodec<>() {
        @Override
        public byte[] encode(Object value) {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
                out.flush();
                return bytes.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object decode(byte[] bytes) {
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                return in.readObject();
            } catch (IOException | ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    /** The builder chain an application server assembles, as the DAO end-to-end tests do. */
    @SuppressWarnings("unchecked")
    static IApiBuilder newBuilder() throws ApiException {
        IReflectionBuilder reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner());
        IClass.setReflection(reflection.build());
        IInjectionContextBuilder injection = InjectionContextBuilder.builder()
                .childContextFactory(new RuntimeContextFactory());
        ((IDependentBuilder<IInjectionContextBuilder, ?>) injection).provide(reflection);
        injection.build();
        IExpressionContextBuilder expressions = ExpressionContextBuilder.builder();
        expressions.autoDetect(true);
        expressions.withPackage("com.garganttua.core.expression.functions");
        expressions.withPackage("com.garganttua.core.script.functions");
        expressions.withPackage("com.garganttua.core.observability");
        expressions.withPackage("com.garganttua.api.core.expression");
        ((IDependentBuilder<IExpressionContextBuilder, ?>) expressions).provide(injection);
        expressions.build();
        IRuntimesBuilder runtimes = RuntimesBuilder.builder();
        ((IDependentBuilder<IRuntimesBuilder, ?>) runtimes).provide(injection);
        IScriptsBuilder scripts = ScriptsBuilder.builder();
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(injection);
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(expressions);
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(runtimes);
        IWorkflowsBuilder workflows = WorkflowsBuilder.builder();
        ((IDependentBuilder<IWorkflowsBuilder, ?>) workflows).provide(injection);
        ((IDependentBuilder<IWorkflowsBuilder, ?>) workflows).provide(scripts);
        IApiBuilder builder = ApiBuilder.builder();
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(reflection);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(injection);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(expressions);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(workflows);
        return builder.superTenantId(SUPER_TENANT).superTenantAutoCreate(false);
    }

    static IApi start(IApiBuilder builder) throws ApiException {
        IApi api = builder.build();
        api.onInit();
        api.onStart();
        return api;
    }

    @Override
    public void close() {
        this.nearCaches.close();
        this.mongo.close();
        this.postgres.close();
        this.redis.close();
    }
}
