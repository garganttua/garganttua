package com.garganttua.api.commons.definition;

import com.garganttua.core.reflection.binders.IMethodBinder;

// Nominal definition contract (already carries a default method); not a lambda target.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IAuthenticationDefinition {

    IMethodBinder<?> authenticateMethodBinder();

    /**
     * Custom method binder run on CREATE/UPDATE of the authenticator entity to apply
     * security on it (e.g. hash a password), declared via
     * {@code .authentication(...).applySecurityOnEntity(method)}. {@code null} when not declared.
     */
    default IMethodBinder<?> applySecurityOnEntityMethodBinder() {
        return null;
    }

}
