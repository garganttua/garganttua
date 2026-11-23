package com.garganttua.api.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.garganttua.api.binding.jackson.JacksonJsonSerializer;
import com.garganttua.api.binding.javalin.JavalinInterface;
import com.garganttua.api.binding.javalin.JavalinProtocol;
import com.garganttua.api.commons.context.HttpCachePolicy;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.cache.redis.RedisCache;
import com.garganttua.core.cache.redis.RedisNearCache;
import com.garganttua.core.reflection.IClass;

/**
 * Every cache level, with and without, against a real MongoDB and PostgreSQL (and redis for the distributed
 * implementations). Run with
 * {@code mvn -Pbench test -pl :garganttua-api-cache-benchmark -Dbench=true}, the stores on their default addresses
 * ({@code -Dbench.mongo}, {@code -Dbench.postgres}, {@code -Dbench.redis} to change them). Writes
 * {@code target/cache-benchmark.md}.
 *
 * <p>
 * Single-threaded, on one machine: latencies are those of one client, store and redis on the loopback. What the
 * cache saves is store round trips; on a network the gaps widen, they do not close.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheBenchmarkTest {

    private static final int ITEMS = 5_000;
    private static final int HOT = 500;
    private static final int UUID_READS = 50_000;
    private static final int QUERY_READS = 5_000;
    private static final int PLACES = 30_000;
    private static final int VIEWPORTS = 300;
    private static final int HTTP_GETS = 1_000;
    private static final int PRODUCTS = 200;
    private static final int WARMUP = 2_000;

    private BenchApi bench;
    private final StringBuilder report = new StringBuilder();

    @BeforeAll
    void connect() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"), "-Dbench=true not set");
        this.bench = new BenchApi();
        this.report.append("# Cache benchmark\n\n").append(LocalDateTime.now().withNano(0)).append(" — ")
                .append(Runtime.getRuntime().availableProcessors()).append(" cores, JDK ")
                .append(Runtime.version()).append(", single-threaded client, stores in Docker on the loopback.\n\n")
                .append("Columns: operations per second, mean / p50 / p99 latency in µs, queries that reached the store.\n");
    }

    @AfterAll
    void write() throws Exception {
        if (this.bench == null) {
            return;
        }
        this.bench.close();
        Path out = Path.of("target", "cache-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, this.report, StandardCharsets.UTF_8);
        System.out.println(this.report);
    }

    private static final String HEADER = "| variant | ops/s | mean µs | p50 µs | p99 µs | store queries |\n|---|---|---|---|---|---|";

    /** The implementations a cache level is measured with; {@code null} type: no cache. */
    private record Variant(String label, Class<?> type, CacheSpec spec) {
    }

    private static List<Variant> uuidVariants() {
        return List.of(new Variant("no cache", null, null),
                new Variant("LocalCache", LocalCache.class, CacheSpec.of()),
                new Variant("RedisNearCache", RedisNearCache.class, CacheSpec.of()),
                new Variant("RedisCache", RedisCache.class, CacheSpec.of().codec(BenchApi.JAVA)));
    }

    // ───────────────────────── assembly ─────────────────────────

    private IDomain<?> items(BenchApi.Store store, AtomicLong queries, Consumer<IDomainBuilder<?>> caching)
            throws Exception {
        this.bench.flushRedis();
        IApiBuilder builder = BenchApi.newBuilder();
        IDomainBuilder<?> domain = builder.domain(IClass.getClass(Rows.Item.class))
                .tenant(true).superTenant("superTenant")
                .entity().id("id").uuid("uuid").tenantId("tenantId").update("name").index("kind").up()
                .dto(IClass.getClass(Rows.ItemDto.class)).id("id").uuid("uuid").tenantId("tenantId")
                .db(this.bench.dao(store, "items", queries)).up();
        caching.accept(domain);
        domain.security().disable(true).up().up();
        return BenchApi.start(builder).getDomain("items").orElseThrow();
    }

    private IDomain<?> places(BenchApi.Store store, AtomicLong queries, Consumer<IDomainBuilder<?>> caching)
            throws Exception {
        this.bench.flushRedis();
        IApiBuilder builder = BenchApi.newBuilder();
        IDomainBuilder<?> domain = builder.domain(IClass.getClass(Rows.Place.class))
                .tenant(true).superTenant("superTenant")
                .entity().id("id").uuid("uuid").tenantId("tenantId").index("latitude").up()
                .dto(IClass.getClass(Rows.PlaceDto.class)).id("id").uuid("uuid").tenantId("tenantId")
                .db(this.bench.dao(store, "places", queries)).up();
        caching.accept(domain);
        domain.security().disable(true).up().up();
        return BenchApi.start(builder).getDomain("places").orElseThrow();
    }

    private static Consumer<IDomainBuilder<?>> none() {
        return domain -> { };
    }

    // ───────────────────────── scenarios ─────────────────────────

    @Test
    void benchmark() throws Exception {
        for (BenchApi.Store store : BenchApi.Store.values()) {
            this.bench.reset();
            this.report.append("\n## ").append(store).append("\n");
            uuidReads(store);
            searches(store);
            map(store);
        }
        http();
    }

    /** L2 / L3: reads by uuid — what token verification makes twice per authenticated request. */
    private void uuidReads(BenchApi.Store store) throws Exception {
        AtomicLong seeding = new AtomicLong();
        IRepository seed = items(store, seeding, none()).getRepository();
        for (int i = 0; i < ITEMS; i++) {
            seed.save(item(i));
        }
        for (boolean writes : new boolean[] { false, true }) {
            this.report.append("\n### Reads by uuid (L2/L3) — ").append(ITEMS).append(" entities, ")
                    .append(UUID_READS).append(" reads, 80 % on ").append(HOT).append(" of them")
                    .append(writes ? ", 2 % writes\n\n" : ", no writes\n\n").append(HEADER).append('\n');
            for (Variant variant : uuidVariants()) {
                AtomicLong queries = new AtomicLong();
                IRepository repository = items(store, queries, domain -> {
                    if (variant.type() != null) {
                        ICacheManager caches = this.bench.caches();
                        domain.cache(caches, IClass.getClass(variant.type()), variant.spec());
                    }
                }).getRepository();
                Random random = new Random(1);
                for (int i = 0; i < WARMUP; i++) {
                    repository.getByUuid(uuid(random));
                }
                queries.set(0);
                Timing timing = new Timing();
                timing.start();
                for (int i = 0; i < UUID_READS; i++) {
                    String uuid = uuid(random);
                    long t = System.nanoTime();
                    Object read = repository.getByUuid(uuid).orElseThrow();
                    if (!uuid.equals(((Rows.Item) read).getUuid())) {
                        throw new AssertionError(variant.label() + " returned " + ((Rows.Item) read).getUuid()
                                + " for " + uuid);
                    }
                    if (writes && i % 50 == 0) {
                        ((Rows.Item) read).setName("renamed " + i);
                        repository.save(read);
                    }
                    timing.add(System.nanoTime() - t);
                }
                timing.stop();
                this.report.append(timing.row(variant.label(), queries.get())).append('\n');
            }
        }
    }

    /** L4: the same searches again and again — a dashboard. */
    private void searches(BenchApi.Store store) throws Exception {
        List<Variant> variants = List.of(new Variant("no cache", null, null),
                new Variant("query cache, LocalCache", LocalCache.class, CacheSpec.of()),
                new Variant("query cache, RedisNearCache", RedisNearCache.class, CacheSpec.of()));
        for (boolean writes : new boolean[] { false, true }) {
            this.report.append("\n### Searches (L4) — 20 distinct (tenant × kind), ").append(QUERY_READS)
                    .append(" reads").append(writes ? ", 2 % writes\n\n" : ", no writes\n\n").append(HEADER)
                    .append('\n');
            for (Variant variant : variants) {
                AtomicLong queries = new AtomicLong();
                IRepository repository = items(store, queries, domain -> {
                    if (variant.type() != null) {
                        domain.queryCache(this.bench.caches(), IClass.getClass(variant.type()), variant.spec());
                    }
                }).getRepository();
                Random random = new Random(2);
                for (int i = 0; i < 200; i++) {
                    repository.getEntities(Optional.empty(), Optional.of(search(random)), Optional.empty());
                }
                queries.set(0);
                Timing timing = new Timing();
                timing.start();
                for (int i = 0; i < QUERY_READS; i++) {
                    Filter filter = search(random);
                    long t = System.nanoTime();
                    repository.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
                    if (writes && i % 50 == 0) {
                        Object one = repository.getByUuid(uuid(random)).orElseThrow();
                        repository.save(one);
                    }
                    timing.add(System.nanoTime() - t);
                }
                timing.stop();
                this.report.append(timing.row(variant.label(), queries.get())).append('\n');
            }
        }
    }

    /** Tiles: a map panned and zoomed — viewports that never repeat exactly. */
    private void map(BenchApi.Store store) throws Exception {
        AtomicLong seeding = new AtomicLong();
        IRepository seed = places(store, seeding, none()).getRepository();
        Random random = new Random(3);
        for (int i = 0; i < PLACES; i++) {
            Rows.Place place = new Rows.Place();
            place.setId("place-" + i);
            place.setUuid("uuid-place-" + i);
            place.setTenantId("T" + (i % 5));
            place.setKind(i % 3 == 0 ? "center" : "home");
            place.setLatitude(42.5 + random.nextDouble() * 8.5);
            place.setLongitude(-4.5 + random.nextDouble() * 12.5);
            seed.save(place);
        }
        List<Filter> viewports = viewports();
        List<Integer> expected = new ArrayList<>();
        this.report.append("\n### Map (tiles) — ").append(PLACES).append(" places, ").append(VIEWPORTS)
                .append(" viewports panned and zoomed (0.05° to 1.5°), one tenant\n\n").append(HEADER).append('\n');
        record MapVariant(String label, Consumer<IDomainBuilder<?>> caching) {
        }
        List<MapVariant> variants = List.of(
                new MapVariant("no cache", none()),
                new MapVariant("query cache (exact key), LocalCache", d -> d.queryCache(this.bench.caches(),
                        IClass.getClass(LocalCache.class), CacheSpec.of())),
                new MapVariant("tiles, LocalCache", d -> d.tileCache(this.bench.caches(),
                        IClass.getClass(LocalCache.class), CacheSpec.of(), "latitude", "longitude")),
                new MapVariant("tiles, RedisNearCache", d -> d.tileCache(this.bench.caches(),
                        IClass.getClass(RedisNearCache.class), CacheSpec.of(), "latitude", "longitude")));
        for (MapVariant variant : variants) {
            AtomicLong queries = new AtomicLong();
            IRepository repository = places(store, queries, variant.caching()).getRepository();
            Timing timing = new Timing();
            timing.start();
            for (int i = 0; i < viewports.size(); i++) {
                long t = System.nanoTime();
                int rows = repository.getEntities(Optional.empty(), Optional.of(viewports.get(i)), Optional.empty())
                        .size();
                timing.add(System.nanoTime() - t);
                if (expected.size() < viewports.size()) {
                    expected.add(rows);
                } else {
                    assertEquals(expected.get(i), rows, variant.label() + ": viewport " + i + " differs from the store");
                }
            }
            timing.stop();
            this.report.append(timing.row(variant.label(), queries.get())).append('\n');
        }
        this.report.append("\nEvery cached variant returned, for every viewport, the rows the store returned.\n");
    }

    /** L5: a list over HTTP — the body every time, or a 304 when the client's copy is current. */
    private void http() throws Exception {
        this.report.append("\n## HTTP (L5) — GET of a ").append(PRODUCTS).append("-row list over Javalin, MongoDB, ")
                .append(HTTP_GETS).append(" requests\n\n")
                .append("| variant | req/s | mean µs | p50 µs | p99 µs | store queries | bytes received |\n")
                .append("|---|---|---|---|---|---|---|\n");
        record HttpVariant(String label, boolean etag, boolean queryCache) {
        }
        int port = 7300;
        for (HttpVariant variant : List.of(new HttpVariant("no HTTP cache", false, false),
                new HttpVariant("ETag + If-None-Match (304)", true, false),
                new HttpVariant("ETag (304) + query cache", true, true))) {
            AtomicLong queries = new AtomicLong();
            this.bench.flushRedis();
            IApiBuilder builder = BenchApi.newBuilder();
            builder.serializer(new JacksonJsonSerializer());
            builder.protocol(new JavalinProtocol());
            IDomainBuilder<?> domain = builder.domain(IClass.getClass(Rows.Product.class))
                    .tenant(true).superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(Rows.ProductDto.class)).id("id").uuid("uuid").tenantId("tenantId")
                    .db(this.bench.dao(BenchApi.Store.MONGODB, "products-" + port, queries)).up();
            if (variant.etag()) {
                domain.httpCache(HttpCachePolicy.revalidated());
            }
            if (variant.queryCache()) {
                domain.queryCache(this.bench.caches(), IClass.getClass(LocalCache.class), CacheSpec.of());
            }
            domain.security().disable(true).up().up();
            IDomain<?> products = BenchApi.start(builder).getDomain("products").orElseThrow();
            for (int i = 0; i < PRODUCTS; i++) {
                Rows.Product product = new Rows.Product();
                product.setId("product-" + i);
                product.setUuid("uuid-product-" + i);
                product.setTenantId(BenchApi.SUPER_TENANT);
                product.setKind("k" + (i % 4));
                product.setName("Product " + i);
                product.setPayload("x".repeat(200));
                products.getRepository().save(product);
            }
            JavalinInterface server = new JavalinInterface(port);
            server.handle(products);
            server.onInit();
            server.onStart();
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + port + "/products");
            String etag = null;
            for (int i = 0; i < 100; i++) {
                etag = get(client, uri, null).headers().firstValue("ETag").orElse(null);
            }
            queries.set(0);
            long bytes = 0;
            Timing timing = new Timing();
            timing.start();
            for (int i = 0; i < HTTP_GETS; i++) {
                long t = System.nanoTime();
                HttpResponse<byte[]> response = get(client, uri, variant.etag() ? etag : null);
                timing.add(System.nanoTime() - t);
                bytes += response.body().length;
            }
            timing.stop();
            server.onStop();
            this.report.append(timing.row(variant.label(), queries.get())).append(String.format(Locale.ROOT, " %,d |", bytes))
                    .append('\n');
            port++;
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, URI uri, String ifNoneMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // ───────────────────────── data ─────────────────────────

    private static Rows.Item item(int i) {
        Rows.Item item = new Rows.Item();
        item.setId("item-" + i);
        item.setUuid("uuid-item-" + i);
        item.setTenantId("T" + (i % 5));
        item.setKind("k" + (i % 4));
        item.setName("Item " + i);
        item.setPayload("p".repeat(200));
        return item;
    }

    private static String uuid(Random random) {
        int index = random.nextDouble() < 0.8 ? random.nextInt(HOT) : random.nextInt(ITEMS);
        return "uuid-item-" + index;
    }

    private static Filter search(Random random) {
        return Filter.and(Filter.eq("tenantId", "T" + random.nextInt(5)), Filter.eq("kind", "k" + random.nextInt(4)));
    }

    /** A map session: pans of 10 to 40 % of the view, a zoom now and then, within France. */
    private static List<Filter> viewports() {
        Random random = new Random(4);
        double lat = 46.5;
        double lon = 2.5;
        double span = 0.4;
        List<Filter> filters = new ArrayList<>();
        for (int i = 0; i < VIEWPORTS; i++) {
            if (random.nextDouble() < 0.15) {
                span = Math.max(0.05, Math.min(1.5, span * (random.nextBoolean() ? 0.6 : 1.6)));
            } else {
                lat += (random.nextDouble() - 0.5) * 0.8 * span;
                lon += (random.nextDouble() - 0.5) * 0.8 * span;
            }
            lat = Math.max(43, Math.min(50, lat));
            lon = Math.max(-3, Math.min(7, lon));
            filters.add(Filter.and(Filter.gte("latitude", lat), Filter.lte("latitude", lat + span),
                    Filter.gte("longitude", lon), Filter.lte("longitude", lon + span), Filter.eq("tenantId", "T1")));
        }
        return filters;
    }
}
