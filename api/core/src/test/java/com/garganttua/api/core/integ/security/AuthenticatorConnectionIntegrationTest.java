package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.expression.SecurityAuthorizationExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.integ.security.RefreshRotationIntegrationTest.Token;
import com.garganttua.api.core.integ.security.RefreshRotationIntegrationTest.TokenDto;
import com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder;
import com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder;
import com.garganttua.api.core.security.authorization.RefreshedAuthorizationSupplierBuilder;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * An authenticator reached by a second login field — a professional number, unique only within a tenant — with
 * its own strategy, issuing the domain's own token.
 */
@DisplayName("Authenticator connections — a second login field with its own strategy")
class AuthenticatorConnectionIntegrationTest extends AbstractCrudScriptTest {

    // ───── Fixtures ─────

    public static class Account {
        private String id;
        private String uuid;
        private String tenantId;
        private String rpps;
        private Boolean superTenant = false;
        private Boolean superOwner = false;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getRpps() { return rpps; }
        public void setRpps(String rpps) { this.rpps = rpps; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
    }

    public static class AccountDto {
        private String id;
        private String uuid;
        private String tenantId;
        private String rpps;
        private Boolean superTenant;
        private Boolean superOwner;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getRpps() { return rpps; }
        public void setRpps(String rpps) { this.rpps = rpps; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
    }

    /** One login method: a secret it accepts, the method it stamps on the token, and the refresh of its tokens. */
    static final class Method {
        private final String secret;
        private final String name;
        volatile String loginFieldSeen;

        Method(String secret, String name) {
            this.secret = secret;
            this.name = name;
        }

        IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            this.loginFieldSeen = def.login().toString();
            return of(this.secret.equals(new String(credentials, StandardCharsets.UTF_8)), principal);
        }

        /** Declines a token issued by another method: the next strategy is tried. */
        IAuthentication refresh(Object principal, Object replaced) {
            return of(replaced instanceof Token token && this.name.equals(token.getTokenType()), principal);
        }

        private IAuthentication of(boolean ok, Object principal) {
            return new Authentication(ok, ok ? principal : null, null, ok ? this.name : null,
                    ok ? List.of("READ") : null, null, null, false, false, true, true, true, true);
        }
    }

    /** The password strategy — a class of its own: strategies are registered by class. */
    public static class Password {
        static final Method METHOD = new Method("pw", "pwd");
        public IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            return METHOD.authenticate(principal, credentials, def);
        }
        public IAuthentication refresh(Object principal, IAuthenticatorDefinition def, Object replaced) {
            return METHOD.refresh(principal, replaced);
        }
    }

