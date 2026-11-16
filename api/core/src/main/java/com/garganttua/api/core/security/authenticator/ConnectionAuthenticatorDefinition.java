package com.garganttua.api.core.security.authenticator;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorConnection;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthenticatorAuthorizationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;

/**
 * An authenticator seen through one of its named connections: the connection's login field, scope and strategies,
 * and everything else — account state, authorities, authorization, issuer — the authenticator's. Handed to the login
 * path in place of the authenticator, so the search, the scope check and the strategy cascade need not know that
 * connections exist.
 */
public final class ConnectionAuthenticatorDefinition implements IAuthenticatorDefinition {

    private final IAuthenticatorDefinition authenticator;
    private final IAuthenticatorConnection connection;

    private ConnectionAuthenticatorDefinition(IAuthenticatorDefinition authenticator,
            IAuthenticatorConnection connection) {
        this.authenticator = authenticator;
        this.connection = connection;
    }

    /**
     * The authenticator as the request reaches it: through the connection it names, or as is when it names none.
     *
     * @throws ApiException when the request names a connection the authenticator does not declare
     */
    public static IAuthenticatorDefinition forRequest(IAuthenticatorDefinition authenticator, Object request) {
        String name = request instanceof IOperationRequest operationRequest
                ? operationRequest.arg(IOperationRequest.CONNECTION).orElse(null) : null;
        return forConnection(authenticator, name);
    }

    /**
     * The authenticator through the connection {@code name}, or as is when {@code name} is blank.
     *
     * @throws ApiException when the authenticator declares no such connection
     */
    public static IAuthenticatorDefinition forConnection(IAuthenticatorDefinition authenticator, String name) {
        Objects.requireNonNull(authenticator, "Authenticator cannot be null");
        if (name == null || name.isBlank() || authenticator instanceof ConnectionAuthenticatorDefinition) {
            return authenticator;
        }
        return authenticator.connections().stream()
                .filter(connection -> connection.name().equals(name))
                .findFirst()
                .<IAuthenticatorDefinition>map(connection -> new ConnectionAuthenticatorDefinition(authenticator,
                        connection))
                .orElseThrow(() -> new ApiException("Unknown connection '" + name + "' on this authenticator"));
    }

    /** {@return the name of the connection this view goes through} */
    public String connectionName() {
        return this.connection.name();
    }

    @Override
    public ObjectAddress login() {
        return this.connection.login();
    }

    @Override
    public AuthenticatorScope scope() {
        return this.connection.scope().orElse(this.authenticator.scope());
    }

    @Override
    public List<IAuthenticationDefinition> authenticationDefinitions() {
        return this.connection.authenticationDefinitions();
    }

    @Override
    public List<IAuthenticatorConnection> connections() {
        return this.authenticator.connections();
    }

    @Override
    public boolean alwaysEnabled() {
        return this.authenticator.alwaysEnabled();
    }

    @Override
    public ObjectAddress authorities() {
        return this.authenticator.authorities();
    }

    @Override
    public ObjectAddress credentialsNonExpired() {
        return this.authenticator.credentialsNonExpired();
    }

    @Override
    public ObjectAddress enabled() {
        return this.authenticator.enabled();
    }

    @Override
    public ObjectAddress accountNonLocked() {
        return this.authenticator.accountNonLocked();
    }

    @Override
    public ObjectAddress accountNonExpired() {
        return this.authenticator.accountNonExpired();
    }

    @Override
    public Map<Annotation, ObjectAddress> requiredAuthenticationFields() {
        return this.authenticator.requiredAuthenticationFields();
    }

    @Override
    public IDomainAuthenticatorAuthorizationDefinition authorizationDefinition() {
        return this.authenticator.authorizationDefinition();
    }

    @Override
    public IMethodBinder<?> authorizationMethodBinder() {
        return this.authenticator.authorizationMethodBinder();
    }
}
