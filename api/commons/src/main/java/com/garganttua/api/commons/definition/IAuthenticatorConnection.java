package com.garganttua.api.commons.definition;

import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * A named way into an authenticator, besides its default one: its own login field, searched within its own scope,
 * and its own authentication strategies. Everything else — the account state, the authorities, the authorization
 * issued, its key, its issuer, its refresh — is the authenticator's, so a token issued through a connection is the
 * domain's like any other: same owner {@code <domain>:<uuid>}.
 *
 * <p>
 * A request names the connection it uses ({@code POST /<domain>/authenticate/<name>}); without one, the default
 * applies, exactly as an authenticator without connections.
 * </p>
 */
public interface IAuthenticatorConnection {

    /** {@return the connection's name, unique on its authenticator; the last segment of its route} */
    String name();

    /** {@return the field a request's login is matched against} */
    ObjectAddress login();

    /** {@return the scope of the search, when it differs from the authenticator's} */
    Optional<AuthenticatorScope> scope();

    /** {@return the strategies tried, in order, for a request through this connection} */
    List<IAuthenticationDefinition> authenticationDefinitions();
}
