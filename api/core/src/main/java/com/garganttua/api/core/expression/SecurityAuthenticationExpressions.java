package com.garganttua.api.core.expression;
import com.garganttua.core.reflection.annotations.Reflected;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authentication.IAuthenticationRequest;
import com.garganttua.api.commons.security.authorization.IAuthorizationProtocol;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IContextualMethodBinder;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.reflection.IMethodReturn;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.runtime.RuntimeExpressionContext;

import jakarta.annotation.Nullable;

import static com.garganttua.api.core.expression.ExpressionUtils.*;

/**
 * Authentication-side security expressions split out of {@link SecurityExpressions}: principal
 * lookup, synthesizing an IAuthentication from a stored principal, building the authentication
 * response, login/account-status checks, the authenticate dispatch and authorization verification.
 * Kept in the {@code com.garganttua.api.core.expression} package so the {@code @Expression} methods
 * remain discovered by the package scan; behaviour is identical to the original.
 */
@Reflected(queryAllPublicMethods = true)
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals", "PMD.NullAssignment"})
public class SecurityAuthenticationExpressions {

	private static final com.garganttua.core.observability.Logger log =
			com.garganttua.core.observability.Logger.getLogger(SecurityAuthenticationExpressions.class);

	private SecurityAuthenticationExpressions() {
	}

	@Expression(name = "findPrincipalByOwnerUuid", description = "Looks up the principal entity in the authenticator domain's repository using the ownerId stored on an existing authorization. Returns the entity or throws if absent.")
	public static Object findPrincipalByOwnerUuid(@Nullable Object authzEntity, @Nullable Object authenticatorDomain, @Nullable Object repositoryObj) {
		if (authzEntity == null || authenticatorDomain == null || repositoryObj == null) {
			throw new ApiException("findPrincipalByOwnerUuid: entity, authenticatorDomain and repository are required");
		}
		IDomain<?> domain = toDomain(authenticatorDomain);
		IRepository repo = (IRepository) repositoryObj;

		IDomain<?> authzDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(domain);
		if (authzDomain == null) {
			throw new ApiException("findPrincipalByOwnerUuid: no authorization domain linked to '"
					+ (domain != null ? domain.getDomainName() : "<null>") + "'");
		}
		ObjectAddress ownedField = authzDomain.getDomainDefinition().owned();
		if (ownedField == null) {
			throw new ApiException("findPrincipalByOwnerUuid: authorization domain '"
					+ authzDomain.getDomainName() + "' is not owned");
		}
		Object ownerUuid = readOwnerUuid(authzEntity, ownedField);
		ObjectAddress uuidField = domain.getEntityDefinition() != null ? domain.getEntityDefinition().uuid() : null;
		if (uuidField == null) {
			throw new ApiException("findPrincipalByOwnerUuid: authenticator domain '"
					+ domain.getDomainName() + "' has no uuid field");
		}
		// owned stores a qualified id (${domainName}:${uuid}); strip the prefix for a direct read by
		// bare uuid (the principal is an authenticator entity, outside the pipeline scope).
		IFilter filter = Filter.eq(uuidField.toString(), OwnerIds.idOf(ownerUuid.toString()));
		List<Object> results = repo.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
		if (results == null || results.isEmpty()) {
			throw new ApiException("Principal not found for ownerId: " + ownerUuid);
		}
		return results.get(0);
	}

	/** Reads the (qualified) ownerId off an authorization entity, throwing when absent / unreadable. */
	private static Object readOwnerUuid(Object authzEntity, ObjectAddress ownedField) {
		Object ownerUuid;
		try {
			ownerUuid = DefaultMapper.reflection().getFieldValue(authzEntity, ownedField.toString());
		} catch (Exception e) {
			throw new ApiException("findPrincipalByOwnerUuid: failed to read ownerId from authorization: "
					+ e.getMessage(), e);
		}
		if (ownerUuid == null) {
			throw new ApiException("findPrincipalByOwnerUuid: authorization has no ownerId set");
		}
		return ownerUuid;
	}

