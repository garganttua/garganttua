package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.core.expression.SecurityAuthorizationExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.integ.sync.RecordingMutexManager;
import com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder;
import com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder;
import com.garganttua.api.core.security.authorization.AuthenticationSupplierBuilder;
import com.garganttua.api.core.security.authorization.RefreshedAuthorizationSupplierBuilder;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyRealmBuilder;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.mutex.InterruptibleLeaseMutex;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * A refresh judged by the state of the moment, not by the token it is handed: the stored record (revoked,
 * already exchanged), the account (active), the rights (re-derived), and the issuer the login uses.
 */
@DisplayName("Refresh — revocation, rotation, account state and rights of the moment")
class RefreshRotationIntegrationTest extends AbstractCrudScriptTest {

    private static final String LOGIN = "ana@example.com";
    private static final String PASSWORD = "valid-password";

    // ───── Fixtures ─────

    /** The authorization; its DTO stores every field, as a real deployment does. */
    public static class Token {
        private String id;
        private String uuid;
        private String tenantId;
        private String ownerId;
        private String tokenType;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private byte[] signature;
        private Instant refreshExpiresAt;
        private Boolean refreshRevoked = false;
        private Boolean superTenant = false;
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
        public byte[] getSignature() { return signature; }
        public void setSignature(byte[] signature) { this.signature = signature; }
        public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
        public void setRefreshExpiresAt(Instant v) { this.refreshExpiresAt = v; }
        public Boolean getRefreshRevoked() { return refreshRevoked; }
        public void setRefreshRevoked(Boolean v) { this.refreshRevoked = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public byte[] getDataToSign() {
            return (uuid + "|" + ownerId + "|" + tenantId + "|" + tokenType + "|" + authorities)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    public static class TokenDto {
        private String id;
        private String uuid;
        private String tenantId;
        private String ownerId;
        private String tokenType;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private byte[] signature;
        private Instant refreshExpiresAt;
        private Boolean refreshRevoked;
        private Boolean superTenant;
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
        public byte[] getSignature() { return signature; }
        public void setSignature(byte[] signature) { this.signature = signature; }
        public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
        public void setRefreshExpiresAt(Instant v) { this.refreshExpiresAt = v; }
        public Boolean getRefreshRevoked() { return refreshRevoked; }
        public void setRefreshRevoked(Boolean v) { this.refreshRevoked = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    /** The principal: its rights live on the entity and can change between a login and a refresh. */
    public static class Account {
        private String id;
        private String uuid;
        private String tenantId;
        private Boolean enabled = true;
        private List<String> rights;
        private Boolean superTenant = false;
        private Boolean superOwner = false;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public List<String> getRights() { return rights; }
        public void setRights(List<String> rights) { this.rights = rights; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
    }

    public static class AccountDto {
        private String id;
        private String uuid;
        private String tenantId;
        private Boolean enabled;
        private List<String> rights;
        private Boolean superTenant;
        private Boolean superOwner;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public List<String> getRights() { return rights; }
        public void setRights(List<String> rights) { this.rights = rights; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
    }

    /** Derives the rights from the account — at login, and again at refresh when it declares one. */
    public static class RightsAuthentication {
        public IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            boolean ok = PASSWORD.equals(new String(credentials, StandardCharsets.UTF_8));
            return of(ok, principal);
        }

        public IAuthentication refresh(Object principal, IAuthenticatorDefinition def) {
            Account account = (Account) principal;
            return of(account.getRights() == null || !account.getRights().contains("NO_REFRESH"), principal);
        }

        private static IAuthentication of(boolean ok, Object principal) {
            return new Authentication(ok, ok ? principal : null, null, ok ? "bearer" : null,
                    ok ? ((Account) principal).getRights() : null, null, null, false, false, true, true, true, true);
        }
    }

    /** A declared issuer: it numbers what it issues, so a refresh proves it went through it. */
    public static class StampingIssuer {
        private int issued;

        public Object issue(IAuthentication auth, Object replaced) {
            Account account = (Account) auth.principal();
            Token token = new Token();
            token.setUuid(UUID.randomUUID().toString());
            token.setTenantId(account.getTenantId());
            token.setOwnerId(OwnerIds.qualify("accounts", account.getUuid()));
            // What a real issuer does with the replaced authorization: carry over what it put in it at login.
            token.setTokenType("STAMPED-" + (++this.issued)
                    + (replaced instanceof Token previous ? "<-" + previous.getUuid() : ""));
            token.setAuthorities(auth.authorities());
            token.setExpiresAt(Instant.now().plusSeconds(900));
            token.setRevoked(false);
            token.setRefreshExpiresAt(Instant.now().plusSeconds(3600));
            token.setRefreshRevoked(false);
            return token;
        }
    }

    /** One API, three switches: a refresh strategy declared, a custom issuer, the account enabled field. */
    private final class Fixture {
        final CapturingDao accounts = new CapturingDao();
        final CapturingDao tokens = new CapturingDao();
        final IDomain<?> accountDomain;

        Fixture(boolean refreshStrategy, boolean issuer) throws Exception {
            this(refreshStrategy, issuer, null);
        }

        Fixture(boolean refreshStrategy, boolean issuer, RecordingMutexManager locks) throws Exception {
            IKeyRealm realm = KeyRealmBuilder.builder().name("rotation-realm").algorithm(KeyAlgorithm.EC_256)
                    .signatureAlgorithm(SignatureAlgorithm.SHA256).build();
            IApiBuilder builder = newBuilder();
            var auth = builder.security().authentication(
                    new FixedSupplierBuilder<>(new RightsAuthentication(), IClass.getClass(RightsAuthentication.class)));
            auth.authenticate("authenticate")
                    .withParam(0, new PrincipalSupplierBuilder())
                    .withParam(1, new AuthenticateCredentialsSupplierBuilder())
                    .withParam(2, new AuthenticatorDefinitionSupplierBuilder());
            if (refreshStrategy) {
                auth.refresh("refresh")
                        .withParam(0, new PrincipalSupplierBuilder())
                        .withParam(1, new AuthenticatorDefinitionSupplierBuilder());
            }
            auth.up();

            var tokenDomain = builder.domain(IClass.getClass(Token.class))
                    .tenant(true).superTenant("superTenant").owned("ownerId")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(TokenDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(tokens).up();
            if (locks != null) {
                tokenDomain.synchronization(locks, IClass.getClass(InterruptibleLeaseMutex.class));
            }
            var authorization = tokenDomain.security().authorization()
                    .type("tokenType").authorities("authorities").expirable("expiresAt").revokable("revoked");
            if (!issuer) {
                authorization.signable().signature("signature").getDataToSign("getDataToSign").up();
            }
            authorization.refreshable().expirable("refreshExpiresAt").revokable("refreshRevoked").up();
            authorization.up().up();

            @SuppressWarnings("rawtypes")
            var accountDomainBuilder = builder.domain(IClass.getClass(Account.class))
                    .tenant(true).superTenant("superTenant").owner("uuid").superOwner("superOwner")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(AccountDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(accounts).up();
            var authenticator = accountDomainBuilder.security().authenticator()
                    .login("id").scope(AuthenticatorScope.tenant).enabled("enabled").authentication(auth);
            if (issuer) {
                authenticator.authorization(
                        new FixedSupplierBuilder<>(new StampingIssuer(), IClass.getClass(StampingIssuer.class)), "issue")
                        .withParam(0, new AuthenticationSupplierBuilder())
                        .withParam(1, new RefreshedAuthorizationSupplierBuilder(), true);
            }
            var link = authenticator.authorization((IDomainBuilder) tokenDomain)
                    .lifeTime(15, TimeUnit.MINUTES).refreshLifeTime(8, TimeUnit.HOURS);
            if (!issuer) {
                link.key(new RefreshAuthorizationIntegrationTest.FixedKeyRealmSupplierBuilder(realm));
            }
            accountDomainBuilder.up();

            IApi api = buildAndStart(builder);
            this.accountDomain = api.getDomain("accounts").orElseThrow();

            AccountDto ana = new AccountDto();
            ana.setId(LOGIN);
            ana.setUuid("account-ana");
            ana.setTenantId("SUPER_TENANT");
            ana.setEnabled(true);
            ana.setRights(List.of("READ"));
            this.accounts.save(ana);
        }

        Token login() throws ApiException {
            OperationRequest request = superTenantScriptRequest(
                    OperationDefinition.authenticate("accounts", IClass.getClass(Account.class)));
            request.arg("entity", new AuthenticationRequest(LOGIN, PASSWORD.getBytes(StandardCharsets.UTF_8)));
            WorkflowResult result = executeScript(this.accountDomain, request);
            assertEquals(0, result.code(), () -> "login failed on " + failure(request));
            return (Token) result.output();
        }

        Refreshed refresh(Token token) {
            OperationRequest request = superTenantScriptRequest(
                    OperationDefinition.refreshAuthorization("accounts", IClass.getClass(Account.class)));
            request.arg("entity", token);
            WorkflowResult result = executeScript(this.accountDomain, request);
            return new Refreshed(result.code(), result.output() instanceof Token t ? t : null, failure(request));
        }

        TokenDto stored(Token token) {
            return this.tokens.getStorage().stream().map(TokenDto.class::cast)
                    .filter(dto -> dto.getUuid().equals(token.getUuid())).findFirst().orElseThrow();
        }

        AccountDto account() {
            return (AccountDto) this.accounts.getStorage().get(0);
        }
    }

    private record Refreshed(int code, Token token, Object failure) {
    }

    private static Object failure(OperationRequest request) {
        return request.arg(SecurityAuthorizationExpressions.LAST_EXCEPTION_ARG).map(e -> ((Throwable) e).getMessage())
                .orElse(null);
    }

    // ───── Tests ─────

    @Nested
    @DisplayName("the stored record")
    class StoredRecord {

        @Test
        @DisplayName("a session revoked in the store does not reopen by refreshing")
        void revokedInStore() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            api.stored(login).setRevoked(true);

            Refreshed refreshed = api.refresh(login);

            assertEquals(401, refreshed.code());
            assertEquals("Authorization not found or revoked", refreshed.failure());
        }

        @Test
        @DisplayName("an authorization deleted from the store cannot be refreshed")
        void absentFromStore() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            api.tokens.getStorage().clear();

            assertEquals(401, api.refresh(login).code());
        }
    }

    @Nested
    @DisplayName("rotation")
    class Rotation {

        @Test
        @DisplayName("the exchanged authorization stays usable until it expires, but can no longer be refreshed")
        void exchangedIsSpent() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();

            Refreshed refreshed = api.refresh(login);

            assertEquals(0, refreshed.code(), () -> "refresh failed on " + refreshed.failure());
            assertNotEquals(login.getUuid(), refreshed.token().getUuid());
            assertTrue(api.stored(login).getRefreshRevoked(), "the exchanged one is marked spent");
            assertFalse(api.stored(login).getRevoked(), "its access is left to expire, not cut");
            assertFalse(api.stored(refreshed.token()).getRefreshRevoked(), "the new one is refreshable");
        }

        @Test
        @DisplayName("a spent authorization presented again revokes every live authorization of the owner")
        void reuseRevokesTheLineage() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            Token legitimate = api.refresh(login).token();

            Refreshed replay = api.refresh(login);

            assertEquals(401, replay.code());
            assertEquals("This authorization has already been refreshed", replay.failure());
            assertTrue(api.stored(legitimate).getRevoked(), "the token issued to the other party is revoked too");
            assertEquals(401, api.refresh(legitimate).code(), "and can no longer be refreshed");
        }

        @Test
        @DisplayName("under the authorization domain's lock, concurrent refreshes of one token: one succeeds")
        void concurrentRefreshesOneWins() throws Exception {
            Fixture api = new Fixture(true, false, new RecordingMutexManager());
            Token login = api.login();
            int racers = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            try {
                List<Future<Refreshed>> outcomes = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        return api.refresh(login);
                    }));
                }
                start.countDown();
                int succeeded = 0;
                for (Future<Refreshed> outcome : outcomes) {
                    Refreshed refreshed = outcome.get(30, TimeUnit.SECONDS);
                    if (refreshed.code() == 0) {
                        succeeded++;
                    } else {
                        assertEquals(401, refreshed.code(), () -> "unexpected failure: " + refreshed.failure());
                    }
                }
                assertEquals(1, succeeded, "one token, one exchange");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a refresh does not push back the refresh window: a session lasts refreshLifeTime from the login")
        void windowFixedAtLogin() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            Thread.sleep(5);

            Token second = api.refresh(login).token();
            Token third = api.refresh(second).token();

            assertEquals(login.getRefreshExpiresAt(), second.getRefreshExpiresAt());
            assertEquals(login.getRefreshExpiresAt(), third.getRefreshExpiresAt());
            assertTrue(third.getExpiresAt().isAfter(login.getExpiresAt()), "the access part is renewed");
        }

        @Test
        @DisplayName("a login does not hand back an authorization already exchanged")
        void loginSkipsSpent() throws Exception {
            Fixture api = new Fixture(true, false);
            Token first = api.login();
            api.refresh(first);

            Token second = api.login();

            assertNotEquals(first.getUuid(), second.getUuid());
            assertEquals(0, api.refresh(second).code(), "and what it hands back can be refreshed");
        }
    }

