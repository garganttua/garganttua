package com.garganttua.api.core.perfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.expression.SecurityAuthenticationExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.observability.HotPathProbe;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * What authentication and authorization cost, measured on the framework alone.
 *
 * <p>
 * This is the half of the request a consumer cannot avoid paying and cannot instrument from the
 * outside. A report from a consumer running on pure AOT put a ~40 ms floor on every authenticated
 * request and 92 ms on {@code POST /users/authenticate} (see {@code demandes-externes/}); it also
 * discarded two hypotheses by measurement — that the stored-token re-read was to blame, and that the
 * number of authorities was. Both are re-examined here on a harness we control.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-api-core -Dtest='*PerfTest' -Dgarganttua.perf=true
 * }</pre>
 *
 * <h2>What the numbers include, and what they cannot</h2>
 * <ul>
 *   <li><b>Included</b>: the authenticate workflow, principal lookup, token minting and persistence,
 *       and the whole of {@code verifyAuthorization} — decode, expiration, revocation, the stored-row
 *       re-read when enabled, owner resolution, caller reconciliation.</li>
 *   <li><b>Excluded, and it matters</b>: the repository is in memory. {@code checkStoredOnVerify}
 *       therefore costs a scan of a one-element list here, where against a real MongoDB it costs a
 *       round trip. The figure below is the FRAMEWORK cost of that option, never its deployed cost.</li>
 *   <li><b>Excluded</b>: password hashing. A real credential check is a bcrypt by design — tens of
 *       milliseconds, deliberately — and it would drown every other figure. It is a known constant,
 *       measured once, not a pipeline cost.</li>
 *   <li><b>Excluded</b>: signature. The fixture's authorization is storable but not signable, so no
 *       asymmetric cryptography runs.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true")
@DisplayName("Authentication and authorization cost (in-memory repository)")
class SecurityPipelinePerfTest extends AbstractCrudScriptTest {