	@Expression(name = "synthAuthFromPrincipal", description = "Builds a synthetic IAuthentication from a resolved principal and the authorities/type carried by an existing authorization entity, used to feed createAuthorizationEntity2 during a refresh operation.")
	public static IAuthentication synthAuthFromPrincipal(@Nullable Object principal, @Nullable Object existingAuthzEntity, @Nullable Object domainContext) {
		if (principal == null || existingAuthzEntity == null || domainContext == null) {
			throw new ApiException("synthAuthFromPrincipal: principal, existingAuthz and domainContext are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef)) {
			throw new ApiException("synthAuthFromPrincipal: authorization definition not resolved");
		}
		IReflection reflection = DefaultMapper.reflection();
		Object tokenType = null;
		if (authzDef.type() != null) {
			try {
				tokenType = reflection.getFieldValue(existingAuthzEntity, authzDef.type().toString());
			} catch (Exception e) {
				// keep null — the new entity will simply have no type
				log.trace("synthAuthFromPrincipal: could not read token type: {}", e.getMessage());
			}
		}
		List<String> authorities = SecurityExpressionsSupport.readTokenAuthorities(
				existingAuthzEntity, authzDef, reflection, log);
		// Fill the full security context onto the Authentication: identity (tenant / owner) read off
		// the token, super status from the server registries — the trusted identity the pipeline
		// reconciles the protocol caller against.
		var ctx = SecurityExpressionsSupport.readTokenSecurityContext(toDomain(domainContext), existingAuthzEntity);
		return new com.garganttua.api.commons.security.authentication.Authentication(
				true,
				principal,
				null,
				tokenType,
				authorities,
				ctx.tenantId(),
				ctx.ownerId(),
				ctx.superTenant(),
				ctx.superOwner(),
				true, true, true, true);
	}

	@Expression(name = "authenticationResponse", description = "Builds the IAuthentication returned to the client after a successful authenticate/login: the security context (tenantId/ownerId read off the minted token, super status from the registries, authorities from the token, the encoded token as authorization), SANITIZED of credentials and principal (never returned over the wire). Returns null when the token entity is null (lets the reuse branch fall through to fresh-create).")
	public static Object authenticationResponse(@Nullable Object authResult, @Nullable Object tokenEntity,
			@Nullable Object encodedToken, @Nullable Object domainContext) {
		Object token = unwrapOptional(tokenEntity);
		if (token == null) {
			return null;
		}
		IReflection reflection = DefaultMapper.reflection();
		// domainContext is the AUTHENTICATOR domain (e.g. users); the token's identity fields live on
		// the linked AUTHORIZATION domain, so resolve it to read tenant/owner correctly.
		IDomain<?> authzDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(toDomain(domainContext));
		var ctx = SecurityExpressionsSupport.readTokenSecurityContext(authzDomain, token);
		List<String> authorities = null;
		if (authzDomain != null
				&& SecurityExpressions.authorizationDefinition(domainContext)
						instanceof IDomainAuthorizationDefinition authzDef) {
			authorities = SecurityExpressionsSupport.readTokenAuthorities(token, authzDef, reflection, log);
		}
		Object authorization = unwrapOptional(encodedToken);
		return new com.garganttua.api.commons.security.authentication.Authentication(
				true,
				null, // principal — internal, never returned
				null, // credentials — internal, never returned
				authorization,
				authorities,
				ctx.tenantId(),
				ctx.ownerId(),
				ctx.superTenant(),
				ctx.superOwner(),
				true, true, true, true);
	}

	@Expression(name = "findByLogin", description = "Finds an entity by login field in the repository. Returns the entity or throws if not found.")
	public static Object findByLogin(@Nullable Object authContextObj, @Nullable Object repositoryObj, @Nullable Object loginValue) {
		if (authContextObj == null || repositoryObj == null || loginValue == null) {
			throw new ApiException("findByLogin: authContext, repository and login are required");
		}
		IAuthenticatorDefinition authDef = (IAuthenticatorDefinition) authContextObj;
		IRepository repo = (IRepository) repositoryObj;
		ObjectAddress loginField = authDef.login();
		if (loginField == null) {
			throw new ApiException("findByLogin: no login field configured on authenticator");
		}
		String loginFieldName = loginField.toString();
		IFilter filter = Filter.eq(loginFieldName, loginValue);
		List<Object> results = repo.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
		if (results == null || results.isEmpty()) {
			throw new ApiException("User not found for login: " + loginValue);
		}
		return results.get(0);
	}

	@Expression(name = "checkAccountStatus", description = "Checks enabled/locked/expired flags on an authenticator entity. Returns true if OK, throws if account is disabled/locked/expired.")
	public static boolean checkAccountStatus(@Nullable Object authContextObj, @Nullable Object entity) {
		if (authContextObj == null || entity == null) {
			throw new ApiException("checkAccountStatus: authContext and entity are required");
		}
		IAuthenticatorDefinition authDef = (IAuthenticatorDefinition) authContextObj;

		if (authDef.alwaysEnabled()) {
			return true;
		}

		IReflection reflection = DefaultMapper.reflection();

		if (authDef.enabled() != null) {
			Object value = reflection.getFieldValue(entity, authDef.enabled().toString());
			if (!Boolean.TRUE.equals(value)) {
				throw new ApiException("Account is disabled");
			}
		}

		if (authDef.accountNonLocked() != null) {
			Object value = reflection.getFieldValue(entity, authDef.accountNonLocked().toString());
			if (!Boolean.TRUE.equals(value)) {
				throw new ApiException("Account is locked");
			}
		}

		if (authDef.accountNonExpired() != null) {
			Object value = reflection.getFieldValue(entity, authDef.accountNonExpired().toString());
			if (!Boolean.TRUE.equals(value)) {
				throw new ApiException("Account is expired");
			}
		}

		if (authDef.credentialsNonExpired() != null) {
			Object value = reflection.getFieldValue(entity, authDef.credentialsNonExpired().toString());
			if (!Boolean.TRUE.equals(value)) {
				throw new ApiException("Credentials are expired");
			}
		}

		return true;
	}

	@Expression(name = "prepareAuthContext", description = "Prepares the runtime context with request and domainContext variables for authenticate method suppliers")
	public static boolean prepareAuthContext(@Nullable Object request, @Nullable Object domainContext) {
		IRuntimeContext<?, ?> runtimeCtx = RuntimeExpressionContext.get();
		if (runtimeCtx != null && request != null) {
			runtimeCtx.setVariable("request", request);
		}
		if (runtimeCtx != null && domainContext != null) {
			runtimeCtx.setVariable("domainContext", domainContext);
		}
		return true;
	}

	@Expression(name = "tryAuthenticate", description = "Attempts authentication using the IAuthenticatorDefinition, iterating over authentication methods until one succeeds")
	public static Object tryAuthenticate(@Nullable Object authenticatorDefinition) {
		if (authenticatorDefinition == null) {
			throw new ApiException("No authenticator definition available");
		}
		try {
			IAuthenticatorDefinition def = (IAuthenticatorDefinition) authenticatorDefinition;
			List<IAuthenticationDefinition> authDefs = def.authenticationDefinitions();
			if (authDefs == null || authDefs.isEmpty()) {
				throw new ApiException("No authentication methods configured");
			}

			return authDefs.stream()
					.map(SecurityAuthenticationExpressions::attemptAuthentication)
					.filter(Objects::nonNull)
					.findFirst()
					.orElseThrow(() -> new ApiException("All authentication methods failed"));
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Authentication failed: " + e.getMessage(), e);
		}
	}

	private static IAuthentication attemptAuthentication(IAuthenticationDefinition authDef) {
		try {
			IMethodBinder<?> binder = authDef.authenticateMethodBinder();
			if (binder == null) {
				return null;
			}

			Optional<? extends IMethodReturn<?>> result;
			if (binder instanceof IContextualMethodBinder<?, ?> contextualBinder) {
				IRuntimeContext<?, ?> runtimeCtx = RuntimeExpressionContext.get();
				result = ((IContextualMethodBinder<?, Object>) contextualBinder).execute(runtimeCtx);
			} else {
				result = binder.execute();
			}

			if (result.isEmpty()) {
				return null;
			}

			Object returned = result.get().single();
			if (returned instanceof IAuthentication auth) {
				if (auth.authenticated()) return auth;
			}
			return null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ----- Authorization → authenticate pipeline bridge -----

	@Expression(name = "protocolTargetDomain", description = "Returns the entity class that an IAuthorizationProtocol declares as its target — the domain on which the authenticate pipeline should run for tokens decoded by this protocol.")
	public static IClass<?> protocolTargetDomain(@Nullable Object protocol) {
		IAuthorizationProtocol p = (IAuthorizationProtocol) unwrapOptional(protocol);
		if (p == null) {
			throw new ApiException("Authorization protocol is null");
		}
		IClass<?> target = p.targetDomain();
		if (target == null) {
			throw new ApiException(
					"IAuthorizationProtocol '" + p.getClass().getName()
					+ "' returned null from targetDomain()");
		}
		return target;
	}

	@Expression(name = "resolveDomainByEntityClass", description = "Iterates over IApi.getDomain and returns the first domain whose entity class matches. Throws 500 if none found.")
	public static IDomain<?> resolveDomainByEntityClass(@Nullable Object apiContext, @Nullable Object entityClass) {
		IApi api = (IApi) unwrapOptional(apiContext);
		IClass<?> target = (IClass<?>) unwrapOptional(entityClass);
		if (api == null) {
			throw new ApiException("API context is null");
		}
		if (target == null) {
			throw new ApiException("Target entity class is null");
		}
		if (api instanceof com.garganttua.api.core.api.Api concrete) {
			for (IDomain<?> domain : concrete.getDomains().values()) {
				IClass<?> domainEntity = domain.getEntityClass();
				if (domainEntity != null && domainEntity.equals(target)) {
					return domain;
				}
			}
		}
		throw new ApiException(
				"No domain registered for entity class: " + target.getName());
	}

	@Expression(name = "verifyAuthorization", description = "Single server-side verification step used by VERIFY_AUTHORIZATION.gs. The decoded authorization (token) verifies ITSELF: it must be a registered, authenticator-enabled domain. Resolves the token's own domain from the entity class, forges an AuthenticationRequest (login = token uuid, credentials = decoded token, tenantId = token's tenant) and runs that domain's authenticate pipeline — the user-declared @AuthenticationAuthenticate method enforces signature / expiration / revocation / custom rules. On success the framework resolves the OWNER from the token's qualified ownerId and returns it as the principal (carrying the token's type + authorities). Throws ApiException (→ 401) when the token is not a verifiable authenticator domain, fails its authenticate method, or its owner cannot be resolved.")
	public static IAuthentication verifyAuthorization(@Nullable Object apiContext,
			@Nullable Object authorization, @Nullable Object operationRequest) {
		IApi api = (IApi) unwrapOptional(apiContext);
		Object authz = unwrapOptional(authorization);
		if (api == null || authz == null) {
			throw new ApiException("verifyAuthorization: apiContext and authorization are required");
		}

		// 1. Resolve the token's OWN domain from the decoded entity's class. No registered domain →
		//    trusted in-process Mode-B token; carries no resolved identity (null lets reconcile fall
		//    back to the trusted protocol caller).
		IDomain<?> authzDomain = SecurityVerificationSupport.domainOfEntity(api, authz);
		if (authzDomain == null) {
			return new com.garganttua.api.commons.security.authentication.Authentication(
					true, authz, null, authz, null,
					null, null, false, false,
					true, true, true, true);
		}

		// 2. Framework-owned intrinsic checks (expiration + revocation) — reject fast (→ 401).
		SecurityVerificationSupport.validateAuthorizationFromDefinition(authz, authzDomain);

		// 3. Verify the token — custom (authenticator declared → run its authenticate pipeline) or
		//    default (framework verifies the signature itself; no-op when not signable).
		DomainDefinition<?> domDef = toDomainDefinition(authzDomain);
		boolean hasAuthenticator = domDef != null
				&& domDef.domainSecurityDefinition() != null
				&& domDef.domainSecurityDefinition().authenticatorDefinition() != null;
		if (hasAuthenticator) {
			verifyViaAuthenticator(api, authzDomain, authz, operationRequest);
		} else if (!SecuritySigningExpressions.verifyIfSignable(authz, authzDomain, operationRequest)) {
			throw new ApiException("Authorization signature verification failed");
		}

		// 4. Server resolves the OWNER as the final principal (SERVER-AUTHORITATIVE: a storable
		//    token's persisted record wins over the decoded payload to prevent privilege escalation).
		Object owner = SecurityVerificationSupport.resolveOwnerPrincipal(api, authzDomain, authz);
		Object trusted = SecurityVerificationSupport.serverAuthoritativeAuthorization(authzDomain, authz);
		return synthAuthFromPrincipal(owner, trusted, authzDomain);
	}

	/**
	 * Verifies a token whose domain declares an authenticator: framework-owned signature check
	 * (against the key resolved from the token's qualified signedBy; no-op when not signable), then
	 * the user authenticate pipeline (BUSINESS rules only) forged from the token's uuid/credentials/
	 * tenant. Throws (→ 401) on a tampered signature or a rejecting authenticate.
	 */
	private static void verifyViaAuthenticator(IApi api, IDomain<?> authzDomain, Object authz,
			Object operationRequest) {
		if (SecurityAuthorizationExpressions.isAuthorizationSignable(authzDomain)
				&& !SecuritySigningExpressions.verifyTokenSignature(authz, authzDomain, operationRequest)) {
			throw new ApiException("Authorization signature verification failed");
		}
		String login = SecurityExpressions.readField(authz, authzDomain.getEntityDefinition().uuid());
		ObjectAddress tenantAddr = authzDomain.getEntityDefinition().tenantId();
		String tenantId = tenantAddr != null ? SecurityExpressions.readField(authz, tenantAddr) : null;
		IAuthenticationRequest authRequest =
				new com.garganttua.api.core.security.authentication.AuthenticationRequest(login, authz);
		// The token's own tenant drives the lookup (verify flow), not the request body.
		invokeAuthenticate(api, authzDomain, authRequest, tenantId);
	}


	@Expression(name = "invokeAuthenticate", description = "Synchronously invokes the 'authenticate' operation on the given target domain with the provided IAuthenticationRequest as body. Returns the resulting IAuthentication or throws ApiException on failure (mapped to 401 by the caller).")
	public static IAuthentication invokeAuthenticate(@Nullable Object apiContext, @Nullable Object targetDomain,
			@Nullable Object authRequest, @Nullable Object tenantId) {
		IApi api = (IApi) unwrapOptional(apiContext);
		IDomain<?> domain = (IDomain<?>) unwrapOptional(targetDomain);
		IAuthenticationRequest req = (IAuthenticationRequest) unwrapOptional(authRequest);
		if (api == null || domain == null || req == null) {
			throw new ApiException("invokeAuthenticate: apiContext, targetDomain and authRequest must all be non-null");
		}
		Object tenantUnwrapped = unwrapOptional(tenantId);
		String tenant = tenantUnwrapped == null ? null : tenantUnwrapped.toString();

		@SuppressWarnings({"unchecked", "rawtypes"})
		IClass<?> entityClass = ((IDomain) domain).getEntityClass();
		com.garganttua.api.core.service.OperationRequest invocation =
				new com.garganttua.api.core.service.OperationRequest(new java.util.HashMap<>());
		invocation.arg(IOperationRequest.OPERATION,
				OperationDefinition.authenticate(domain.getDomainName(), entityClass));
		invocation.arg("entity", req);
		// The tenant is set on the caller of the authenticate invocation (not in the
		// request body): for the verify flow it is the token's own tenant.
		if (tenant != null && !tenant.isBlank()) {
			invocation.arg(IOperationRequest.TENANT_ID, tenant);
			invocation.arg(IOperationRequest.REQUESTED_TENANT_ID, tenant);
		}

		IOperationResponse response = domain.invoke(invocation);
		OperationResponseCode code = response.getResponseCode();
		if (code != OperationResponseCode.OK && code != OperationResponseCode.CREATED) {
			throw new ApiException("Authenticate invocation on domain '" + domain.getDomainName()
					+ "' returned " + code + ": " + response.getResponse());
		}
		Object body = response.getResponse();
		if (body instanceof IAuthentication auth) {
			return auth;
		}
		throw new ApiException("Authenticate invocation on domain '" + domain.getDomainName()
				+ "' did not return an IAuthentication — got: "
				+ (body == null ? "null" : body.getClass().getName()));
	}
}
