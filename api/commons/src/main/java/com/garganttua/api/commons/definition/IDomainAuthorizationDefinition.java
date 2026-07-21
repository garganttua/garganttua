package com.garganttua.api.commons.definition;

import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;

public interface IDomainAuthorizationDefinition {

	/**
	 * Custom caller-reconciliation binder, declared via
	 * {@code .security().authorization().reconcile(supplier, "method")}. When present,
	 * the verify pipeline calls this user method ({@code ICaller method(IAuthentication, ICaller)})
	 * instead of the default {@link com.garganttua.api.commons.security.authentication.IAuthentication#reconcile}
	 * — enabling fully custom, self-contained caller resolution. {@code null} when not declared.
	 */
	default IMethodBinder<?> reconcileBinder() {
		return null;
	}

	ObjectAddress type();

	ObjectAddress authorities();

	ObjectAddress expiration();

	ObjectAddress creation();

	ObjectAddress revoked();

	boolean storable();

	boolean signable();

	boolean refreshable();

	/**
	 * Opt-in stateful revocation: when {@code true} (and the domain is
	 * {@link #storable()}), the verify path fetches the server-authoritative
	 * stored record BEFORE the intrinsic checks, fails closed (→ 401) when it is
	 * absent, and runs the intrinsic {@code revoked} / {@code expiration} checks
	 * against that stored record's CURRENT state — not only the decoded token's
	 * frozen claims. This makes a {@code revoked=true} (or a deleted row) reject
	 * an already-issued bearer immediately, instead of waiting for expiration.
	 *
	 * <p>Default {@code false}: verification stays stateless (checks the signed
	 * claims only), behaviour strictly unchanged. Declared via
	 * {@code .authorization().checkStoredOnVerify(true)}.
	 *
	 * @return whether the verify path re-validates against the stored record
	 */
	default boolean checkStoredOnVerify() {
		return false;
	}

	ObjectAddress signatureField();

	ObjectAddress getDataToSignMethod();

	ObjectAddress refreshExpiration();

	ObjectAddress refreshRevoked();

	/**
	 * Method on the authorization entity that produces a transport-friendly
	 * encoded form (e.g. JWT compact serialization). Declared via
	 * {@code .refreshable().encode(method)}. {@code null} when not configured.
	 */
	ObjectAddress encodeMethod();

	/**
	 * Method that decodes a transport-friendly encoded authorization back into
	 * a typed entity. Declared via {@code .refreshable().decode(method)}.
	 * {@code null} when not configured.
	 */
	ObjectAddress decodeMethod();

	/**
	 * Field on the authorization entity recording who signed it, stamped at
	 * signing time with the qualified key-realm id ({@code ${domainName}:${id}}).
	 * {@code null} when not configured.
	 */
	ObjectAddress signedBy();

}