    /** The Pro Santé Connect strategy. */
    public static class Psc {
        static final Method METHOD = new Method("psc-proof", "psc");
        public IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            return METHOD.authenticate(principal, credentials, def);
        }
        public IAuthentication refresh(Object principal, IAuthenticatorDefinition def, Object replaced) {
            return METHOD.refresh(principal, replaced);
        }
    }

    private final CapturingDao accounts = new CapturingDao();
    private final CapturingDao tokens = new CapturingDao();
    private IDomain<?> accountDomain;

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @BeforeEach
    void setUp() throws Exception {
        IApiBuilder builder = newBuilder();
        var passwordAuth = strategy(builder, new Password(), IClass.getClass(Password.class));
        var pscAuth = strategy(builder, new Psc(), IClass.getClass(Psc.class));

        var tokenDomain = builder.domain(IClass.getClass(Token.class))
                .tenant(true).superTenant("superTenant").owned("ownerId")
                .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                .dto(IClass.getClass(TokenDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(this.tokens).up();
        tokenDomain.security().authorization()
                .type("tokenType").authorities("authorities").expirable("expiresAt").revokable("revoked")
                .refreshable().expirable("refreshExpiresAt").revokable("refreshRevoked").up()
                .up().up();

        var accountDomainBuilder = builder.domain(IClass.getClass(Account.class))
                .tenant(true).superTenant("superTenant").owner("uuid").superOwner("superOwner")
                .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                .dto(IClass.getClass(AccountDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(this.accounts).up();
        var authenticator = accountDomainBuilder.security().authenticator()
                .login("id").scope(AuthenticatorScope.system).alwaysEnabled(true);
        authenticator.connection("psc").login("rpps").scope(AuthenticatorScope.tenant).authentication(pscAuth).up();
        authenticator.authentication(passwordAuth)
                .authorization((IDomainBuilder) tokenDomain)
                .lifeTime(15, TimeUnit.MINUTES).refreshLifeTime(10, TimeUnit.HOURS);
        accountDomainBuilder.up();

        IApi api = buildAndStart(builder);
        this.accountDomain = api.getDomain("accounts").orElseThrow();
        account("ana@example.com", "account-ana", "T1", "100");
        account("bob@example.com", "account-bob", "T2", "100");
    }

    private static <T> com.garganttua.api.commons.context.dsl.security.IAuthenticationBuilder<?> strategy(
            IApiBuilder builder, T strategy, IClass<T> type) throws ApiException {
        var auth = builder.security().authentication(new FixedSupplierBuilder<>(strategy, type));
        auth.authenticate("authenticate")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new AuthenticatorDefinitionSupplierBuilder());
        auth.refresh("refresh")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new AuthenticatorDefinitionSupplierBuilder())
                .withParam(2, new RefreshedAuthorizationSupplierBuilder(), true);
        auth.up();
        return auth;
    }

    private void account(String email, String uuid, String tenant, String rpps) throws ApiException {
        AccountDto dto = new AccountDto();
        dto.setId(email);
        dto.setUuid(uuid);
        dto.setTenantId(tenant);
        dto.setRpps(rpps);
        this.accounts.save(dto);
    }

    private record Outcome(int code, Token token, String failure) {
    }

    /** A login: through {@code connection} when not null, from a caller of {@code tenant}. */
    private Outcome login(String connection, String tenant, String login, String secret) {
        OperationDefinition op = OperationDefinition.authenticate("accounts", IClass.getClass(Account.class));
        OperationRequest request = tenant == null ? superTenantScriptRequest(op) : tenantScriptRequest(op, tenant);
        request.arg("entity", new AuthenticationRequest(login, secret.getBytes(StandardCharsets.UTF_8)));
        if (connection != null) {
            request.arg(IOperationRequest.CONNECTION, connection);
        }
        return outcome(request, executeScript(this.accountDomain, request));
    }

    private Outcome refresh(Token token) {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.refreshAuthorization("accounts", IClass.getClass(Account.class)));
        request.arg("entity", token);
        return outcome(request, executeScript(this.accountDomain, request));
    }

    private static Outcome outcome(OperationRequest request, WorkflowResult result) {
        String failure = request.arg(SecurityAuthorizationExpressions.LAST_EXCEPTION_ARG)
                .map(e -> ((Throwable) e).getMessage()).orElse(null);
        return new Outcome(result.code(), result.output() instanceof Token t ? t : null, failure);
    }

    // ───── Tests ─────

    @Nested
    @DisplayName("a named connection")
    class Connection {

        @Test
        @DisplayName("finds the account by its own field, within the request's tenant, and issues the domain's token")
        void foundByItsFieldWithinTheTenant() {
            Outcome ana = login("psc", "T1", "100", "psc-proof");
            Outcome bob = login("psc", "T2", "100", "psc-proof");

            assertEquals(0, ana.code(), () -> "failed on " + ana.failure());
            assertEquals("accounts:account-ana", ana.token().getOwnerId(), "the domain's owner, not another domain's");
            assertEquals("psc", ana.token().getTokenType());
            assertEquals("rpps", Psc.METHOD.loginFieldSeen, "the strategy sees the connection's login field");
            assertEquals(0, bob.code(), () -> "failed on " + bob.failure());
            assertEquals("accounts:account-bob", bob.token().getOwnerId(), "the same RPPS, another tenant");
        }

        @Test
        @DisplayName("the default login is unchanged")
        void defaultUnchanged() {
            Outcome login = login(null, null, "ana@example.com", "pw");

            assertEquals(0, login.code(), () -> "failed on " + login.failure());
            assertEquals("accounts:account-ana", login.token().getOwnerId());
            assertEquals("pwd", login.token().getTokenType());
            assertEquals("id", Password.METHOD.loginFieldSeen);
        }

        @Test
        @DisplayName("only its own strategies are tried: a password through it is refused")
        void ownStrategiesOnly() {
            assertEquals(401, login("psc", "T1", "100", "pw").code());
        }

        @Test
        @DisplayName("an unknown connection is refused with 400, naming it")
        void unknownConnection() {
            Outcome login = login("cps", "T1", "100", "psc-proof");

            assertEquals(400, login.code());
            assertEquals("Unknown connection 'cps' on this authenticator", login.failure());
        }
    }

    @Nested
    @DisplayName("a login that matches several accounts")
    class Duplicates {

        @Test
        @DisplayName("is refused through a connection, instead of taking the first")
        void throughAConnection() throws ApiException {
            account("ana.other@example.com", "account-ana-2", "T1", "100");

            assertEquals(401, login("psc", "T1", "100", "psc-proof").code());
        }

        @Test
        @DisplayName("is refused through the default login as well")
        void throughTheDefault() throws ApiException {
            account("ana@example.com", "account-ana-copy", "T2", "200");

            assertEquals(401, login(null, null, "ana@example.com", "pw").code());
        }
    }

    @Nested
    @DisplayName("tokens")
    class Tokens {

        @Test
        @DisplayName("a login by another method is not handed the token of the session opened otherwise")
        void reuseByMethod() {
            Token byPassword = login(null, null, "ana@example.com", "pw").token();
            Token byPsc = login("psc", "T1", "100", "psc-proof").token();
            Token byPasswordAgain = login(null, null, "ana@example.com", "pw").token();

            assertNotEquals(byPassword.getUuid(), byPsc.getUuid());
            assertEquals("psc", byPsc.getTokenType());
            assertEquals(byPassword.getUuid(), byPasswordAgain.getUuid(), "the same method still reuses its token");
        }

        @Test
        @DisplayName("a refresh re-derives the authentication through the strategy of the token's method")
        void refreshByMethod() {
            Outcome psc = refresh(login("psc", "T1", "100", "psc-proof").token());
            Outcome pwd = refresh(login(null, null, "ana@example.com", "pw").token());

            assertEquals(0, psc.code(), () -> "failed on " + psc.failure());
            assertEquals("psc", psc.token().getTokenType());
            assertEquals(0, pwd.code(), () -> "failed on " + pwd.failure());
            assertEquals("pwd", pwd.token().getTokenType());
        }
    }
}
