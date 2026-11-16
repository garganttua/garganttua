package com.garganttua.api.commons.context.dsl.security;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IAuthenticatorConnection;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.core.dsl.IAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Declares a named connection of an authenticator ({@code .authenticator().connection("psc")}): a second login field
 * — a professional number, an external identity — searched within its own scope and tried by its own strategies.
 * {@code up()} returns to the authenticator.
 *
 * <pre>{@code
 * .authenticator()
 *     .login("id").authentication(password)                // the default connection
 *     .connection("psc")
 *         .login("rpps").scope(AuthenticatorScope.tenant)  // unique within a tenant only
 *         .authentication(proSanteConnect)
 *     .up()
 * }</pre>
 */
public interface IAuthenticatorConnectionBuilder<E>
        extends IAutomaticLinkedBuilder<IAuthenticatorConnectionBuilder<E>, IAuthenticatorBuilder<E>, IAuthenticatorConnection> {

    IAuthenticatorConnectionBuilder<E> login(String fieldName) throws ApiException;

    IAuthenticatorConnectionBuilder<E> login(IField field) throws ApiException;

    IAuthenticatorConnectionBuilder<E> login(ObjectAddress fieldAddress) throws ApiException;

    /** The scope of the search by this connection's login; the authenticator's when not set. */
    IAuthenticatorConnectionBuilder<E> scope(AuthenticatorScope scope);

    /** Adds a strategy tried for a request through this connection; at least one is required. */
    IAuthenticatorConnectionBuilder<E> authentication(IAuthenticationBuilder<?> authentication) throws ApiException;
}
