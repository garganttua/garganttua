package com.garganttua.api.commons.security.context;

import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;

// Nominal role contract (security-context accessor), not a lambda target; may gain methods.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IAuthorizationContext {

    IDomainAuthorizationDefinition getAuthorizationDefinition();

}
