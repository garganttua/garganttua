package com.garganttua.api.commons.security.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.core.reflection.annotations.Indexed;

/**
 * Marks a login field of an authenticator entity — the annotation dual of {@code .login(field)}, or, with a
 * {@link #connection()} name, of {@code .connection(name).login(field)}. Several fields may carry it, one per
 * connection.
 */
@Indexed
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuthenticatorLogin {

	/** The connection this field is the login of; empty for the authenticator's default login. */
	String connection() default "";

	/** The scope of the search by this field, for a named connection; empty: the authenticator's. */
	AuthenticatorScope[] scope() default {};

	/**
	 * The {@code @Authentication} strategies of this login: those of the named connection, or, on the default
	 * login, strategies added to the authenticator's.
	 */
	Class<?>[] authentications() default {};
}
