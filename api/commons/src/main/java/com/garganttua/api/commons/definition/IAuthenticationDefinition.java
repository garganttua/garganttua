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

    /**
     * The method that re-derives the authentication of an already known principal when its authorization is
     * refreshed, declared via {@code .authentication(...).refresh(method)}: the login's dual without the
     * credentials, so that a refreshed token carries the authorities of the moment rather than those of the
     * token it replaces. {@code null} when not declared.
     */
    default IMethodBinder<?> refreshMethodBinder() {
        return null;
    }

}
