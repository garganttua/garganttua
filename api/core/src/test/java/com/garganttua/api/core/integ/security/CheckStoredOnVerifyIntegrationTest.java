package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.expression.SecurityAuthenticationExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * End-to-end tests for the opt-in {@code .checkStoredOnVerify(true)} option.
 *
 * <p>Verification is stateless by default: the intrinsic revoked/expiration checks run on the
 * decoded token's frozen claims, so a {@code revoked=true} (or a deleted row) set on the STORED
 * record does not reject an already-issued bearer. With {@code checkStoredOnVerify(true)} on a
 * {@code storable} domain, the verify path fetches the server-authoritative stored record, fails
 * closed when it is absent, and re-validates against its current state.
 *
 * <p>The token domain here is deliberately non-signable and has no token-level authenticator, so
 * the signature step is a no-op and the tests isolate the stored-record behaviour.
 */
@DisplayName("checkStoredOnVerify — stateful revocation against the stored record")
class CheckStoredOnVerifyIntegrationTest extends AbstractCrudScriptTest {

    /** Full-fidelity session token: every field the stored record must round-trip. */
    public static class SessionTokenEntity {
        private String id;
        private String uuid;
        private String tenantId;
        private String ownerId;
        private String tokenType;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private String signedBy;
        private Boolean superTenant = false;

        public SessionTokenEntity() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public String getSignedBy() { return signedBy; }
        public void setSignedBy(String signedBy) { this.signedBy = signedBy; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    /** DTO mirrors every entity field by name so the stored record round-trips fully. */
    public static class SessionTokenDto {
        private String id;
        private String uuid;
        private String tenantId;
        private String ownerId;
        private String tokenType;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private String signedBy;
        private Boolean superTenant;

        public SessionTokenDto() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public String getSignedBy() { return signedBy; }
        public void setSignedBy(String signedBy) { this.signedBy = signedBy; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    private CapturingDao userDao;
    private CapturingDao tokenDao;
    private IApi api;
    private IDomain<?> usersCtx;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void build(boolean checkStoredOnVerify) throws ApiException {
        userDao = new CapturingDao();
        tokenDao = new CapturingDao();
        IApiBuilder builder = newBuilder();

        StubAuthentication stubAuth = new StubAuthentication();
        var authBuilder = builder.security()
                .authentication(new FixedSupplierBuilder<>(stubAuth, IClass.getClass(StubAuthentication.class)));
        authBuilder.authenticate("authenticate")
                .withParam(0, new com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder())
                .withParam(1, new com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder());
        authBuilder.up();

        // Token domain: storable (via revokable), non-signable, no token authenticator.
        var tokenBuilder = builder.domain(IClass.getClass(SessionTokenEntity.class))
                .tenant(true)
                .superTenant("superTenant")
                .owned("ownerId")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(SessionTokenDto.class))
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
                    .lifeTime(60, java.util.concurrent.TimeUnit.MINUTES);
        userBuilder.up();

        api = buildAndStart(builder);
        usersCtx = api.getDomain("users").orElseThrow();

        UserDto john = new UserDto();
        john.setId("john@example.com");
        john.setUuid("user-uuid-1");
        john.setTenantId("SUPER_TENANT");
        userDao.save(john);
    }

    private SessionTokenEntity mintToken() throws ApiException {
        AuthenticationRequest authReq = new AuthenticationRequest(
                "john@example.com", "valid-password".getBytes(StandardCharsets.UTF_8));
        OperationDefinition authOp = OperationDefinition.authenticate("users", IClass.getClass(User.class));
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, authOp);
        request.arg(IOperationRequest.TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.REQUESTED_TENANT_ID, "SUPER_TENANT");
        request.arg("entity", authReq);

        WorkflowResult mint = executeScript(usersCtx, request);
        assertEquals(0, mint.code(), () -> "mint failed; vars=" + mint.variables());
        SessionTokenEntity token = (SessionTokenEntity) mint.output();
        assertNotNull(token, "authenticate must mint a token");
        assertEquals(1, tokenDao.getStorage().size(), "a storable token must be persisted");
        return token;
    }

    private SessionTokenDto storedRow(String uuid) {
        for (Object o : tokenDao.getStorage()) {
            SessionTokenDto dto = (SessionTokenDto) o;
            if (uuid.equals(dto.getUuid())) {
                return dto;
            }
        }
        throw new AssertionError("no stored token row with uuid " + uuid);
    }

    private IAuthentication verify(SessionTokenEntity token) {
        return SecurityAuthenticationExpressions.verifyAuthorization(
                api, token, new OperationRequest(new java.util.HashMap<>()));
    }

    @Nested
    @DisplayName("checkStoredOnVerify(true) — fail closed on the stored record")
    class Enabled {

        @Test
        @DisplayName("a valid token whose stored row is intact verifies")
        void validTokenVerifies() throws Exception {
            build(true);
            SessionTokenEntity token = mintToken();
            IAuthentication auth = verify(token);
            assertTrue(auth.authenticated(), "a token with an intact stored row must verify");
            assertNotNull(auth.principal(), "the owner must be resolved as principal");
        }

        @Test
        @DisplayName("a valid token whose STORED row is revoked=true is rejected (401), even though its signed payload says false")
        void revokedInStoreRejected() throws Exception {
            build(true);
            SessionTokenEntity token = mintToken();
            assertFalse(Boolean.TRUE.equals(token.getRevoked()), "the decoded token's own claim is not revoked");

            storedRow(token.getUuid()).setRevoked(true); // revoke the persisted session

            ApiException ex = assertThrows(ApiException.class, () -> verify(token));
            assertTrue(ex.getMessage().contains("revoked"),
                    "revocation must be enforced against the stored record — got: " + ex.getMessage());
        }

        @Test
        @DisplayName("a valid token whose STORED row was deleted is rejected (401, fail-closed)")
        void deletedInStoreRejected() throws Exception {
            build(true);
            SessionTokenEntity token = mintToken();

            tokenDao.getStorage().clear(); // delete the persisted session (e.g. logout)

            ApiException ex = assertThrows(ApiException.class, () -> verify(token));
            assertTrue(ex.getMessage().contains("not found or revoked"),
                    "a deleted stored row must fail closed — got: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("checkStoredOnVerify(false, default) — stateless, unchanged behaviour")
    class Disabled {

        @Test
        @DisplayName("a token whose STORED row is revoked is STILL accepted (stateless — the frozen claim wins)")
        void revokedInStoreStillAccepted() throws Exception {
            build(false);
            SessionTokenEntity token = mintToken();

            storedRow(token.getUuid()).setRevoked(true);

            IAuthentication auth = verify(token);
            assertTrue(auth.authenticated(),
                    "without checkStoredOnVerify, a stored revocation does not reject the bearer (unchanged behaviour)");
        }

        @Test
        @DisplayName("a token whose STORED row was deleted is STILL accepted (no fail-closed by default)")
        void deletedInStoreStillAccepted() throws Exception {
            build(false);
            SessionTokenEntity token = mintToken();

            tokenDao.getStorage().clear();

            IAuthentication auth = verify(token);
            assertTrue(auth.authenticated(),
                    "without checkStoredOnVerify, a deleted stored row does not reject the bearer");
        }
    }
}
