package com.garganttua.api.core.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.observability.HotPathProbe;
import com.garganttua.core.reflection.IClass;

/**
 * Attribution benchmark for the per-request CRUD READ hot path
 * ({@code Domain.invoke → Workflow → Runtime → READ_ONE/READ_ALL}).
 *
 * <p><b>Not part of the normal build:</b> the class name deliberately ends in {@code Benchmark},
 * not {@code Test}, so Surefire's default includes skip it. Run it explicitly:
 *
 * <pre>{@code
 * # timings only
 * mvn -o test -pl :garganttua-api-core -Dtest=ReadPathBenchmark
 *
 * # (the probe is switched on by the benchmark itself — see enableProbe())
 * }</pre>
 *
 * <p><b>JVM ≠ native.</b> The JIT absorbs allocation churn that GraalVM's closed-world runtime
 * cannot, so absolute numbers here are NOT the production numbers. What transfers is the RELATIVE
 * attribution (which stage dominates) and the before/after ratio of an optimisation.
 */
@DisplayName("READ hot path — attribution benchmark (run explicitly, skipped by default)")
class ReadPathBenchmark extends AbstractCrudScriptTest {

    /**
     * Turns the probe on BEFORE {@link HotPathProbe}'s class initialiser runs (its {@code ENABLED}
     * flag is a {@code static final} read once at class init — that is what makes it fold to dead
     * code in production). Works because this benchmark is run alone in its own Surefire fork
     * ({@code -Dtest=ReadPathBenchmark}), so nothing has touched the probe class yet. A CLI
     * {@code -DargLine} would NOT work: the reactor pom pins its own {@code <argLine>}.
     */
    @BeforeAll
    static void enableProbe() {
        System.setProperty("garganttua.perf.probe", "true");
    }

    private static final int SEEDED_ENTITIES = 50;
    private static final int WARMUP_ITERATIONS = 200;
    private static final int MEASURED_ITERATIONS = 500;

    private IApi api;
    private IDomain<?> productsCtx;
    private CapturingDao dao;
    private String someUuid;

    /** A public read-only domain: no injection, no lifecycle hooks — the leanest possible read path. */
    private void buildApi() throws ApiException {
        dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        builder.multiTenant(false); // a public read-only content domain: no tenant scoping
        builder.domain(IClass.getClass(Product.class))
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(ProductDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security()
                    .readOneAccess(Access.anonymous)
                    .readAllAccess(Access.anonymous)
                .up()
            .up();
        api = buildAndStart(builder);
        productsCtx = api.getDomain("products").orElseThrow();

        for (int i = 0; i < SEEDED_ENTITIES; i++) {
            ProductDto dto = new ProductDto();
            dto.setId("prod-" + i);
            dto.setUuid("uuid-" + i);
            dto.setTenantId("SUPER_TENANT");
            dto.setLabel("Product " + i);
            dto.setPrice(i * 1.5d);
            dao.save(dto);
        }
        someUuid = "uuid-" + (SEEDED_ENTITIES / 2);
    }

    private static double median(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2) / 1_000_000.0d;
    }

    private static double mean(List<Long> nanos) {
        long total = 0L;
        for (long n : nanos) {
            total += n;
        }
        return (total / (double) nanos.size()) / 1_000_000.0d;
    }

    private static double percentile(List<Long> nanos, double p) {
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.min(sorted.size() - 1L, Math.round(p * (sorted.size() - 1)));
        return sorted.get(idx) / 1_000_000.0d;
    }

    private OperationDefinition operationOf(BusinessOperation businessOperation) {
        return productsCtx.getDomainDefinition().operations().stream()
                .filter(op -> op.getBusinessOperation() == businessOperation)
                .findFirst().orElseThrow();
    }

    private OperationRequest readOneRequest(OperationDefinition operation) {
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, operation);
        request.arg(IOperationRequest.ENTITY_UUID, someUuid);
        return request;
    }

    private OperationRequest readAllRequest(OperationDefinition operation) {
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, operation);
        return request;
    }

    private void report(String label, List<Long> samples) {
        System.out.printf("%-14s n=%d  mean=%.3f ms  p50=%.3f ms  p95=%.3f ms  p99=%.3f ms%n",
                label, samples.size(), mean(samples), median(samples), percentile(samples, 0.95),
                percentile(samples, 0.99));
    }

    @Test
    @DisplayName("readOne / readAll — per-request cost and per-stage attribution")
    void benchmarkReadPath() throws Exception {
        buildApi();
        // Requests are built from the domain's OWN operation definitions, so they carry the
        // access=anonymous configured above (IDomain.readOne/readAll shortcuts would instead force
        // readOneWithStandardSecurity → access=authenticated → 401 without a token).
        OperationDefinition readOneOp = operationOf(BusinessOperation.readOne);
        OperationDefinition readAllOp = operationOf(BusinessOperation.readAll);

        // ── warm-up: let the JIT settle and every lazy cache fill ──
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            productsCtx.invoke(readOneRequest(readOneOp));
            productsCtx.invoke(readAllRequest(readAllOp));
        }

        // ── measured run: probe counters start clean, so attribution covers only this phase ──
        HotPathProbe.reset();

        List<Long> readOneSamples = new ArrayList<>(MEASURED_ITERATIONS);
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            IOperationRequest request = readOneRequest(readOneOp);
            long t0 = System.nanoTime();
            IOperationResponse response = productsCtx.invoke(request);
            readOneSamples.add(System.nanoTime() - t0);
            assertTrue(response.getResponseCode().name().startsWith("OK"),
                    "readOne must succeed, got " + response.getResponseCode());
        }

        List<Long> readAllSamples = new ArrayList<>(MEASURED_ITERATIONS);
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            IOperationRequest request = readAllRequest(readAllOp);
            long t0 = System.nanoTime();
            productsCtx.invoke(request);
            readAllSamples.add(System.nanoTime() - t0);
        }

        System.out.println();
        System.out.println("──────── READ hot path benchmark (JVM — relative attribution, not prod numbers) ────────");
        System.out.printf("seeded entities=%d  warmup=%d  measured=%d%n",
                SEEDED_ENTITIES, WARMUP_ITERATIONS, MEASURED_ITERATIONS);
        report("readOne", readOneSamples);
        report("readAll", readAllSamples);
        System.out.println();
        System.out.println("── per-stage attribution (HotPathProbe) ──");
        System.out.println(HotPathProbe.report());
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        // Sanity: the benchmark must have exercised a working read path.
        IOperationResponse one = productsCtx.invoke(readOneRequest(readOneOp));
        Object body = one.getResponse();
        assertEquals(1, body instanceof List<?> list ? list.size() : 1,
                "readOne must return exactly one entity");
        assertTrue(body != null, "readOne must return a body");
    }
}
