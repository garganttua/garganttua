package com.garganttua.api.core.perfs;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.core.integ.TestAuthorization;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.observability.HotPathProbe;
import com.garganttua.core.reflection.IClass;

/**
 * What one CRUD operation costs in the pipeline itself, with the database taken out of the picture.
 *
 * <p>
 * The repository here is the in-memory {@code CapturingDao}, so every microsecond measured belongs
 * to the framework: request building, verification, the workflow, the stage scripts, entity
 * resolution, injection, mapping and serialisation. That is the cost a consumer pays on EVERY
 * request whatever its business does, and it is the figure a consumer reported as a ~40 ms floor
 * per authenticated request (see {@code demandes-externes/}). A number measured against a real
 * MongoDB would mix that floor with the driver and the network and answer a different question.
 * </p>
 *
 * <h2>Running them</h2>
 * <pre>{@code
 * mvn -o test -pl :garganttua-api-core -Dtest='*PerfTest' -Dgarganttua.perf=true
 *
 * # with the per-stage attribution (which stage of the pipeline the time goes to):
 * mvn -o test -pl :garganttua-api-core -Dtest='*PerfTest' -Dgarganttua.perf=true \
 *     -DargLine="-Dgarganttua.perf.probe=true"
 * }</pre>
 *
 * <p>
 * They are gated on {@code -Dgarganttua.perf=true} rather than {@code @Disabled}: disabled tests
 * cannot be run at all, not even by asking for them by name, which is why the older
 * {@code core/runtime} performance test never runs.
 * </p>
 *
 * <h2>What is asserted, and what is not</h2>
 * <p>
 * No absolute threshold. A wall-clock budget asserted in a test passes on the machine that wrote it
 * and fails on the next one, and a suite that fails for the machine it runs on stops being read.
 * What IS asserted are two relations that hold on any machine:
 * </p>
 * <ul>
 *   <li><b>scaling</b> — reading a hundred entities must not cost a hundred times reading none.
 *       This catches per-entity work that escaped into a fixed-cost stage;</li>
 *   <li><b>drift</b> — the last runs must not be dramatically slower than the first. This catches
 *       a cost that grows with the number of requests served: a cache that never stops filling,
 *       registrations that accumulate, a resolution redone and re-recorded each time.</li>
 * </ul>
 * <p>
 * Both are measured on the <b>floor</b> (the fastest run) rather than the median, and with the two
 * scenarios <b>interleaved</b>. A latency sample has a hard floor and an unbounded tail, so noise
 * can only push a measurement up; and running one scenario to completion before the other compares
 * them across seconds of drifting machine state. Skipping either precaution produces numbers that
 * look plausible and are not: a first version of this suite reported reading a hundred entities as
 * faster than reading none.
 * </p>
 *
 * <p>
 * The bounds are deliberately generous. They are there to catch a tenfold regression, not a
 * twenty-percent one — measuring a twenty-percent change reliably needs a controlled machine, which
 * a unit-test suite is not.
 * </p>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true")
@DisplayName("CRUD pipeline cost (in-memory repository)")
class CrudPipelinePerfTest extends AbstractCrudIntegrationTest {

    private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 2_000);
    private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 5_000);

    private final List<PerfHarness.Stats> collected = new ArrayList<>();

    /** A users domain over a fresh in-memory repository, security off — the pipeline, nothing else. */
    private static Fixture usersDomain() throws Exception {
        CapturingDao dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .update("name", true)
                    .update("email", true)
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security().disable(true).up()
            .up();
        IApi context = buildAndStart(builder);
        return new Fixture(context.getDomain("users").orElseThrow(), dao);
    }

    /**
     * The same domain with the security stage ENGAGED: operations default to
     * {@code Access.authenticated}, so {@code VERIFY_AUTHORIZATION} runs instead of
     * short-circuiting. Requests must then carry an authorization.
     */
    private static Fixture securedUsersDomain() throws Exception {
        CapturingDao dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security().up()
            .up();
        IApi context = buildAndStart(builder);
        return new Fixture(context.getDomain("users").orElseThrow(), dao);
    }

    private record Fixture(IDomain<?> domain, CapturingDao dao) {
    }

    private static void seed(CapturingDao dao, int count) {
        for (int i = 0; i < count; i++) {
            UserDto dto = new UserDto();
            dto.setId(String.valueOf(i));
            dto.setUuid("uuid-" + i);
            dto.setTenantId("SUPER_TENANT");
            dto.setName("user-" + i);
            dto.setEmail("user-" + i + "@example.com");
            dao.getStorage().add(dto);
        }
    }

    private static IOperationResponse readAll(IDomain<?> domain) {
        return domain.invoke(superTenantRequest(
                OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class))));
    }

    private PerfHarness.Stats record(PerfHarness.Stats stats) {
        this.collected.add(stats);
        return stats;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the fixed cost of a read, and how it scales with the number of entities returned")
    void readCost() throws Exception {
        Fixture empty = usersDomain();
        Fixture hundred = usersDomain();
        seed(hundred.dao(), 100);

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — empty collection", i -> readAll(empty.domain()),
                "readAll — 100 entities", i -> readAll(hundred.domain()),
                WARMUP, RUNS);
        PerfHarness.Stats onNothing = record(both[0]);
        PerfHarness.Stats onHundred = record(both[1]);

        // Compared on the FLOOR of each, not the median: the floor is the cost when nothing else
        // interfered, and it is the only figure two scenarios can be held against on a machine
        // running other things.
        //
        // A read that returns nothing still pays the whole pipeline; that is the fixed cost.
        // Returning a hundred entities adds mapping and injection per entity, so it costs more —
        // but nowhere near a hundred times more, or per-entity work has leaked into that fixed cost.
        assertTrue(onHundred.minUs() < onNothing.minUs() * 40,
                () -> "reading 100 entities costs " + ratio(onHundred, onNothing)
                        + "x reading none — per-entity work has escaped into the fixed cost."
                        + report());
    }

    @Test
    @DisplayName("reading one entity by uuid")
    void readOneCost() throws Exception {
        Fixture fixture = usersDomain();
        seed(fixture.dao(), 100);

        record(PerfHarness.measure("readOne — by uuid", WARMUP, RUNS, i -> {
            OperationRequest request = superTenantRequest(
                    OperationDefinition.readOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg("type", "uuid");
            request.arg("identifier", "uuid-42");
            assertNotNull(fixture.domain().invoke(request));
        }));
    }

    @Test
    @DisplayName("writing: create, then update by uuid")
    void writeCost() throws Exception {
        Fixture creates = usersDomain();
        record(PerfHarness.measure("createOne", WARMUP, RUNS, i -> {
            // The in-memory repository is a list: left to grow over thousands of creates it would
            // dominate the measurement with its own linear scans, and report a drift that belongs
            // to the harness rather than to the pipeline.
            if (creates.dao().getStorage().size() > 200) {
                creates.dao().getStorage().clear();
            }
            User user = new User();
            user.setName("user-" + i);
            OperationRequest request = superTenantRequest(
                    OperationDefinition.createOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.BODY, user);
            creates.domain().invoke(request);
        }));

        Fixture updates = usersDomain();
        seed(updates.dao(), 1);
        record(PerfHarness.measure("updateOne — partial (PATCH)", WARMUP, RUNS, i -> {
            User body = new User();
            body.setName("renamed-" + i);
            OperationRequest request = superTenantRequest(
                    OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.ENTITY_UUID, "uuid-0");
            request.arg(IOperationRequest.BODY, body);
            request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);
            updates.domain().invoke(request);
        }));
    }

    @Test
    @DisplayName("what engaging the security stage adds to a read")
    void securityStageCost() throws Exception {
        Fixture open = usersDomain();                 // .security().disable(true)
        seed(open.dao(), 10);
        Fixture secured = securedUsersDomain();       // operations default to Access.authenticated
        seed(secured.dao(), 10);

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — security disabled", i -> readAll(open.domain()),
                "readAll — security on (Mode B token)", i -> {
                    OperationRequest request = superTenantRequest(
                            OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class)));
                    request.arg("authorization", new TestAuthorization());
                    secured.domain().invoke(request);
                },
                WARMUP, RUNS);
        PerfHarness.Stats withoutSecurity = record(both[0]);
        PerfHarness.Stats withSecurity = record(both[1]);

        assertNotNull(withSecurity);
        System.out.printf(Locale.ROOT,
                "%nSecurity stage adds %.0f us to a 10-entity read (floor %.0f -> %.0f us).%n"
                        + "NOTE: a pre-decoded Mode B token skips the stored-token re-read, the%n"
                        + "signature check and the authenticator lookup — this is the FLOOR of%n"
                        + "engaging the stage, not the cost of verifying a real token.%n",
                withSecurity.minUs() - withoutSecurity.minUs(),
                withoutSecurity.minUs(), withSecurity.minUs());
    }

    @Test
    @DisplayName("serving requests does not make the next one more expensive")
    void costDoesNotDriftWithUse() throws Exception {
        Fixture fixture = usersDomain();
        seed(fixture.dao(), 10);

        PerfHarness.Stats stats = record(PerfHarness.measure(
                "readAll — drift over " + RUNS + " runs", WARMUP, RUNS, i -> readAll(fixture.domain())));

        // The guard that matters for a long-lived server: the cost of a request must not depend on
        // how many came before it. A resolution redone and re-recorded per request, a registry that
        // only grows, a listener list that accumulates — all show up here and nowhere else.
        assertTrue(stats.driftRatio() < 3.0,
                () -> String.format(Locale.ROOT,
                        "the last runs cost %.2fx the first — serving requests is making the next "
                                + "one more expensive.%s", stats.driftRatio(), report()));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static String ratio(PerfHarness.Stats a, PerfHarness.Stats b) {
        return String.format(Locale.ROOT, "%.1f", a.minUs() / Math.max(b.minUs(), 0.001d));
    }

    /** The table, plus the per-stage attribution when the probe was enabled on the JVM. */
    private String report() {
        String table = PerfHarness.report("CRUD pipeline, in-memory repository:", this.collected);
        if (!HotPathProbe.isEnabled()) {
            return table + System.lineSeparator()
                    + "(add -DargLine=\"-Dgarganttua.perf.probe=true\" for the per-stage attribution)";
        }
        return table + System.lineSeparator() + HotPathProbe.report();
    }

    @org.junit.jupiter.api.AfterEach
    void publish() {
        if (!this.collected.isEmpty()) {
            System.out.println(report());
        }
    }
}
