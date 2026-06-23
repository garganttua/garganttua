package com.garganttua.api.commons.security.context;

import com.garganttua.api.commons.definition.IDomainAuthenticatorAuthorizationKeyDefinition;

// Nominal role contract (security-context accessor), not a lambda target; may gain methods.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IAuthenticatorAuthorizationKeyContext {

    IDomainAuthenticatorAuthorizationKeyDefinition getAuthenticatorAuthorizationKeyDefinition();

}