    @Nested
    @DisplayName("the principal")
    class Principal {

        @Test
        @DisplayName("a suspended account cannot refresh")
        void suspended() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            api.account().setEnabled(false);

            Refreshed refreshed = api.refresh(login);

            assertEquals(401, refreshed.code());
            assertEquals("Account is disabled", refreshed.failure());
        }

        @Test
        @DisplayName("the refresh strategy decides the rights of the refreshed token")
        void rightsOfTheMoment() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            api.account().setRights(List.of("READ", "WRITE"));

            Refreshed refreshed = api.refresh(login);

            assertEquals(0, refreshed.code(), () -> "refresh failed on " + refreshed.failure());
            assertEquals(List.of("READ"), login.getAuthorities());
            assertEquals(List.of("READ", "WRITE"), refreshed.token().getAuthorities());
        }

        @Test
        @DisplayName("a refresh strategy that refuses refuses the refresh")
        void strategyRefuses() throws Exception {
            Fixture api = new Fixture(true, false);
            Token login = api.login();
            api.account().setRights(List.of("NO_REFRESH"));

            Refreshed refreshed = api.refresh(login);

            assertEquals(401, refreshed.code());
            assertEquals("The authentication can no longer be refreshed", refreshed.failure());
        }

        @Test
        @DisplayName("without a refresh strategy, the rights of the replaced token are copied")
        void noStrategyCopies() throws Exception {
            Fixture api = new Fixture(false, false);
            Token login = api.login();
            api.account().setRights(List.of("READ", "WRITE"));

            Refreshed refreshed = api.refresh(login);

            assertEquals(0, refreshed.code(), () -> "refresh failed on " + refreshed.failure());
            assertEquals(List.of("READ"), refreshed.token().getAuthorities());
        }
    }

    @Nested
    @DisplayName("the issuer")
    class Issuer {

        @Test
        @DisplayName("a refresh goes through the declared issuer, handed the authorization it replaces")
        void declaredIssuer() throws Exception {
            Fixture api = new Fixture(true, true);
            Token login = api.login();
            assertEquals("STAMPED-1", login.getTokenType());

            Refreshed refreshed = api.refresh(login);

            assertEquals(0, refreshed.code(), () -> "refresh failed on " + refreshed.failure());
            assertEquals("STAMPED-2<-" + login.getUuid(), refreshed.token().getTokenType(),
                    "issued by the issuer, which was handed the authorization it replaces");
            assertTrue(api.stored(login).getRefreshRevoked(), "rotated as well");
            assertFalse(api.stored(refreshed.token()).getRefreshRevoked(), "and the issued one is stored");
        }
    }
}
