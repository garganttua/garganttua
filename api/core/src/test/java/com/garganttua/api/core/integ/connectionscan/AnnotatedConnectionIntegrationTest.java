package com.garganttua.api.core.integ.connectionscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.definition.IAuthenticatorConnection;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoTenantId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityOwner;
import com.garganttua.api.commons.entity.annotations.EntitySuperOwner;
import com.garganttua.api.commons.entity.annotations.EntitySuperTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenantId;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.annotations.Authentication;
import com.garganttua.api.commons.security.annotations.AuthenticationAuthenticate;
import com.garganttua.api.commons.security.annotations.Authenticator;
import com.garganttua.api.commons.security.annotations.AuthenticatorLogin;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.expression.SecurityExpressions;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/** {@code @AuthenticatorLogin(connection = ...)} declares a connection as {@code .connection(name)} does. */
@DisplayName("Authenticator connections declared by annotations")
class AnnotatedConnectionIntegrationTest extends AbstractCrudScriptTest {

    @Authentication
    public static class PasswordAuth {
        @AuthenticationAuthenticate
        public IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            return of("pw".equals(new String(credentials, StandardCharsets.UTF_8)), principal, "pwd");
        }
    }

    @Authentication
    public static class PscAuth {
        @AuthenticationAuthenticate
        public IAuthentication authenticate(Object principal, byte[] credentials, IAuthenticatorDefinition def) {
            return of("psc-proof".equals(new String(credentials, StandardCharsets.UTF_8)), principal, "psc");
        }
    }

    static IAuthentication of(boolean ok, Object principal, String method) {
        return new com.garganttua.api.commons.security.authentication.Authentication(ok, ok ? principal : null,
                null, ok ? method : null, ok ? List.of() : null, null, null, false, false, true, true, true, true);
    }

    @Entity
    @EntityTenant
    @EntityOwner(ownerId = "uuid")
    @Authenticator(authentications = PasswordAuth.class, scope = AuthenticatorScope.system)
    public static class Member {
        @EntityId @AuthenticatorLogin private String id;
        @AuthenticatorLogin(connection = "psc", scope = AuthenticatorScope.tenant, authentications = PscAuth.class)
        private String rpps;
        @EntityUuid private String uuid;
        @EntityTenantId private String tenantId;
        @EntitySuperTenant private Boolean superTenant;
        @EntitySuperOwner private Boolean superOwner;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getRpps() { return rpps; }
        public void setRpps(String rpps) { this.rpps = rpps; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean v) { this.superTenant = v; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean v) { this.superOwner = v; }
    }

    @Dto(entityClass = Member.class)
    public static class MemberDto {
        @DtoId private String id;
        private String rpps;
        @DtoUuid private String uuid;
        @DtoTenantId private String tenantId;
        private Boolean superTenant;
        private Boolean superOwner;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getRpps() { return rpps; }
        public void setRpps(String rpps) { this.rpps = rpps; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean v) { this.superTenant = v; }
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean v) { this.superOwner = v; }
    }

    private IDomain<?> members;

    @BeforeEach
    void setUp() throws ApiException {
        CapturingDao dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        ((com.garganttua.api.core.api.ApiBuilder) builder).withPackage("com.garganttua.api.core.integ.connectionscan");
        ((com.garganttua.core.dsl.IAutomaticBuilder<?, ?>) builder).autoDetect(true);
        builder.domain(IClass.getClass(Member.class)).dto(IClass.getClass(MemberDto.class)).db(dao).up().up();
        this.members = buildAndStart(builder).getDomain("members").orElseThrow();

        MemberDto ana = new MemberDto();
        ana.setId("ana@example.com");
        ana.setRpps("100");
        ana.setUuid("member-ana");
        ana.setTenantId("T1");
        dao.save(ana);
    }

    @Test
    @DisplayName("the annotated field declares the connection: its login, scope and strategy")
    void declared() {
        IAuthenticatorDefinition authenticator = SecurityExpressions.authenticatorContext(this.members);
        IAuthenticatorConnection psc = authenticator.connections().get(0);

        assertEquals(1, authenticator.connections().size());
        assertEquals("psc", psc.name());
        assertEquals("rpps", psc.login().toString());
        assertEquals(AuthenticatorScope.tenant, psc.scope().orElseThrow());
        assertEquals(1, psc.authenticationDefinitions().size());
        assertEquals("id", authenticator.login().toString(), "the unnamed one stays the default login");
    }

    @Test
    @DisplayName("a login through it finds the member by RPPS and authenticates by its strategy")
    void loginThroughIt() {
        OperationRequest request = tenantScriptRequest(
                OperationDefinition.authenticate("members", IClass.getClass(Member.class)), "T1");
        request.arg("entity", new AuthenticationRequest("100", "psc-proof".getBytes(StandardCharsets.UTF_8)));
        request.arg(IOperationRequest.CONNECTION, "psc");

        WorkflowResult result = executeScript(this.members, request);

        assertEquals(0, result.code());
        IAuthentication authentication = assertInstanceOf(IAuthentication.class, result.output());
        assertEquals("psc", authentication.authorization());
    }
}
