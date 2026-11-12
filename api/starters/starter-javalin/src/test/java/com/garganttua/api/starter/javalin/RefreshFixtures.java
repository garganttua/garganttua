package com.garganttua.api.starter.javalin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.security.authentication.Authentication;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.core.security.authentication.AuthenticateCredentialsSupplierBuilder;
import com.garganttua.api.core.security.authentication.AuthenticatorDefinitionSupplierBuilder;
import com.garganttua.api.core.security.authentication.PrincipalSupplierBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;

/**
 * An authenticator ({@code accounts}) whose sessions ({@code sessions}) are refreshable, travel as a string and
 * are stored — what an application refreshing short tokens over HTTP declares.
 */
final class RefreshFixtures {

    static final String LOGIN = "ana";
    static final String PASSWORD = "secret";

    private RefreshFixtures() {
    }

    /** Declares both domains on the application's builder. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static void declare(IApiBuilder builder) throws ApiException {
        var auth = builder.security().authentication(
                new FixedSupplierBuilder<>(new Strategy(), IClass.getClass(Strategy.class)));
        auth.authenticate("authenticate")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new AuthenticateCredentialsSupplierBuilder())
                .withParam(2, new AuthenticatorDefinitionSupplierBuilder());
        auth.refresh("refresh")
                .withParam(0, new PrincipalSupplierBuilder())
                .withParam(1, new AuthenticatorDefinitionSupplierBuilder());
        auth.up();

        var sessions = builder.domain(IClass.getClass(Session.class))
                .owned("ownerId")
                .entity().id("id").uuid("uuid").up()
                .dto(IClass.getClass(SessionDto.class)).id("id").uuid("uuid").up();
        sessions.security().authorization()
                .authorities("authorities").expirable("expiresAt").revokable("revoked")
                .encode("toWire").decode("fromWire")
                .refreshable().expirable("refreshExpiresAt").revokable("refreshRevoked").up()
                .up().up();

        var accounts = builder.domain(IClass.getClass(Account.class))
                .owner("uuid").superOwner("superOwner")
                .entity().id("id").uuid("uuid").up()
                .dto(IClass.getClass(AccountDto.class)).id("id").uuid("uuid").up();
        accounts.security().authenticator()
                .login("id").alwaysEnabled(true).authentication(auth)
                .authorization((IDomainBuilder) sessions)
                .lifeTime(15, java.util.concurrent.TimeUnit.MINUTES)
                .refreshLifeTime(10, java.util.concurrent.TimeUnit.HOURS);
        accounts.up();
    }

    /** Login checks the password; refresh re-derives the rights from the account. */
    public static class Strategy {
        public IAuthentication authenticate(Object principal, Object credentials, IAuthenticatorDefinition def) {
            String given = credentials instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(credentials);
            return of(PASSWORD.equals(given), principal);
        }

        public IAuthentication refresh(Object principal, IAuthenticatorDefinition def) {
            return of(true, principal);
        }

        private static IAuthentication of(boolean ok, Object principal) {
            return new Authentication(ok, ok ? principal : null, null, null, ok ? List.of("READ") : null,
                    null, null, false, false, true, true, true, true);
        }
    }

    public static class Account {
        private String id;
        private String uuid;
        private Boolean superOwner = false;
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    public static class AccountDto {
        private String id;
        private String uuid;
        private Boolean superOwner = null;
        public Boolean getSuperOwner() { return superOwner; }
        public void setSuperOwner(Boolean superOwner) { this.superOwner = superOwner; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    /** A session, written on the wire as {@code base64(uuid;ownerId;authorities;expires;refreshExpires)}. */
    public static class Session {
        private String id;
        private String uuid;
        private String ownerId;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked = false;
        private Instant refreshExpiresAt;
        private Boolean refreshRevoked = false;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
        public void setRefreshExpiresAt(Instant v) { this.refreshExpiresAt = v; }
        public Boolean getRefreshRevoked() { return refreshRevoked; }
        public void setRefreshRevoked(Boolean v) { this.refreshRevoked = v; }

        public String toWire() {
            String payload = String.join(";", uuid, ownerId, String.join(",", authorities == null ? List.of() : authorities),
                    String.valueOf(expiresAt.toEpochMilli()), String.valueOf(refreshExpiresAt.toEpochMilli()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        }

        public void fromWire(byte[] raw) {
            String[] f = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8).split(";", -1);
            this.uuid = f[0];
            this.ownerId = f[1];
            this.authorities = f[2].isEmpty() ? List.of() : List.of(f[2].split(","));
            this.expiresAt = Instant.ofEpochMilli(Long.parseLong(f[3]));
            this.refreshExpiresAt = Instant.ofEpochMilli(Long.parseLong(f[4]));
            this.revoked = false;
            this.refreshRevoked = false;
        }
    }

    public static class SessionDto {
        private String id;
        private String uuid;
        private String ownerId;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private Instant refreshExpiresAt;
        private Boolean refreshRevoked;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
        public void setRefreshExpiresAt(Instant v) { this.refreshExpiresAt = v; }
        public Boolean getRefreshRevoked() { return refreshRevoked; }
        public void setRefreshRevoked(Boolean v) { this.refreshRevoked = v; }
    }
}
