package com.garganttua.api.core.integ.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyRealmBuilder;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Proves that {@code signedBy} sits INSIDE the signed surface.
 *
 * <p>{@code verifyTokenSignature} needs the qualified {@code signedBy} to
 * resolve the exact signing key, so the value must travel on the wire. For a
 * compact 3-segment JWT everything that travels is header or payload — i.e.
 * covered by the signature. An entity is therefore entitled to include
 * {@code signedBy} in its {@code getDataToSign()}, and the framework must
 * stamp it BEFORE computing the signature.
 *
 * <p>Every test here fails when {@code signIfSignable} stamps after signing:
 * the bytes signed (signedBy = null) differ from the bytes verified
 * (signedBy = the realm id).
 */
@DisplayName("signedBy is covered by the signature (stamped before signing)")
class SignedBySealedIntegrationTest extends AbstractCrudScriptTest {

    /**
     * Authorization entity whose signed payload DELIBERATELY includes
     * {@code signedBy} — the JWT-conformant shape.
     */
    public static class SealedTokenEntity {
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
        private String signedBy;
        private Boolean superTenant = false;

        public SealedTokenEntity() {}

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
        public String getSignedBy() { return signedBy; }
        public void setSignedBy(String signedBy) { this.signedBy = signedBy; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }

        /** Signed payload — includes {@code signedBy}, exactly as a JWT header/payload would. */
        public byte[] getDataToSign() {
            String payload = String.valueOf(uuid)
                    + "|" + String.valueOf(ownerId)
                    + "|" + String.valueOf(tenantId)
                    + "|" + String.valueOf(tokenType)
                    + "|" + String.valueOf(signedBy);
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }

    public static class SealedTokenDto {
        private String id;
        private String uuid;
        private String tenantId;
        private Boolean superTenant;

        public SealedTokenDto() {}
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    /** Minimal {@link ISupplierBuilder} adapter for an already-built {@link IKeyRealm}. */
    static class FixedKeyRealmSupplierBuilder implements ISupplierBuilder<IKeyRealm, ISupplier<IKeyRealm>> {
        private final IKeyRealm realm;

        FixedKeyRealmSupplierBuilder(IKeyRealm realm) {
            this.realm = realm;
        }

        @Override
        public IClass<IKeyRealm> getSuppliedClass() {
            return IClass.getClass(IKeyRealm.class);
        }

        @Override
        public java.lang.reflect.Type getSuppliedType() {
            return IKeyRealm.class;
        }

        @Override
        public boolean isContextual() {
            return false;
        }

        @Override
        public ISupplier<IKeyRealm> build() {
            return new ISupplier<IKeyRealm>() {
                @Override public Optional<IKeyRealm> supply() { return Optional.of(realm); }
                @Override public java.lang.reflect.Type getSuppliedType() { return IKeyRealm.class; }
                @Override public IClass<IKeyRealm> getSuppliedClass() { return IClass.getClass(IKeyRealm.class); }
            };
        }
    }

    private IApi context;
    private IDomain<?> userCtx;
    private CapturingDao userDao;
    private CapturingDao tokenDao;
    private IKeyRealm keyRealm;

