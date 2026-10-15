package com.garganttua.api.core.perfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;

/**
 * The api under concurrent load, with the database replaced by {@link MockDao} — the throughput of
 * the pipeline itself, and whether it stays correct when several threads share a domain.
 *
 * <p>
 * A server serves requests in parallel; every other performance test of this package serves them one
 * at a time. What only a concurrent run shows is a lock everyone queues on — the throughput then
 * stays flat however many threads are added — and state shared between requests that should not be:
 * two creates handed the same uuid, a response carrying another request's entity.
 * </p>
 *
 * <p>
 * Correctness is asserted strictly: every request answers what it should, and the repository holds
 * exactly what was written. Throughput is asserted loosely — {@code N} threads must serve at least
 * as much as one when the machine has the cores for it — and reported in full: a scaling factor
 * depends on the machine, a lock does not.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-api-core -Dtest='ConcurrentLoadPerfTest' -Dgarganttua.perf=true -Djacoco.skip=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true -Djacoco.skip=true")
@DisplayName("Concurrent load (mocked repository)")
class ConcurrentLoadPerfTest extends AbstractCrudIntegrationTest {

    private static final int OPS_PER_THREAD = Integer.getInteger("garganttua.perf.concurrent.ops", 3_000);
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int THREADS = Math.max(2, Math.min(4, CORES));

    private final List<String> lines = new ArrayList<>();

    private static IDomain<?> usersDomain(MockDao dao) throws Exception {
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .update("name", true)
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security().disable(true).up()
            .up();
        IApi api = buildAndStart(builder);
        return api.getDomain("users").orElseThrow();
    }

    /** Requests served per second, and how many of them answered something else than expected. */
    private record Load(String label, int threads, int requests, double seconds, int failures) {

        double perSecond() {
            return this.requests / this.seconds;
        }

        String render() {
            return String.format(Locale.ROOT, "%-34s %7d %9d %10.0f %9d",
                    this.label, this.threads, this.requests, perSecond(), this.failures);
        }
    }

    /**
     * Runs {@code operation} {@code OPS_PER_THREAD} times on each of {@code threads} threads, all
     * released at once, and times the whole. The operation returns normally when the request
     * succeeded and throws when it did not; failures are counted, not propagated, so one bad answer
     * does not hide how many there were.
     */
    private Load load(String label, int threads, IntConsumer operation) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            int base = t * OPS_PER_THREAD;
            pool.execute(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        try {
                            operation.accept(base + i);
                        } catch (RuntimeException | AssertionError e) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        assertTrue(done.await(5, TimeUnit.MINUTES), label + " did not finish");
        double seconds = (System.nanoTime() - start) / 1e9d;
        pool.shutdownNow();
        Load result = new Load(label, threads, threads * OPS_PER_THREAD, seconds, failures.get());
        this.lines.add(result.render());
        return result;
    }

    private static void expect(IOperationResponse response, OperationResponseCode code) {
        if (response.getResponseCode() != code) {
            throw new AssertionError("expected " + code + ", got " + response.getResponseCode());
        }
    }

    private static void readOne(IDomain<?> domain, int i) {
        OperationRequest request = superTenantRequest(
                OperationDefinition.readOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-" + (i % 100));
        IOperationResponse response = domain.invoke(request);
        expect(response, OperationResponseCode.OK);
        // The response must be the entity that was asked for, not another thread's.
        if (!(response.getResponse() instanceof User user) || !("uuid-" + (i % 100)).equals(user.getUuid())) {
            throw new AssertionError("readOne uuid-" + (i % 100) + " answered " + response.getResponse());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readOne: N threads serve at least as much as one, and each gets its own entity")
    void readsScaleAndStayIsolated() throws Exception {
        IDomain<?> domain = usersDomain(new MockDao().seed(100));
        load("warm-up", THREADS, i -> readOne(domain, i));

        Load single = load("readOne — 1 thread", 1, i -> readOne(domain, i));
        Load parallel = load("readOne — " + THREADS + " threads", THREADS, i -> readOne(domain, i));

        assertEquals(0, single.failures() + parallel.failures(), "every read answers its own entity" + report());
        double scaling = parallel.perSecond() / single.perSecond();
        System.out.printf(Locale.ROOT, "%nreadOne: %d threads serve %.2fx one thread (%d cores).%n",
                THREADS, scaling, CORES);
        assumeFalse(PerfHarness.coverageAgentAttached(),
                "JaCoCo instruments this JVM: its shared probes make threads contend, the scaling "
                        + "would measure the agent — rerun with -Djacoco.skip=true");
        if (CORES >= 2) {
            // A lock every request queues on keeps this at or below 1; a pipeline that runs requests
            // side by side lands well above. 1.0 is the line between the two, with no margin taken
            // on the machine's behalf: a parallel run SLOWER than a serial one is never noise.
            assertTrue(scaling >= 1.0, () -> String.format(Locale.ROOT,
                    "%d threads serve %.2fx one thread — requests queue on something shared.%s",
                    THREADS, scaling, report()));
        }
    }

    @Test
    @DisplayName("createOne: concurrent creates never collide, and every one is stored")
    void concurrentCreatesAreAllStored() throws Exception {
        MockDao dao = new MockDao();
        IDomain<?> domain = usersDomain(dao);
        Set<String> uuids = ConcurrentHashMap.newKeySet();

        Load creates = load("createOne — " + THREADS + " threads", THREADS, i -> {
            User user = new User();
            user.setName("user-" + i);
            OperationRequest request = superTenantRequest(
                    OperationDefinition.createOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.BODY, user);
            IOperationResponse response = domain.invoke(request);
            expect(response, OperationResponseCode.CREATED);
            uuids.add(((User) response.getResponse()).getUuid());
        });

        int expected = THREADS * OPS_PER_THREAD;
        assertEquals(0, creates.failures(), "every create succeeds" + report());
        assertEquals(expected, uuids.size(), "every create was given its own uuid");
        assertEquals(expected, dao.size(), "every create reached the repository");
    }

    @Test
    @DisplayName("updates and reads of the same entities, interleaved across threads, all succeed")
    void mixedLoadStaysCorrect() throws Exception {
        MockDao dao = new MockDao().seed(100);
        IDomain<?> domain = usersDomain(dao);

        Load mixed = load("mixed 3 reads : 1 update", THREADS, i -> {
            if (i % 4 != 0) {
                readOne(domain, i);
                return;
            }
            User body = new User();
            body.setName("renamed-" + i);
            OperationRequest request = superTenantRequest(
                    OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.ENTITY_UUID, "uuid-" + (i % 100));
            request.arg(IOperationRequest.BODY, body);
            request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);
            expect(domain.invoke(request), OperationResponseCode.UPDATED);
        });

        assertEquals(0, mixed.failures(), "no request fails under a mixed load" + report());
        assertEquals(100, dao.size(), "updates replace, they do not add");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String report() {
        StringBuilder sb = new StringBuilder(256);
        sb.append(System.lineSeparator()).append("Concurrent load, mocked repository:").append(System.lineSeparator());
        sb.append(String.format(Locale.ROOT, "%-34s %7s %9s %10s %9s", "scenario", "threads", "requests",
                "req/s", "failures")).append(System.lineSeparator());
        this.lines.forEach(l -> sb.append(l).append(System.lineSeparator()));
        return sb.toString();
    }

    @AfterEach
    void publish() {
        if (!this.lines.isEmpty()) {
            System.out.println(report());
        }
    }
}
