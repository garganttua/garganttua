package com.garganttua.api.core.security.authenticator;

import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorConnection;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * A built named connection of an authenticator.
 *
 * @param name                      the connection's name, its route segment
 * @param login                     the login field of the connection
 * @param scopeOverride             the scope of its search, {@code null} to inherit the authenticator's
 * @param authenticationDefinitions its strategies, in order
 */
@SuppressFBWarnings(value = { "EI_EXPOSE_REP" }, justification = "Immutable-by-contract definition carrier, built once.")
public record AuthenticatorConnection(String name, ObjectAddress login, AuthenticatorScope scopeOverride,
        List<IAuthenticationDefinition> authenticationDefinitions) implements IAuthenticatorConnection {

    @Override
    public Optional<AuthenticatorScope> scope() {
        return Optional.ofNullable(this.scopeOverride);
    }
}
