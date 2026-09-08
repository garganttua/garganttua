package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Covers {@code GET /<domain>/self}: the caller reads its OWN entity on an authenticator domain.
 *
 * <p>
 * The whole value of the route is that its input cannot name anyone else — the identity comes from
 * the verified caller's qualified {@code ownerId}, which the verification stage published. These
 * tests are therefore mostly about what it REFUSES: a caller with no owner identity, and a caller
 * whose principal belongs to another domain. Both would be ways to read someone else's record, and
 * a route that only worked for the happy path would be worse than no route.
 * </p>
 */
@DisplayName("GET /<domain>/self — the caller's own entity")
class ReadSelfIntegrationTest extends AbstractCrudScriptTest {

    private CapturingDao userDao;

    /** A users domain with an authenticator, hence a readSelf operation. Defaults untouched. */
    private IDomain<?> authenticatorDomain(String domainName) throws ApiException {
        return authenticatorDomain(domainName, false);
    }

    /**
     * @param openReadSelf when true, {@code readSelf} is declared anonymous so VERIFY_AUTHORIZATION
     *        short-circuits and the caller seeded by the test survives to the script. The behaviour
     *        under test is what READ_SELF.gs does with a given identity; that the route is closed by
     *        default is asserted separately, on an untouched domain.
     */
    private IDomain<?> authenticatorDomain(String domainName, boolean openReadSelf) throws ApiException {
        userDao = new CapturingDao();
        IApiBuilder builder = newBuilder();

        var authBuilder = builder.security().authentication(new FixedSupplierBuilder<>(
                new StubAuthentication(), IClass.getClass(StubAuthentication.class)));
        authBuilder.authenticate("authenticate")
                .withParam(0, new com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder())
                .withParam(1, new com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder());
        authBuilder.up();

        builder.domain(IClass.getClass(User.class))
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
                .up()
                .security()
                    .readSelfAccess(openReadSelf ? Access.anonymous : Access.authenticated)
                    .readSelfAuthority(!openReadSelf)
                    .authenticator()
                        .login("id")
                        .scope(AuthenticatorScope.tenant)
                        .alwaysEnabled(true)
                        .authentication(authBuilder)
                    .up()
                .up()
            .up();

        IApi context = buildAndStart(builder);
        seedAlice();
        return context.getDomain(domainName).orElseThrow();
    }

    private void seedAlice() {
        UserDto alice = new UserDto();
        alice.setId("alice@example.com");
        alice.setUuid("uuid-alice");
        alice.setTenantId("SUPER_TENANT");
        alice.setName("Alice");
        alice.setEmail("alice@example.com");
        userDao.getStorage().add(alice);
    }

    /**
     * A request whose verified caller owns {@code ownerId} — what VERIFY_AUTHORIZATION publishes.
     *
     * <p>
     * The operation dispatched is the DOMAIN's own, not a synthesised one: the access level the
     * verification stage enforces is the one the domain declared, so sending a stand-in with
     * different security would test the stand-in. This mirrors what the transport does.
     * </p>
     */
    private static OperationRequest selfRequest(IDomain<?> domain, String ownerId) {
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, domain.getDomainDefinition().operations().stream()
                .filter(op -> op.getBusinessOperation() == BusinessOperation.readSelf)
                .findFirst().orElseThrow(() -> new AssertionError("domain exposes no readSelf")));
        request.arg(IOperationRequest.TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.REQUESTED_TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.SUPER_TENANT, true);
        request.arg(IOperationRequest.SUPER_OWNER, true);
        request.arg(IOperationRequest.CALLER, ICaller.of("SUPER_TENANT", "SUPER_TENANT",
                "alice@example.com", ownerId, null, true, true, List.of()));
        return request;
    }

    @Nested
    @DisplayName("what it returns")
    class HappyPath {

        @Test
        @DisplayName("a caller owning an entity of this domain gets that entity, without naming it")
        void returnsTheCallersOwnEntity() throws Exception {
            IDomain<?> users = authenticatorDomain("users", true);

            WorkflowResult result = executeScript(users, selfRequest(users, OwnerIds.qualify("users", "uuid-alice")));

            assertTrue(result.isSuccess(), () -> "readSelf must succeed; code=" + result.code());
            User self = (User) result.output();
            assertNotNull(self);
            assertEquals("uuid-alice", self.getUuid());
            assertEquals("Alice", self.getName());
        }

        @Test
        @DisplayName("the operation is auto-registered on a domain that declares an authenticator")
        void operationIsRegistered() throws Exception {
            IDomain<?> users = authenticatorDomain("users");

            assertTrue(users.getDomainDefinition().operations().stream()
                            .anyMatch(op -> op.getBusinessOperation() == BusinessOperation.readSelf),
                    "an authenticator domain must expose readSelf");
        }

        @Test
        @DisplayName("it requires an authority by default — a route on one's own record does not open itself")
        void authorityIsRequiredByDefault() throws Exception {
            IDomain<?> users = authenticatorDomain("users");

            OperationDefinition readSelf = users.getDomainDefinition().operations().stream()
                    .filter(op -> op.getBusinessOperation() == BusinessOperation.readSelf)
                    .findFirst().orElseThrow();

            assertTrue(readSelf.authority(),
                    "readSelf must default to requiring an authority: it appears the day a domain "
                            + "declares an authenticator, and a version bump must not open it");
            assertEquals(Access.authenticated, readSelf.access());
        }
    }

    @Nested
    @DisplayName("what it refuses — the point of the route")
    class Refusals {

        @Test
        @DisplayName("a caller carrying no owner identity is refused, not served someone")
        void noOwnerIdentityIsRefused() throws Exception {
            IDomain<?> users = authenticatorDomain("users", true);

            WorkflowResult result = executeScript(users, selfRequest(users, null));

            assertFalse(result.isSuccess());
            assertEquals(403, result.code(), "no identity means no self, and 403 says so");
        }

        @Test
        @DisplayName("a caller whose principal belongs to ANOTHER domain is refused")
        void foreignPrincipalIsRefused() throws Exception {
            IDomain<?> users = authenticatorDomain("users", true);

            // A token minted by another domain's authenticator, naming a uuid that exists here.
            WorkflowResult result = executeScript(users,
                    selfRequest(users, OwnerIds.qualify("admins", "uuid-alice")));

            assertFalse(result.isSuccess(),
                    "a token issued for 'admins' must not resolve an entity on 'users'");
            assertEquals(403, result.code());
        }

        @Test
        @DisplayName("an unqualified owner identity is refused rather than guessed")
        void unqualifiedOwnerIdIsRefused() throws Exception {
            IDomain<?> users = authenticatorDomain("users", true);

            WorkflowResult result = executeScript(users, selfRequest(users, "uuid-alice"));

            assertFalse(result.isSuccess(),
                    "an owner id with no domain cannot be attributed to one — refuse, do not assume");
            assertEquals(403, result.code());
        }

        @Test
        @DisplayName("a caller whose own row is gone gets 404, not someone else's row")
        void deletedOwnRowIsNotFound() throws Exception {
            IDomain<?> users = authenticatorDomain("users", true);
            userDao.getStorage().clear();

            WorkflowResult result = executeScript(users,
                    selfRequest(users, OwnerIds.qualify("users", "uuid-alice")));

            assertFalse(result.isSuccess());
            assertEquals(404, result.code());
        }
    }
}
