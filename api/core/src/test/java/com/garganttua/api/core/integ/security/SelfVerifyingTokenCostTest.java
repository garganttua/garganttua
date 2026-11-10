package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.expression.SecurityAuthenticationExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder;
import com.garganttua.api.core.security.authentication.DecodedAuthorizationSupplierBuilder;
import com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * What a request costs to verify a SELF-VERIFYING bearer: a token domain that declares its own
 * authenticator (the token verifies itself through a strategy), is storable and checks its stored
 * record on every request — the shape a consumer uses for revocable sessions.
 *
 * <p>
 * The verification used to invoke the whole {@code authenticate} pipeline of the token domain at every
 * request — whose principal lookup is itself a nested {@code readAll} pipeline — and then read the same
 * stored record twice more. The checks are the same and run directly: the stored record is read ONCE,
 * serves as the principal, as the revocation check and as the server-authoritative copy.
 * </p>
 */
@DisplayName("Self-verifying token — what one verification costs, and what it still enforces")
class SelfVerifyingTokenCostTest extends AbstractCrudScriptTest {

    public static class SessionToken {
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

        public SessionToken() {}

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

    /** The token's own strategy: accepts unless told to refuse; records what it was given. */
    public static class TokenStrategy {
        int calls;
        Object principal;
        Object credentials;
        boolean refuse;

        public IAuthentication authenticate(Object principal, Object credentials, IAuthenticatorDefinition definition) {
            this.calls++;
            this.principal = principal;
            this.credentials = credentials;
            boolean ok = !this.refuse && credentials instanceof SessionToken;
            return new Authentication(ok, ok ? principal : null, credentials, null, null,
                    null, null, false, false, true, true, true, true);
        }
    }

    /** Counts what reaches the database — the figure the verification is judged on. */
    public static class CountingDao extends CapturingDao {
        int finds;

        @Override
        public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
                throws ApiException {
            this.finds++;
            return super.find(pageable, filter, sort);
        }
    }

    private CountingDao userDao;
    private CountingDao tokenDao;
    private TokenStrategy strategy;
    private IApi api;
    private IDomain<?> usersCtx;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void build() throws ApiException {
        userDao = new CountingDao();
        tokenDao = new CountingDao();
        strategy = new TokenStrategy();
        IApiBuilder builder = newBuilder();

        var passwordAuth = builder.security()
                .authentication(new FixedSupplierBuilder<>(new StubAuthentication(),
                        IClass.getClass(StubAuthentication.class)));
        passwordAuth.authenticate("authenticate")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new AuthenticatorDefinitionSupplierBuilder());
        passwordAuth.up();

        var tokenAuth = builder.security()
                .authentication(new FixedSupplierBuilder<>(strategy, IClass.getClass(TokenStrategy.class)));
        tokenAuth.authenticate("authenticate")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new DecodedAuthorizationSupplierBuilder())
                .withParam(2, new AuthenticatorDefinitionSupplierBuilder());
        tokenAuth.up();

        var tokenBuilder = builder.domain(IClass.getClass(SessionToken.class))
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
                    .storable(true)
                    .checkStoredOnVerify(true)
                .up()
            .up();
        tokenBuilder.security()
                .authenticator()
                    .login("uuid")
                    .scope(AuthenticatorScope.system)
                    .alwaysEnabled(true)
                    .authentication(tokenAuth);

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
                    .authentication(passwordAuth)
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

    private SessionToken mintToken() {
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, OperationDefinition.authenticate("users", IClass.getClass(User.class)));
        request.arg(IOperationRequest.TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.REQUESTED_TENANT_ID, "SUPER_TENANT");
        request.arg("entity", new AuthenticationRequest(
                "john@example.com", "valid-password".getBytes(StandardCharsets.UTF_8)));
        WorkflowResult mint = executeScript(usersCtx, request);
        assertEquals(0, mint.code(), () -> "mint failed; vars=" + mint.variables());
        SessionToken token = (SessionToken) mint.output();
        assertNotNull(token, "authenticate must mint a token");
        return token;
    }

    /** A protected read carrying the (already decoded) bearer: VERIFY_AUTHORIZATION runs in the pipeline. */
    private WorkflowResult readUsersWith(SessionToken token) {
        OperationRequest request = superTenantScriptRequest(OperationDefinition.readAll("users",
                IClass.getClass(User.class), false, null, Access.authenticated));
        request.arg(IOperationRequest.AUTHORIZATION, token);
        userDao.finds = 0;
        tokenDao.finds = 0;
        strategy.calls = 0;
        return executeScript(usersCtx, request);
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

    @Nested
    @DisplayName("cost")
    class Cost {

        @Test
        @DisplayName("the token's stored record is read ONCE per request, and its strategy runs once")
        void oneStoredRead() throws Exception {
            build();
            SessionToken token = mintToken();

            WorkflowResult result = readUsersWith(token);

            assertEquals(0, result.code(), () -> "the request must pass; vars=" + result.variables());
            assertEquals(1, tokenDao.finds,
                    "principal, revocation check and server-authoritative copy are the same stored row");
            assertEquals(1, strategy.calls, "the token's strategy decides, once");
            assertEquals(2, userDao.finds, "the owner principal, then the read the request asked for");
        }
    }

    @Nested
    @DisplayName("what the verification still enforces")
    class Enforced {

        @Test
        @DisplayName("the strategy receives the STORED record as principal and the decoded token as credentials")
        void strategyInputs() throws Exception {
            build();
            SessionToken token = mintToken();

            assertEquals(0, readUsersWith(token).code());

            assertTrue(strategy.principal instanceof SessionToken, "the principal is the token domain's entity");
            assertEquals(token.getUuid(), ((SessionToken) strategy.principal).getUuid());
            assertSame(token, strategy.credentials, "the credentials are the presented (decoded) token");
        }

        @Test
        @DisplayName("a strategy that refuses rejects the request (401)")
        void strategyRefusal() throws Exception {
            build();
            SessionToken token = mintToken();
            strategy.refuse = true;

            assertEquals(401, readUsersWith(token).code());
            assertEquals(0, userDao.finds, "a refused token reads nothing on behalf of its owner");
        }

        @Test
        @DisplayName("a token revoked in the store is rejected (401), whatever its own claim says")
        void revokedInStore() throws Exception {
            build();
            SessionToken token = mintToken();
            storedRow(token.getUuid()).setRevoked(true);

            assertEquals(401, readUsersWith(token).code());
        }

        @Test
        @DisplayName("a token whose stored record was deleted is rejected (401)")
        void deletedInStore() throws Exception {
            build();
            SessionToken token = mintToken();
            tokenDao.getStorage().clear();

            assertEquals(401, readUsersWith(token).code());
            assertEquals(0, strategy.calls, "no stored record, no principal: the strategy is not reached");
        }

        @Test
        @DisplayName("called from Java, outside any pipeline, the verification gives the same answer")
        void outsideAPipeline() throws Exception {
            build();
            SessionToken token = mintToken();

            IAuthentication auth = SecurityAuthenticationExpressions.verifyAuthorization(
                    api, token, new OperationRequest(new java.util.HashMap<>()));

            assertTrue(auth.authenticated());
            assertEquals(1, strategy.calls);
        }
    }
}
