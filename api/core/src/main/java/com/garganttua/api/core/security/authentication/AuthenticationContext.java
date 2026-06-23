package com.garganttua.api.core.security.authentication;

import com.garganttua.api.core.SuppressFBWarnings;

import java.util.Objects;

import com.garganttua.api.core.security.authentication.AuthenticationDefinition;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.security.context.IAuthenticationContext;


@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public class AuthenticationContext implements IAuthenticationContext {

    private AuthenticationDefinition authenticationDefinition;
    private IDomain<?> domainContext;

    public AuthenticationContext(AuthenticationDefinition definition) {
        this.authenticationDefinition = Objects.requireNonNull(definition, "Authentication definition is mandatory to create an authentication context");
    }

    public void setDomain(IDomain<?> domainContext) {
        this.domainContext = domainContext;
    }

    public IDomain<?> getDomain() {
        return this.domainContext;
    }

    @Override
    public IAuthenticationDefinition getAuthenticationDefinition() {
        return this.authenticationDefinition;
    }

}
