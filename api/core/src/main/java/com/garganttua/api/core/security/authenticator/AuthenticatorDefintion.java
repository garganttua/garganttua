package com.garganttua.api.core.security.authenticator;

import com.garganttua.api.core.SuppressFBWarnings;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthenticatorAuthorizationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public record AuthenticatorDefintion(
        boolean alwaysEnabled,
        ObjectAddress login,
        ObjectAddress authorities,
        ObjectAddress credentialsNonExpired,
        ObjectAddress enabled,
        ObjectAddress accountNonLocked,
        ObjectAddress accountNonExpired,
        AuthenticatorScope scope,
        Map<Annotation, ObjectAddress> requiredAuthenticationFields,
        List<IAuthenticationDefinition> authenticationDefinitions,
        IDomainAuthenticatorAuthorizationDefinition authorizationDefinition,
        IMethodBinder<?> authorizationMethodBinder) implements IAuthenticatorDefinition {

}