    private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 500);
    private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 2_000);

    private final List<PerfHarness.Stats> collected = new ArrayList<>();

    /** One built API: the users domain that authenticates, and the token domain it mints into. */
    private record Fixture(IApi api, IDomain<?> users, CapturingDao tokenDao) {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Fixture build(boolean checkStoredOnVerify, int authorityCount) throws ApiException {
        CapturingDao userDao = new CapturingDao();
        CapturingDao tokenDao = new CapturingDao();
        IApiBuilder builder = newBuilder();

        var authBuilder = builder.security().authentication(new FixedSupplierBuilder<>(
                new PerfAuthentication(authorityCount), IClass.getClass(PerfAuthentication.class)));
        authBuilder.authenticate("authenticate")
                .withParam(0, new com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder())
                .withParam(1, new com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder());
        authBuilder.up();

        var tokenBuilder = builder.domain(IClass.getClass(PerfSessionToken.Entity.class))
                .tenant(true)
                .superTenant("superTenant")
                .owned("ownerId")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(PerfSessionToken.Dto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(tokenDao)
                .up();
        tokenBuilder.security()
                .authorization()
                    .type("tokenType")
                    .authorities("authorities")
                    .expirable("expiresAt")
                    .revokable("revoked")
                    .checkStoredOnVerify(checkStoredOnVerify)
                .up()
            .up();

        var userBuilder = builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .owner("uuid")
                .superOwner("superOwner")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(userDao)
                .up();
        userBuilder.security()
                .authenticator()
                    .login("id")
                    .scope(AuthenticatorScope.tenant)
                    .alwaysEnabled(true)
                    .authentication(authBuilder)
                .authorization((IDomainBuilder) tokenBuilder)
                    .lifeTime(60, TimeUnit.MINUTES);
        userBuilder.up();

        IApi api = buildAndStart(builder);

        UserDto john = new UserDto();
        john.setId("john@example.com");
        john.setUuid("user-uuid-1");
        john.setTenantId("SUPER_TENANT");
        userDao.save(john);

        return new Fixture(api, api.getDomain("users").orElseThrow(), tokenDao);
    }

    /** Runs the whole authenticate workflow: lookup, credential check, mint, persist. */
    private WorkflowResult authenticate(Fixture fixture) {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.authenticate("users", IClass.getClass(User.class)));
        request.arg("entity", new AuthenticationRequest(
                "john@example.com", "valid-password".getBytes(StandardCharsets.UTF_8)));
        return executeScript(fixture.users(), request);
    }

    private PerfSessionToken.Entity mintToken(Fixture fixture) {
        WorkflowResult mint = authenticate(fixture);
        assertEquals(0, mint.code(), () -> "mint failed; vars=" + mint.variables());
        PerfSessionToken.Entity token = (PerfSessionToken.Entity) mint.output();
        assertNotNull(token, "authenticate must mint a token");
        return token;
    }

    /** The per-request half: everything VERIFY_AUTHORIZATION does with a presented token. */
    private static IAuthentication verify(Fixture fixture, PerfSessionToken.Entity token) {
        return SecurityAuthenticationExpressions.verifyAuthorization(
                fixture.api(), token, new OperationRequest(new HashMap<>()));
    }

    private PerfHarness.Stats record(PerfHarness.Stats stats) {
        this.collected.add(stats);
        return stats;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("minting a token: the whole authenticate workflow")
    void authenticateCost() throws Exception {
        Fixture fixture = build(false, 1);

        PerfHarness.Stats stats = record(PerfHarness.measure(
                "authenticate — lookup, mint, persist", WARMUP, RUNS, i -> {
                    // The token store would otherwise grow by one row per run and turn its linear
                    // scans into the thing being measured.
                    if (fixture.tokenDao().getStorage().size() > 50) {
                        fixture.tokenDao().getStorage().clear();
                    }
                    authenticate(fixture);
                }));

        assertTrue(stats.driftRatio() < 3.0,
                () -> String.format(Locale.ROOT, "minting drifts %.2fx over the run.%s",
                        stats.driftRatio(), report()));
    }

    @Test
    @DisplayName("verifying a presented token, the cost paid on every authenticated request")
    void verifyCost() throws Exception {
        Fixture fixture = build(false, 1);
        PerfSessionToken.Entity token = mintToken(fixture);

        record(PerfHarness.measure("verifyAuthorization — stateless", WARMUP, RUNS,
                i -> verify(fixture, token)));
    }

    @Test
    @DisplayName("what checkStoredOnVerify costs — a consumer measured no difference")
    void storedTokenRereadCost() throws Exception {
        Fixture stateless = build(false, 1);
        PerfSessionToken.Entity statelessToken = mintToken(stateless);

        Fixture stateful = build(true, 1);
        PerfSessionToken.Entity statefulToken = mintToken(stateful);

        PerfHarness.Stats[] both = PerfHarness.compare(
                "verify — checkStoredOnVerify(false)", i -> verify(stateless, statelessToken),
                "verify — checkStoredOnVerify(true)", i -> verify(stateful, statefulToken),
                WARMUP, RUNS);
        PerfHarness.Stats off = record(both[0]);
        PerfHarness.Stats on = record(both[1]);

        System.out.printf(Locale.ROOT,
                "%ncheckStoredOnVerify costs %+.0f us per verification here (floor %.0f -> %.0f us).%n"
                        + "READ THIS BEFORE QUOTING IT: the repository is in memory, so the extra%n"
                        + "read is a scan of a one-element list. Against a real database it is a%n"
                        + "round trip, and this figure says nothing about that.%n",
                on.minUs() - off.minUs(), off.minUs(), on.minUs());
    }

    @Test
    @DisplayName("what the number of authorities costs — a consumer measured no difference between 3 and 219")
    void authorityCountCost() throws Exception {
        Fixture few = build(false, 3);
        PerfSessionToken.Entity fewToken = mintToken(few);

        Fixture many = build(false, 219);
        PerfSessionToken.Entity manyToken = mintToken(many);

        PerfHarness.Stats[] both = PerfHarness.compare(
                "verify — 3 authorities", i -> verify(few, fewToken),
                "verify — 219 authorities", i -> verify(many, manyToken),
                WARMUP, RUNS);
        PerfHarness.Stats small = record(both[0]);
        PerfHarness.Stats large = record(both[1]);

        System.out.printf(Locale.ROOT,
                "%n219 authorities cost %+.0f us per verification versus 3 (floor %.0f -> %.0f us).%n",
                large.minUs() - small.minUs(), small.minUs(), large.minUs());

        // The authorities travel as a list on the token; verification reads it, it does not walk it
        // per element. A verification that scaled with the number of authorities would mean the list
        // is being re-derived, not read.
        assertTrue(large.minUs() < small.minUs() * 5,
                () -> "verification scales with the number of authorities (" + small.minUs()
                        + " -> " + large.minUs() + " us): the list is being re-derived, not read."
                        + report());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String report() {
        String table = PerfHarness.report("Security pipeline, in-memory repository:", this.collected);
        return HotPathProbe.isEnabled() ? table + System.lineSeparator() + HotPathProbe.report() : table;
    }

    @AfterEach
    void publish() {
        if (!this.collected.isEmpty()) {
            System.out.println(report());
        }
    }
}