    @BeforeEach
    void setUp() throws Exception {
        userDao = new CapturingDao();
        tokenDao = new CapturingDao();

        keyRealm = KeyRealmBuilder.builder()
                .name("sealed-key-realm")
                .algorithm(KeyAlgorithm.EC_256)
                .signatureAlgorithm(SignatureAlgorithm.SHA256)
                .build();

        StubAuthentication stubAuth = new StubAuthentication();
        IApiBuilder builder = newBuilder();

        var authBuilder = builder.security()
                .authentication(new FixedSupplierBuilder<>(stubAuth, IClass.getClass(StubAuthentication.class)));
        authBuilder.authenticate("authenticate")
                .withParam(0, new com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder())
                .withParam(1, new com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder());
        authBuilder.up();

        var tokenDomainBuilder = builder.domain(IClass.getClass(SealedTokenEntity.class))
                .tenant(true)
                .superTenant("superTenant")
                .owned("ownerId")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(SealedTokenDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(tokenDao)
                .up()
                .security()
                    .authorization()
                        .type("tokenType")
                        .authorities("authorities")
                        .expirable("expiresAt")
                        .revokable("revoked")
                        .signedBy("signedBy")
                        .signable()
                            .signature("signature")
                            .getDataToSign("getDataToSign")
                        .up()
                    .up()
                .up();

        StubTokenAuthentication stubTokenAuth = new StubTokenAuthentication();
        var tokenAuthBuilder = builder.security()
                .authentication(new FixedSupplierBuilder<>(stubTokenAuth, IClass.getClass(StubTokenAuthentication.class)));
        tokenAuthBuilder.authenticate("authenticate")
                .withParam(0, new com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder())
                .withParam(1, new com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder());
        tokenAuthBuilder.up();

        tokenDomainBuilder.security()
                .authenticator()
                    .login("uuid")
                    .scope(AuthenticatorScope.tenant)
                    .alwaysEnabled(true)
                    .authentication(tokenAuthBuilder);

        @SuppressWarnings("rawtypes")
        var userDomainBuilder = builder.domain(IClass.getClass(User.class))
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

        var authenticatorBuilder = userDomainBuilder.security()
                .authenticator()
                    .login("id")
                    .scope(AuthenticatorScope.tenant)
                    .alwaysEnabled(true);
        authenticatorBuilder.authentication(authBuilder)
                    .authorization((com.garganttua.api.commons.context.dsl.IDomainBuilder) tokenDomainBuilder)
                        .lifeTime(60, java.util.concurrent.TimeUnit.MINUTES)
                        .key(new FixedKeyRealmSupplierBuilder(keyRealm));

        userDomainBuilder.up();

        context = buildAndStart(builder);
        userCtx = context.getDomain("users").orElseThrow();

        UserDto existingUser = new UserDto();
        existingUser.setId("john@example.com");
        existingUser.setUuid("user-uuid-1");
        existingUser.setTenantId("SUPER_TENANT");
        existingUser.setName("John");
        userDao.save(existingUser);
    }

    private OperationRequest authenticateRequest(String login, String password) {
        AuthenticationRequest authReq = new AuthenticationRequest(
                login, password.getBytes(StandardCharsets.UTF_8));
        OperationDefinition authOp = OperationDefinition.authenticate("users", IClass.getClass(User.class));
        OperationRequest request = superTenantScriptRequest(authOp);
        request.arg("entity", authReq);
        return request;
    }

    private SealedTokenEntity authenticateAndGetToken() throws ApiException {
        WorkflowResult result = executeScript(userCtx, authenticateRequest("john@example.com", "valid-password"));
        assertEquals(0, result.code(), "workflow should succeed with code 0");
        assertInstanceOf(SealedTokenEntity.class, result.output());
        return (SealedTokenEntity) result.output();
    }

    @Nested
    @DisplayName("Ordering: stamp signedBy, then sign")
    class StampBeforeSign {

        @Test
        @DisplayName("signedBy is populated on the emitted token")
        void signedByIsStamped() throws ApiException {
            SealedTokenEntity token = authenticateAndGetToken();

            assertEquals("sealed-key-realm", token.getSignedBy(),
                    "signedBy must carry the signing realm's id");
        }

        @Test
        @DisplayName("a token whose getDataToSign() INCLUDES signedBy verifies end-to-end")
        void signedByIsInsideTheSignedSurface() throws Exception {
            SealedTokenEntity token = authenticateAndGetToken();

            assertNotNull(token.getSignature(), "signature must be populated by signIfSignable");
            assertTrue(token.getSignature().length > 0, "signature must be non-empty");

            // getDataToSign() reads the CURRENT signedBy. If the framework stamped it
            // after signing, the signed bytes carried signedBy=null and this fails.
            assertTrue(keyRealm.getKeyForSignatureVerification()
                            .verifySignature(token.getSignature(), token.getDataToSign()),
                    "the signature must cover the stamped signedBy — stamping must happen before signing");
        }

        @Test
        @DisplayName("the signed bytes are the signedBy-bearing ones, not the null-signedBy ones")
        void nullSignedByPayloadDoesNotVerify() throws Exception {
            SealedTokenEntity token = authenticateAndGetToken();

            // Rebuild the payload the OLD (buggy) order would have signed: same
            // entity, signedBy still null. It must NOT verify — proving the
            // signature was computed over the stamped value.
            String nullSignedByPayload = String.valueOf(token.getUuid())
                    + "|" + String.valueOf(token.getOwnerId())
                    + "|" + String.valueOf(token.getTenantId())
                    + "|" + String.valueOf(token.getTokenType())
                    + "|" + String.valueOf((Object) null);

            assertFalse(keyRealm.getKeyForSignatureVerification().verifySignature(
                            token.getSignature(), nullSignedByPayload.getBytes(StandardCharsets.UTF_8)),
                    "the pre-stamp payload must not verify — otherwise signedBy is outside the signed surface");
        }

        @Test
        @DisplayName("tampering with signedBy after emission breaks verification")
        void tamperingWithSignedByBreaksVerification() throws Exception {
            SealedTokenEntity token = authenticateAndGetToken();

            token.setSignedBy("attacker-controlled-realm");

            assertFalse(keyRealm.getKeyForSignatureVerification()
                            .verifySignature(token.getSignature(), token.getDataToSign()),
                    "signedBy must be tamper-evident — it is part of the signed payload");
        }
    }
}
