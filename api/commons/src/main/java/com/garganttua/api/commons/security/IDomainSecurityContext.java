package com.garganttua.api.commons.security;

import com.garganttua.api.commons.definition.IDomainSecurityDefinition;

// Nominal role contract (security-context accessor), not a lambda target; may gain methods.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IDomainSecurityContext {

    IDomainSecurityDefinition getDomainSecurityDefinition();
}
