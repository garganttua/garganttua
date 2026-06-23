package com.garganttua.api.core.expression;

import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Authorization-verification helpers split out of {@link SecurityAuthenticationExpressions}: resolves
 * the server-authoritative authorization record, the owner principal behind a verified token, the
 * registered domain of an entity, and runs the DSL-driven intrinsic (revoked / expired) validation.
 * Extracted to keep that expression registry under the file-size gate; behaviour is identical.
 */
final class SecurityVerificationSupport {

	private static final Logger log = Logger.getLogger(SecurityVerificationSupport.class);

	private SecurityVerificationSupport() {
	}

	static Object serverAuthoritativeAuthorization(IDomain<?> authzDomain, Object authz) {
		Object defObj = SecurityExpressions.authorizationDefinition(authzDomain);
		if (!(defObj instanceof IDomainAuthorizationDefinition d) || !d.storable()) {
			return authz;
		}
		ObjectAddress uuidAddr = authzDomain.getEntityDefinition() != null
				? authzDomain.getEntityDefinition().uuid() : null;
		if (uuidAddr == null) {
			return authz;
		}
		String uuid = SecurityExpressions.readField(authz, uuidAddr);
		if (uuid == null) {
			return authz;
		}
		try {
			IFilter filter = Filter.eq(uuidAddr.toString(), uuid);
			List<Object> results = authzDomain.getRepository()
					.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
			if (results != null && !results.isEmpty()) {
				return results.get(0);
			}
		} catch (RuntimeException e) {
			// The authenticate step already proved the token exists; on a lookup hiccup
			// fall back to the decoded (signature-verified) payload rather than failing open.
			log.trace("serverAuthoritativeAuthorization: lookup hiccup, using decoded payload: {}", e.getMessage());
		}
		return authz;
	}

	/** Resolves the registered domain whose entity class matches the runtime class of {@code entity}. Null when none. */
	static IDomain<?> domainOfEntity(IApi api, Object entity) {
		if (entity == null || !(api instanceof com.garganttua.api.core.api.Api concrete)) {
			return null;
		}
		IClass<?> cls = IClass.getClass(entity.getClass());
		for (IDomain<?> d : concrete.getDomains().values()) {
			if (cls.equals(d.getEntityClass())) {
				return d;
			}
		}
		return null;
	}

	/**
	 * Resolves the owner principal for a verified token. The token's {@code owned}
	 * field carries a qualified id {@code ${ownerDomain}:${uuid}}; we split it to
	 * find the owner domain and look the principal up by its bare uuid.
	 */
	static Object resolveOwnerPrincipal(IApi api, IDomain<?> authzDomain, Object authz) {
		ObjectAddress ownedAddr = authzDomain.getDomainDefinition().owned();
		if (ownedAddr == null) {
			throw new ApiException("verifyAuthorization: authorization domain '" + authzDomain.getDomainName()
					+ "' is not owned — cannot resolve the owner principal. Declare .owned(field).");
		}
		String qualified = SecurityExpressions.readField(authz, ownedAddr);
		if (qualified == null) {
			throw new ApiException("verifyAuthorization: authorization has no ownerId set");
		}
		String ownerDomainName = OwnerIds.domainOf(qualified);
		String ownerUuid = OwnerIds.idOf(qualified);
		IDomain<?> ownerDomain = ownerDomainName != null ? api.getDomain(ownerDomainName).orElse(null) : null;
		if (ownerDomain == null) {
			throw new ApiException("verifyAuthorization: owner domain '" + ownerDomainName
					+ "' (from ownerId '" + qualified + "') is not registered");
		}
		ObjectAddress uuidField = ownerDomain.getEntityDefinition() != null
				? ownerDomain.getEntityDefinition().uuid() : null;
		if (uuidField == null) {
			throw new ApiException("verifyAuthorization: owner domain '" + ownerDomain.getDomainName()
					+ "' has no uuid field");
		}
		IFilter filter = Filter.eq(uuidField.toString(), ownerUuid);
		List<Object> results = ownerDomain.getRepository()
				.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
		if (results == null || results.isEmpty()) {
			throw new ApiException("verifyAuthorization: owner not found for ownerId '" + qualified + "'");
		}
		return results.get(0);
	}

	/**
	 * DSL-driven intrinsic validation. Reads the {@code revoked} and
	 * {@code expiration} ObjectAddresses from
	 * {@link IDomainAuthorizationDefinition} and verifies the entity's fields
	 * against them. Either check raises a parlant {@link ApiException} so the
	 * `! => recordCaughtException(@0, @exception) -> 401` pattern surfaces the
	 * exact message on the OperationResponse.
	 */
	static void validateAuthorizationFromDefinition(Object authzEntity, IDomain<?> targetDomain) {
		Object defObj = SecurityExpressions.authorizationDefinition(targetDomain);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef)) {
			return;
		}
		IReflection reflection = DefaultMapper.reflection();

		ObjectAddress revokedAddr = authzDef.revoked();
		if (revokedAddr != null) {
			Object value = reflection.getFieldValue(authzEntity, revokedAddr.toString());
			if (value instanceof Boolean b && b) {
				throw new ApiException("Authorization revoked");
			}
		}

		ObjectAddress expirationAddr = authzDef.expiration();
		if (expirationAddr != null) {
			Object value = reflection.getFieldValue(authzEntity, expirationAddr.toString());
			if (value != null && SecuritySigningExpressions.isExpired(value)) {
				throw new ApiException("Authorization expired");
			}
		}
	}
}
