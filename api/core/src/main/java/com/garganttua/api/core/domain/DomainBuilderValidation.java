package com.garganttua.api.core.domain;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.core.security.DomainSecurityBuilder;
import com.garganttua.api.core.security.authenticator.AuthenticatorBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.query.ObjectQueryFactory;

/**
 * Build-time structural validations for {@link DomainBuilder} (DTO presence, security-role
 * consistency, super-status field presence), with the developer-facing fix-up messages. Extracted
 * from {@code DomainBuilder} to keep that wide-interface builder under the file-size gate.
 */
final class DomainBuilderValidation {

	private static final String DOMAIN_PREFIX = "Domain '";

	private DomainBuilderValidation() {
	}

	/**
	 * Validates that the entity class is reflectable via the supplied provider, rethrowing any
	 * {@link ReflectionException} as an {@link ApiException}. The query result is intentionally
	 * discarded — this is a fail-fast validation only.
	 */
	static void validateReflectable(IClass<?> entityClass, IReflectionProvider provider)
			throws ApiException {
		try {
			ObjectQueryFactory.objectQuery(entityClass, provider);
		} catch (ReflectionException e) {
			throw new ApiException(e.getMessage(), e);
		}
	}

	static void requireDto(boolean hasDto, String domainName, IClass<?> entityClass) {
		if (hasDto) {
			return;
		}
		String entityHint = entityClass != null ? entityClass.getSimpleName() : "MyEntity";
		throw new ApiException("No dto declared for domain '" + domainName + "'. "
				+ "Each domain needs at least one DTO (the persisted shape — fields, id/uuid/tenantId mapping, DAO). Example:\n"
				+ "\n"
				+ "    apiBuilder.domain(" + entityHint + ".class)\n"
				+ "        .entity().id(\"id\").uuid(\"uuid\").tenantId(\"tenantId\").up()\n"
				+ "        .dto(" + entityHint + "Dto.class)             // <- missing\n"
				+ "            .id(\"id\").uuid(\"uuid\").tenantId(\"tenantId\")\n"
				+ "            .db(new MyDao())\n"
				+ "        .up()");
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static void validateSecurityRoles(Object securityBuilder, String domainName,
			ObjectAddress owned, ObjectAddress owner) {
		if (securityBuilder == null) {
			return;
		}
		DomainSecurityBuilder<?> secBuilder = (DomainSecurityBuilder<?>) securityBuilder;

		// Rule 1: A domain with authorization role MUST be owned.
		if (secBuilder.hasAuthorization() && owned == null) {
			throw new ApiException(DOMAIN_PREFIX + domainName
					+ "' has an authorization configuration but is not owned. "
					+ "An authorization entity always belongs to a principal — use .owned(field) on the domain builder.");
		}

		// Rule 2: An authenticator domain that produces an authorization MUST be owner.
		if (secBuilder.hasAuthenticator()) {
			var authenticatorBuilder = (AuthenticatorBuilder) secBuilder.getAuthenticator();
			if (authenticatorBuilder.hasAuthorizationConfig() && owner == null) {
				throw new ApiException(DOMAIN_PREFIX + domainName
						+ "' is an authenticator that produces authorizations but is not an owner. "
						+ "The authenticator entity must own the authorization entities — use .owner(field) on the domain builder.");
			}
		}
	}

	/**
	 * Throws with a concrete recipe when a domain-level method is called before {@code .entity(...)}
	 * has produced an entity builder.
	 */
	static void requireEntityDeclared(IEntityBuilder<?> entityBuilder, IClass<?> entityClass,
			String domainName) throws ApiException {
		if (entityBuilder == null) {
			String entityHint = entityClass != null ? entityClass.getSimpleName() : "MyEntity";
			throw new ApiException(
					"This call requires .entity() to be declared first on domain '"
					+ domainName + "'. Example:\n"
					+ "\n"
					+ "    apiBuilder.domain(" + entityHint + ".class)\n"
					+ "        .entity().id(\"id\").uuid(\"uuid\").tenantId(\"tenantId\").up()\n"
					+ "        // <- now field-resolving calls like .owner/.owned/.shared/.hiddenable/.geolocalized work\n"
					+ "\n"
					+ "(The stack trace shows which specific method was called.)");
		}
	}

	/**
	 * Throws with a concrete recipe when a method needs the entity class to be known but the domain
	 * was created from a name only (no {@code IClass}).
	 */
	static void requireEntityClassSet(IClass<?> entityClass, String domainName, String calledMethod)
			throws ApiException {
		if (entityClass == null) {
			throw new ApiException(
					"." + calledMethod + "() needs an entity class on domain '"
					+ domainName + "', but this domain was created from a name only. "
					+ "Use apiBuilder.domain(MyEntity.class) instead of the name-only overload, "
					+ "or call .entity(MyEntity.class) on this domain before ." + calledMethod + "().");
		}
	}

	static void validateSuperFields(String domainName, boolean tenant, ObjectAddress superTenant,
			ObjectAddress owner, ObjectAddress superOwner) {
		if (tenant && superTenant == null) {
			throw new ApiException(DOMAIN_PREFIX + domainName + "' is the tenant (.tenant(true) / @EntityTenant) "
					+ "but declares no superTenant field. A tenant entity must carry a boolean superTenant field so "
					+ "the framework can identify super-tenants server-side — declare it via .superTenant(field) on the "
					+ "domain builder, or annotate the boolean field with @EntitySuperTenant.");
		}
		if (owner != null && superOwner == null) {
			throw new ApiException(DOMAIN_PREFIX + domainName + "' is an owner (.owner(field) / @EntityOwner) "
					+ "but declares no superOwner field. An owner entity must carry a boolean superOwner field so "
					+ "the framework can identify super-owners server-side — declare it via .superOwner(field) on the "
					+ "domain builder, or annotate the boolean field with @EntitySuperOwner.");
		}
	}
}
