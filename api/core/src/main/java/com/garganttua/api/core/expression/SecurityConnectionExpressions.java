package com.garganttua.api.core.expression;

import static com.garganttua.api.core.expression.ExpressionUtils.unwrapOptional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.core.security.authenticator.ConnectionAuthenticatorDefinition;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.annotations.Reflected;

import jakarta.annotation.Nullable;

/**
 * The authenticator connection a login goes through (AUTHENTICATE): {@code .connection(name)} declares a second
 * login field with its own scope and strategies, and a request names it ({@code /authenticate/<name>}).
 */
@Reflected
public final class SecurityConnectionExpressions {

	private SecurityConnectionExpressions() {
		// Expression function holder
	}

	/**
	 * The authenticator as the request reaches it: through the connection the request names, or as is.
	 *
	 * @param authContext the authenticator's definition
	 * @param request     the operation request, read for {@code connection}
	 * @return the definition the login path uses — login field, scope, strategies of the connection
	 * @throws ApiException when the request names a connection the authenticator does not declare
	 */
	@Expression(name = "authenticatorConnection", description = "AUTHENTICATE: the authenticator seen through the "
			+ "connection the request names (its login field, scope and strategies), or as is when it names none. "
			+ "Fails on an unknown connection.")
	public static IAuthenticatorDefinition authenticatorConnection(@Nullable Object authContext,
			@Nullable Object request) {
		if (!(unwrapOptional(authContext) instanceof IAuthenticatorDefinition authenticator)) {
			throw new ApiException("authenticatorConnection: no authenticator definition");
		}
		return ConnectionAuthenticatorDefinition.forRequest(authenticator, unwrapOptional(request));
	}
}
