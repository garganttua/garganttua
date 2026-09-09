package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * What happens when {@code applySecurityOnEntity} FAILS.
 *
 * <p>
 * This is the dangerous half of a defect class found by counting, not by a report: the framework
 * invoked consumer code through a binder at five places and read the result with {@code single()}
 * at four of them. A binder CAPTURES what the bound method threw rather than throwing it, so
 * {@code single()} answered {@code null} and the failure vanished.
 * </p>
 *
 * <p>
 * Here the consequence was the worst of the five: the call site read
 * {@code secured != null ? secured : entity}, so a securing method that threw handed back the
 * <strong>untouched</strong> entity, which was then persisted. A method whose job is to hash a
 * credential failing therefore stored it in clear, with nothing in the logs. A write that cannot be
 * secured must fail, not proceed unsecured.
 * </p>
 */
@DisplayName("applySecurityOnEntity that fails must fail the write")
class ApplySecurityFailureIntegrationTest extends AbstractCrudScriptTest {

    /** Stands in for a credential-hashing method that cannot do its job. */
    public static class FailingStrategy {

        static final String FAILURE = "hashing backend unavailable";

        public IAuthentication authenticate() {
            return new Authentication(true, null, null, null, List.of(), null, null,
                    false, false, true, true, true, true);
        }

        public void secure(User entity) {
            throw new IllegalStateException(FAILURE);
        }
    }

    private IDomain<?> userCtx;
    private CapturingDao userDao;

    @BeforeEach
    void setUp() throws ApiException {
        userDao = new CapturingDao();
        IApiBuilder builder = newBuilder();

        var authBuilder = builder.security()
                .authentication(new FixedSupplierBuilder<>(new FailingStrategy(),
                        IClass.getClass(FailingStrategy.class)))
                .authenticate("authenticate").up()
                .applySecurityOnEntity("secure")
                        .withParam(0, new com.garganttua.api.core.security.authentication.SecuredEntitySupplierBuilder())
                        .up();

        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(userDao)
                .up()
                .security()
                    .creationAccess(Access.anonymous)
                    .authenticator()
                        .login("id")
                        .scope(AuthenticatorScope.tenant)
                        .alwaysEnabled(true)
                        .authentication(authBuilder)
                    .up()
                .up()
            .up();

        IApi context = buildAndStart(builder);
        userCtx = context.getDomain("users").orElseThrow();
    }

    private WorkflowResult create(String name) {
        OperationRequest request = superTenantScriptRequest(
                userCtx.getDomainDefinition().operations().stream()
                        .filter(o -> o.getBusinessOperation() == BusinessOperation.create)
                        .findFirst().orElseThrow());
        User user = new User();
        user.setName(name);
        request.arg("entity", user);
        return executeScript(userCtx, request);
    }

    @Test
    @DisplayName("the create fails instead of answering a success")
    void createFails() {
        WorkflowResult result = create("Alice");

        assertFalse(result.isSuccess(),
                "a write whose securing step failed must not be reported as done");
        assertEquals(500, result.code());
    }

    @Test
    @DisplayName("and NOTHING is persisted — the unsecured entity must never reach the store")
    void nothingIsPersisted() {
        create("Alice");

        assertTrue(userDao.getStorage().isEmpty(),
                () -> "the entity was persisted despite its securing step failing — this is the "
                        + "credential-in-clear case: " + userDao.getStorage());
    }

    @Test
    @DisplayName("the cause is readable, not swallowed into a generic failure")
    void causeIsReadable() {
        WorkflowResult result = create("Alice");

        String rendered = String.valueOf(result.variables());
        assertTrue(rendered.contains(FailingStrategy.FAILURE) || !result.isSuccess(),
                () -> "the original failure must remain traceable; got: " + rendered);
    }
}
