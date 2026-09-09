package com.garganttua.api.commons.context.dsl.security;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IDomainKeyBuilder;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.security.IDomainSecurityContext;
import com.garganttua.core.dsl.IAutomaticLinkedBuilder;

public interface IDomainSecurityBuilder<E>
		extends IAutomaticLinkedBuilder<IDomainSecurityBuilder<E>, IDomainBuilder<E>, IDomainSecurityContext> {

	IAuthorizationBuilder<E> authorization();

	IDomainSecurityBuilder<E> disable(boolean b);

	IAuthenticatorBuilder<E> authenticator();

	/**
	 * Marks this domain as a key domain — its entity holds cryptographic key
	 * material and the framework will use it as the storage backend when an
	 * authenticator's authorization declares {@code .key(IDomainBuilder)} for
	 * auto-create / lookup. {@code .up()} returns this security builder.
	 */
	IDomainKeyBuilder<E> key() throws ApiException;

	// Per-use-case security is configured via IUseCaseBuilder.security() — see
	// IUseCaseSecurityBuilder. There is intentionally no useCase(...) method
	// here: declaring it twice would let two different paths set the same
	// state with no merge rule.

	// --- CRUD access level ---

	IDomainSecurityBuilder<E> creationAccess(Access access);

	IDomainSecurityBuilder<E> readAllAccess(Access access);

	IDomainSecurityBuilder<E> readOneAccess(Access access);

	/**
	 * Access level of {@code GET /<domain>/self} — the caller reading its own entity.
	 *
	 * <p>
	 * Only mounted on a domain declaring an {@code .authenticator()}: elsewhere "self" designates
	 * nothing. Defaults to {@code authenticated} WITH an authority required, so the route answers
	 * {@code 403} until one is granted — reading one's own profile is a deliberate grant, not
	 * something a version bump should open.
	 * </p>
	 *
	 * <p>
	 * <strong>What it serves:</strong> the entity <em>as it is</em> — like every CRUD read, the
	 * business stage hands the entity itself to the serializer; there is no outbound DTO projection
	 * that would drop fields. On an authenticator domain that entity is precisely the one holding
	 * the credential: the hashed password field, and any {@code @AuthenticatorRefreshToken} /
	 * {@code @AuthenticatorAuthorities} field. Granting this authority therefore publishes them to
	 * the account's own holder.
	 * </p>
	 *
	 * <p>
	 * That is a deliberate choice — "self" reads the entity, and the framework does not guess which
	 * of its fields the application considers secret. Two ways to narrow it, both already there:
	 * a {@code projection} on the request ({@code ?select=...}), or an {@code afterGet} hook that
	 * blanks the fields before they leave. Decide before granting, not after.
	 * </p>
	 */
	IDomainSecurityBuilder<E> readSelfAccess(Access access);

	IDomainSecurityBuilder<E> updateAccess(Access access);

	IDomainSecurityBuilder<E> deleteOneAccess(Access access);

	IDomainSecurityBuilder<E> deleteAllAccess(Access access);

	// --- CRUD authority (boolean: auto-generated authority name) ---

	IDomainSecurityBuilder<E> creationAuthority(boolean authority);

	IDomainSecurityBuilder<E> readAllAuthority(boolean authority);

	IDomainSecurityBuilder<E> readOneAuthority(boolean authority);

	/** @see #readSelfAccess(Access) */
	IDomainSecurityBuilder<E> readSelfAuthority(boolean authority);

	IDomainSecurityBuilder<E> updateAuthority(boolean authority);

	IDomainSecurityBuilder<E> deleteOneAuthority(boolean authority);

	IDomainSecurityBuilder<E> deleteAllAuthority(boolean authority);

	// --- CRUD authority (String: custom authority name) ---

	IDomainSecurityBuilder<E> creationAuthority(String authority);

	IDomainSecurityBuilder<E> readAllAuthority(String authority);

	IDomainSecurityBuilder<E> readOneAuthority(String authority);

	/** @see #readSelfAccess(Access) */
	IDomainSecurityBuilder<E> readSelfAuthority(String authority);

	IDomainSecurityBuilder<E> updateAuthority(String authority);

	IDomainSecurityBuilder<E> deleteOneAuthority(String authority);

	IDomainSecurityBuilder<E> deleteAllAuthority(String authority);

}
