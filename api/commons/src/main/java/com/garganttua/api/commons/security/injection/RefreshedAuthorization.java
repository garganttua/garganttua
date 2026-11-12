package com.garganttua.api.commons.security.injection;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.garganttua.core.reflection.annotations.Indexed;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Declarative injection marker for a parameter of a custom issuer — the annotation dual of
 * {@code .withParam(i, new RefreshedAuthorizationSupplierBuilder(), true)}: the authorization a refresh is
 * replacing, so that the issuer can carry over what it put in it at login. {@code null} at login, so the parameter is {@code @Nullable} too. Resolved by
 * {@code RefreshedAuthorizationElementResolver}.
 */
@Indexed
@Reflected
@Target({ ElementType.METHOD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface RefreshedAuthorization {
}
