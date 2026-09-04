package com.garganttua.api.core.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.core.security.authenticator.AuthenticatorDefintion;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Covers {@code .authenticator().authorities("field")}, reported by a consumer as decorative: the
 * DSL reads as "take the authorities from this field of the authenticator entity", and nothing
 * anywhere read the declaration. A consumer whose strategy did not fill the list got tokens with no
 * authority at all, with no error and no warning.
 */
@DisplayName("Authenticator .authorities(field)")
class AuthenticatorAuthoritiesTest {

    /** The authenticator entity — the principal an authentication resolves to. */
    public static class Account {
        private List<String> roles;

        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }

    /** Minimal authentication result: only the pieces resolveAuthorities reads. */
    private record Auth(Object principal, List<String> authorities) implements IAuthentication {
        @Override public boolean authenticated() { return true; }
        @Override public Object credentials() { return null; }
        @Override public Object authorization() { return null; }
        @Override public String tenantId() { return null; }
        @Override public String ownerId() { return null; }
        @Override public boolean isSuperTenant() { return false; }
        @Override public boolean isSuperOwner() { return false; }
        @Override public boolean credentialsNonExpired() { return true; }
        @Override public boolean enabled() { return true; }
        @Override public boolean accountNonLocked() { return true; }
        @Override public boolean accountNonExpired() { return true; }
    }

    private static IAuthenticatorDefinition authenticatorDeclaring(String authoritiesField) {
        return new AuthenticatorDefintion(false, null,
                authoritiesField == null ? null : new ObjectAddress(authoritiesField),
                null, null, null, null, null, Map.of(), List.of(), null, null);
    }

    private static IReflection reflection() {
        return ReflectionBuilder.builder().withProvider(new RuntimeReflectionProvider()).build();
    }

    private static Account accountWith(List<String> roles) {
        Account account = new Account();
        account.setRoles(roles);
        return account;
    }

    @Test
    @DisplayName("the declared field is read when the strategy resolved no authorities")
    void declaredFieldIsTheFallback() {
        List<String> resolved = SecurityExpressionsSupport.resolveAuthorities(
                authenticatorDeclaring("roles"),
                new Auth(accountWith(List.of("admin", "auditor")), null),
                reflection());

        assertEquals(List.of("admin", "auditor"), resolved,
                "the declaration must be honoured — this is what used to be read by no one");
    }

    @Test
    @DisplayName("the strategy's own list wins over the declared field")
    void strategyWins() {
        List<String> resolved = SecurityExpressionsSupport.resolveAuthorities(
                authenticatorDeclaring("roles"),
                new Auth(accountWith(List.of("from-entity")), List.of("from-strategy")),
                reflection());

        assertEquals(List.of("from-strategy"), resolved);
    }

    @Test
    @DisplayName("an EMPTY list from the strategy is authoritative — it grants nothing, deliberately")
    void emptyStrategyListIsAuthoritative() {
        List<String> resolved = SecurityExpressionsSupport.resolveAuthorities(
                authenticatorDeclaring("roles"),
                new Auth(accountWith(List.of("admin")), List.of()),
                reflection());

        assertEquals(List.of(), resolved,
                "only a null means 'not resolved'; an empty list must not be silently upgraded");
    }

    @Test
    @DisplayName("no declaration and no strategy list resolves to nothing, as before")
    void nothingDeclaredNothingResolved() {
        assertNull(SecurityExpressionsSupport.resolveAuthorities(
                authenticatorDeclaring(null),
                new Auth(accountWith(List.of("admin")), null),
                reflection()));
    }

    @Test
    @DisplayName("a declared field that cannot be read fails with a message naming the declaration")
    void unreadableFieldIsReported() {
        ApiException e = assertThrows(ApiException.class, () ->
                SecurityExpressionsSupport.resolveAuthorities(
                        authenticatorDeclaring("noSuchField"),
                        new Auth(accountWith(List.of("admin")), null),
                        reflection()));

        assertTrue(e.getMessage().contains("noSuchField"),
                () -> "the message must name the declaration; was: " + e.getMessage());
    }
}
