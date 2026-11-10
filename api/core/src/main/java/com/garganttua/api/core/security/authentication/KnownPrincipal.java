package com.garganttua.api.core.security.authentication;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A principal that the caller of an authenticator already knows how to find, handed to
 * {@link PrincipalSupplier} on the request so that the supplier does not look it up itself.
 *
 * <p>
 * The token verification uses it. The principal of a self-verifying token is the token's own stored
 * record, which the verification reads anyway to check revocation. Without this, the supplier would
 * query the domain again, through a nested {@code readAll} pipeline, at every request.
 * </p>
 *
 * <p>
 * Only server code builds one: it is an object, and no transport can put an object on a request. A
 * request arriving with a string under {@link #ARG} is read like any other.
 * </p>
 *
 * @param lookup finds the principal, empty when there is none; called at most once per strategy
 */
public record KnownPrincipal(Supplier<Optional<Object>> lookup) {

	/** The request arg that carries it. */
	public static final String ARG = "knownPrincipal";
}
