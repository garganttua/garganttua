package com.garganttua.api.core.expression;
import com.garganttua.core.reflection.annotations.Reflected;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.OwnerIds;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.Pluralizer;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.service.IOperationRequest;
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
 * Authorization-issuance security expressions split out of {@link SecurityExpressions}: creating /
 * looking up / issuing / reusing authorization entities, the storable & refreshable rules, and the
 * signed-payload guards. Kept in the {@code com.garganttua.api.core.expression} package so the
 * {@code @Expression} methods remain discovered by the package scan; behaviour is identical.
 */
@Reflected(queryAllPublicMethods = true)
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals", "PMD.NullAssignment"})
public class SecurityAuthorizationExpressions {

	private static final com.garganttua.core.observability.Logger log =
			com.garganttua.core.observability.Logger.getLogger(SecurityAuthorizationExpressions.class);

	private SecurityAuthorizationExpressions() {
	}

	@Expression(name = "createAuthorizationEntity", description = "Creates a new authorization entity with fields populated from authentication result, principal uuid and tenant id")
	public static Object createAuthorizationEntity(@Nullable Object authorizationDefObj,
			@Nullable Object authenticationResult, @Nullable Object domainContextObj,
			@Nullable Object principalUuid, @Nullable Object tenantId) {
		if (authorizationDefObj == null || authenticationResult == null) {
			throw new ApiException("createAuthorizationEntity: authorizationDef and authenticationResult are required");
		}

		IDomainAuthorizationDefinition authzDef = (IDomainAuthorizationDefinition) authorizationDefObj;
		IAuthentication authResult = (IAuthentication) authenticationResult;
		IDomain<?> authenticatorDomain = toDomain(domainContextObj);
		IAuthenticatorDefinition authDef = null;
		DomainDefinition<?> domDef = toDomainDefinition(authenticatorDomain);
		if (domDef != null) {
			var secDef = domDef.domainSecurityDefinition();
			if (secDef != null) {
				authDef = secDef.authenticatorDefinition();
			}
		}
		IReflection reflection = DefaultMapper.reflection();
		IDomain<?> authzDomain = resolveAuthorizationDomain(authenticatorDomain);
		return SecurityExpressionsSupport.buildAuthorizationEntity(authzDomain, authenticatorDomain,
				authzDef, authDef, authResult, principalUuid, tenantId, reflection);
	}

	@Expression(name = "lookupValidAuthorization", description = "Looks up a valid (non-expired, non-revoked) authorization owned by the principal via a direct repository query on the authorization domain. Bypasses the workflow on purpose: this is a framework-internal lookup, not user-triggered traffic, so the authorization pipeline (which expects a caller-supplied token) does not apply.")
	public static @Nullable Object lookupValidAuthorization(@Nullable Object authorizationDefObj,
			@Nullable Object domainContextObj, @Nullable Object principalUuid, @Nullable Object tenantId) {
		if (authorizationDefObj == null || domainContextObj == null || principalUuid == null) {
			return null;
		}
		try {
			IDomainAuthorizationDefinition authzDef = (IDomainAuthorizationDefinition) authorizationDefObj;
			IDomain<?> authenticatorDomain = toDomain(domainContextObj);

			IDomain<?> authzDomain = resolveAuthorizationDomain(authenticatorDomain);
			if (authzDomain == null) {
				return null;
			}
			IFilter combinedFilter = buildReuseFilter(
					authzDef, authzDomain, authenticatorDomain, principalUuid, tenantId);
			List<Object> results = SecurityExpressions.invokeReadAll(authzDomain, combinedFilter);
			return (results != null && !results.isEmpty()) ? results.get(0) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Builds the reuse-lookup filter: owner (qualified) + optional tenant + non-revoked + non-expired. */
	private static IFilter buildReuseFilter(IDomainAuthorizationDefinition authzDef, IDomain<?> authzDomain,
			IDomain<?> authenticatorDomain, Object principalUuid, Object tenantId) {
		java.util.List<IFilter> filters = new java.util.ArrayList<>();
		// owned is stored qualified (${domainName}:${uuid}); query by the same form.
		ObjectAddress ownedField = authzDomain.getDomainDefinition().owned();
		if (ownedField != null) {
			filters.add(Filter.eq(ownedField.toString(),
					OwnerIds.qualify(authenticatorDomain.getDomainName(), principalUuid.toString())));
		}
		if (tenantId != null) {
			ObjectAddress tenantField = authzDomain.getTenantIdFieldAddress();
			if (tenantField != null) {
				filters.add(Filter.eq(tenantField.toString(), tenantId));
			}
		}
		if (authzDef.revoked() != null) {
			filters.add(Filter.eq(authzDef.revoked().toString(), false));
		}
		if (authzDef.expiration() != null) {
			filters.add(Filter.gt(authzDef.expiration().toString(), java.time.Instant.now()));
		}
		return filters.isEmpty() ? null
				: filters.size() == 1 ? filters.get(0)
				: Filter.and(filters.toArray(new Filter[0]));
	}

	static IDomain<?> resolveAuthorizationDomain(IDomain<?> authenticatorDomain) {
		DomainDefinition<?> domDef = toDomainDefinition(authenticatorDomain);
		if (domDef != null) {
			var secDef = domDef.domainSecurityDefinition();
			if (secDef != null && secDef.authenticatorDefinition() != null
					&& secDef.authenticatorDefinition().authorizationDefinition() != null
					&& secDef.authenticatorDefinition().authorizationDefinition().authorizationDomainBuilder() != null) {
				if (authenticatorDomain instanceof Domain<?> domCtx) {
					IApi apiContext = domCtx.getApiContext();
					if (apiContext != null) {
						try {
							var builder = secDef.authenticatorDefinition().authorizationDefinition().authorizationDomainBuilder();
							String simpleName = builder.getEntityClass().getSimpleName();
							String domainName = Pluralizer.toPlural(simpleName.toLowerCase(java.util.Locale.ROOT));
							return apiContext.getDomain(domainName).orElse(null);
						} catch (RuntimeException e) {
							return null;
						}
					}
				}
			}
		}
		return null;
	}

	@Expression(name = "createAuthorizationEntity2", description = "Creates an authorization entity from an authentication result and domain context")
	public static Object createAuthorizationEntity2(@Nullable Object authResultObj, @Nullable Object domainContextObj) {
		if (authResultObj == null || domainContextObj == null) {
			throw new ApiException("createAuthorizationEntity2: authResult and domainContext are required");
		}

		IAuthentication authResult = (IAuthentication) authResultObj;
		IDomain<?> authenticatorDomain = toDomain(domainContextObj);
		String principalUuid = readPrincipalUuid(authResult, authenticatorDomain);
		String tenantId = readPrincipalTenantId(authResult, authenticatorDomain);

		IDomainAuthorizationDefinition authzDef = (IDomainAuthorizationDefinition) SecurityExpressions.authorizationDefinition(authenticatorDomain);

		return createAuthorizationEntity(authzDef, authResult, authenticatorDomain, principalUuid, tenantId);
	}

	@Expression(name = "issueAuthorization", description = "Produces the authorization (token) after a successful authentication — the mint-side entry point used by CREATE_AUTHORIZATION. When a custom issuer method is declared via .authenticator().authorization(issuer, \"method\").withParam(...), delegates token production (shape + signature) to that bound method — enabling custom tokens or delegation to an external authorization server (Keycloak/OAuth2). The method's params are resolved from the runtime context (authentication result, domainContext, request) by the same supplier mechanism as the verify-side authenticate method. Otherwise runs the framework's standard minting: build the entity from the auth result, then sign it. Persistence + transport encoding still run AROUND this in the script.")
	public static Object issueAuthorization(@Nullable Object authResultObj, @Nullable Object domainContextObj,
			@Nullable Object request) {
		Object authResultUnwrapped = unwrapOptional(authResultObj);
		if (!(authResultUnwrapped instanceof IAuthentication authResult)) {
			throw new ApiException("issueAuthorization: a successful IAuthentication result is required");
		}
		IDomain<?> authenticatorDomain = toDomain(domainContextObj);
		if (authenticatorDomain == null) {
			throw new ApiException("issueAuthorization: domain context is required");
		}

		IMethodBinder<?> issuerBinder = resolveIssuerBinder(authenticatorDomain);

		// Custom issuer method (the mint-side dual of verify's authenticate): the user owns token
		// production (shape + signing); its params resolve from the runtime context by their suppliers.
		if (issuerBinder != null) {
			Object req = unwrapOptional(request);
			IOperationRequest opReq = (req instanceof IOperationRequest r) ? r : null;
			return SecurityExpressionsSupport.runCustomIssuer(
					issuerBinder, authResult, authenticatorDomain, opReq);
		}

		// Default: the framework is the authorization server — build + sign.
		Object entity = createAuthorizationEntity2(authResultObj, domainContextObj);
		SecuritySigningExpressions.signIfSignable(entity, domainContextObj, request);
		return entity;
	}

	/**
	 * The custom token-production (mint) binder declared on the authenticator via
	 * {@code .authorization(issuer, "method")}. {@code null} when none is declared
	 * (the framework then mints with its standard build + sign).
	 */
	private static IMethodBinder<?> resolveIssuerBinder(IDomain<?> authenticatorDomain) {
		IAuthenticatorDefinition authDef = SecurityExpressions.authenticatorContext(authenticatorDomain);
		return authDef != null ? authDef.authorizationMethodBinder() : null;
	}

	/**
	 * Returns the storable authorization currently valid for this principal in
	 * the linked authorization domain, or {@code null} when the token is not
	 * storable or no reusable entry exists. CREATE_AUTHORIZATION.gs branches on
	 * the result to skip the sign + persist round when reuse is possible.
	 *
	 * <p>The lookup filter (built by {@link #lookupValidAuthorization}) matches
	 * the authzDef's ownerId + tenantId + revoked=false + expiration&gt;NOW. An
	 * expired token is treated as absent — the script then mints a fresh one.
	 */
	@Expression(name = "findReusableAuthorization", description = "Looks up an existing valid (non-expired, non-revoked) authorization owned by the principal in the linked authorization domain when the authorization is storable. Returns the entity if found, or null when not storable / none reusable. Used by CREATE_AUTHORIZATION to skip create + sign + persist on the reuse path.")
	public static @Nullable Object findReusableAuthorization(@Nullable Object domainContextObj,
			@Nullable Object authResultObj) {
		if (domainContextObj == null || authResultObj == null) return null;
		if (!(authResultObj instanceof IAuthentication authResult)) return null;
		IDomain<?> authenticatorDomain = toDomain(domainContextObj);
		Object defObj = SecurityExpressions.authorizationDefinition(authenticatorDomain);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.storable()) {
			return null;
		}
		String principalUuid = readPrincipalUuid(authResult, authenticatorDomain);
		if (principalUuid == null) return null;
		String tenantId = readPrincipalTenantId(authResult, authenticatorDomain);
		return lookupValidAuthorization(authzDef, authenticatorDomain, principalUuid, tenantId);
	}

	/**
	 * Catch-handler companion in CREATE_AUTHORIZATION.gs that fires on the reuse
	 * path (when {@link #findReusableAuthorization} returned a non-null entity
	 * and the {@code requirePresent(if(isNull(@output),1))} guard throws to
	 * short-circuit the fresh-create block). Encodes the reused authorization
	 * to its transport form (if an encode method is configured) and publishes
	 * it on the request as {@code encodedAuthorization}, so downstream stages
	 * see the same wire shape they get on a fresh token.
	 */
	@Expression(name = "publishReusedAuthorization", description = "Catch-handler companion in CREATE_AUTHORIZATION. Runs when findReusableAuthorization returned a non-null entity. Encodes the reused authorization to its transport form (if an encode method is configured) and publishes it on the request as 'encodedAuthorization' so downstream stages see the same wire shape as a fresh token.")
	public static boolean publishReusedAuthorization(@Nullable Object authzEntity,
			@Nullable Object domainContextObj, @Nullable Object request) {
		if (authzEntity == null || domainContextObj == null) return false;
		Object encoded = SecuritySigningExpressions.encodeIfPossible(authzEntity, domainContextObj);
		if (request instanceof IOperationRequest opReq && encoded != null) {
			opReq.arg("encodedAuthorization", encoded);
		}
		return true;
	}

	@Expression(name = "encodeReusedIfPresent", description = "Reuse-path companion in CREATE_AUTHORIZATION. When a reusable authorization entity is present, encodes it to its transport form (if an encode method is configured), publishes that wire form on the request as 'encodedAuthorization', and RETURNS the encoded form (or the entity when no encode method) so it becomes the operation output. Returns null untouched when there is no reusable entity, so the fresh-create branch runs.")
	public static @Nullable Object encodeReusedIfPresent(@Nullable Object authzEntity,
			@Nullable Object domainContextObj, @Nullable Object request) {
		if (authzEntity == null || domainContextObj == null) {
			return null;
		}
		Object encoded = SecuritySigningExpressions.encodeIfPossible(authzEntity, domainContextObj);
		if (request instanceof IOperationRequest opReq && encoded != null) {
			opReq.arg("encodedAuthorization", encoded);
		}
		return encoded != null ? encoded : authzEntity;
	}

	private static @Nullable String readPrincipalUuid(IAuthentication authResult, IDomain<?> authenticatorDomain) {
		Object principal = authResult.principal();
		if (principal == null || authenticatorDomain.getEntityDefinition() == null) return null;
		ObjectAddress uuidAddr = authenticatorDomain.getEntityDefinition().uuid();
		if (uuidAddr == null) return null;
		try {
			Object val = DefaultMapper.reflection().getFieldValue(principal, uuidAddr.toString());
			return val != null ? val.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static @Nullable String readPrincipalTenantId(IAuthentication authResult, IDomain<?> authenticatorDomain) {
		Object principal = authResult.principal();
		if (principal == null) return null;
		ObjectAddress tenantAddr = authenticatorDomain.getTenantIdFieldAddress();
		if (tenantAddr == null) return null;
		try {
			Object val = DefaultMapper.reflection().getFieldValue(principal, tenantAddr.toString());
			return val != null ? val.toString() : null;
		} catch (Exception e) {
			return null;
		}
	}


	@Expression(name = "authResultPrincipal", description = "Extracts the principal from an IAuthentication result")
	public static Object authResultPrincipal(@Nullable Object authResult) {
		if (authResult instanceof IAuthentication auth) {
			return auth.principal();
		}
		return null;
	}

	@Expression(name = "setRequestArg", description = "Sets a named argument on the operation request")
	public static boolean setRequestArg(@Nullable Object request, @Nullable Object key, @Nullable Object value) {
		if (request == null || key == null) return false;
		IOperationRequest opRequest = (IOperationRequest) request;
		opRequest.arg(key.toString(), value);
		return true;
	}

	/**
	 * Well-known request arg under which {@link #recordCaughtException} stashes
	 * the exception object. Domain.doInvoke reads it back to surface the exact
	 * type + message on OperationResponse failures rather than the generic
	 * fallback wording.
	 */
	public static final String LAST_EXCEPTION_ARG = "_lastException";

	@Expression(name = "recordCaughtException", description = "Catch-handler companion for the script's `! => recordCaughtException(@0, @exception) -> CODE` pattern. Stores the throwable bound to `@exception` on the operation request under the well-known '_lastException' key, so Domain.doInvoke can surface the exact exception type + message on the OperationResponse instead of falling back to a synthesised wording. Returns true on success; never throws (a broken catch handler must not turn a captured error into a SERVER_ERROR).")
	public static boolean recordCaughtException(@Nullable Object request, @Nullable Object exception) {
		if (!(request instanceof IOperationRequest opRequest)) return false;
		Object unwrapped = unwrapOptional(exception);
		if (!(unwrapped instanceof Throwable t)) return false;
		opRequest.arg(LAST_EXCEPTION_ARG, t);
		return true;
	}

	@Expression(name = "requireNotDirectAuthorizationCreate", description = "CREATE_ONE guard for authorization domains. A SIGNABLE authorization may only be minted by the framework's authenticate/refresh pipeline, which persists it ALREADY SIGNED (CREATE_AUTHORIZATION / REFRESH_AUTHORIZATION → persistIfStorable → invokeInternal). A direct client CRUD create is rejected: a caller cannot produce a valid signature, so it would store an unsigned/forgeable token. No-op for ordinary domains and non-signable authorizations; passes for framework-internal writes (recognised by the server-set FRAMEWORK_INTERNAL_WRITE marker, never read from the wire). Throws (→ 403) otherwise.")
	public static boolean requireNotDirectAuthorizationCreate(@Nullable Object entity, @Nullable Object domainContext,
			@Nullable Object request) {
		if (!SecurityExpressions.isOwnAuthorizationSignable(domainContext)) {
			return true;
		}
		IOperationRequest req = (unwrapOptional(request) instanceof IOperationRequest r) ? r : null;
		boolean frameworkInternal = req != null
				&& Boolean.TRUE.equals(req.arg(SecurityExpressions.FRAMEWORK_INTERNAL_WRITE_ARG).orElse(null));
		if (frameworkInternal) {
			return true;
		}
		throw new ApiException("A signable authorization cannot be created directly. It is issued (and signed) "
				+ "by the authentication pipeline — obtain it through authentication or token refresh.");
	}

	@Expression(name = "authorizationSignedPayload", description = "Returns the SIGNED payload of a signable authorization entity — the bytes its getDataToSign method produces, base64-encoded — or null when the domain is not a signable authorization (or no getDataToSign / entity). Used by UPDATE_ONE to capture the pre-update signed material so a mutation that would invalidate the signature can be detected without resolving the signing key.")
	public static @Nullable String authorizationSignedPayload(@Nullable Object entity, @Nullable Object domainContext) {
		Object defObj = SecurityExpressions.ownAuthorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.signable()) {
			return null;
		}
		ObjectAddress dataMethod = authzDef.getDataToSignMethod();
		Object e = unwrapOptional(entity);
		if (dataMethod == null || e == null) {
			return null;
		}
		byte[] data = DefaultMapper.reflection().invokeMethod(e, dataMethod.toString(), IClass.getClass(byte[].class));
		return data == null ? null : java.util.Base64.getEncoder().encodeToString(data);
	}

	@Expression(name = "requireSignedPayloadUnchanged", description = "UPDATE_ONE guard for signable authorizations: a signed token's signed material is immutable. Compares the pre-update signed payload (captured via authorizationSignedPayload before the merge) with the merged entity's. Equal (e.g. only the revoked flag — which getDataToSign does not cover — changed) passes, so revocation stays allowed; different throws (→ 400), because the change would invalidate the stored signature. No-op when the pre-update payload is null (non-signable domain).")
	public static boolean requireSignedPayloadUnchanged(@Nullable Object beforePayload, @Nullable Object mergedEntity,
			@Nullable Object domainContext) {
		Object before = unwrapOptional(beforePayload);
		if (!(before instanceof String beforeStr)) {
			return true;
		}
		String after = authorizationSignedPayload(mergedEntity, domainContext);
		if (beforeStr.equals(after)) {
			return true;
		}
		throw new ApiException("A signed authorization is immutable: this update changes a field covered by the "
				+ "signature, which would invalidate it. Only non-signed fields (e.g. revocation) may be updated.");
	}

	@Expression(name = "isAuthorizationStorable", description = "Returns true if the authorization definition has storable=true")
	public static boolean isAuthorizationStorable(@Nullable Object authorizationDefObj) {
		if (authorizationDefObj instanceof IDomainAuthorizationDefinition def) {
			return def.storable();
		}
		return false;
	}

	@Expression(name = "persistIfStorable", description = "Persists the freshly-issued authorization entity to the linked authorization domain's repository when the resolved authorization definition has storable=true. No-op otherwise.")
	public static boolean persistIfStorable(@Nullable Object authzEntity, @Nullable Object authenticatorDomain) {
		if (authzEntity == null || authenticatorDomain == null) {
			throw new ApiException("persistIfStorable: entity and authenticatorDomain are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(authenticatorDomain);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef) || !authzDef.storable()) {
			return true;
		}
		IDomain<?> authzDomain = resolveAuthorizationDomain(toDomain(authenticatorDomain));
		if (authzDomain == null) {
			throw new ApiException("persistIfStorable: storable authorization but no authorization domain linked");
		}
		try {
			SecurityExpressions.invokeCreate(authzDomain, authzEntity);
			return true;
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("persistIfStorable: failed to save authorization: " + e.getMessage(), e);
		}
	}

	@Expression(name = "isAuthorizationSignable", description = "Returns true if the resolved authorization definition is configured as signable")
	public static boolean isAuthorizationSignable(@Nullable Object domainContext) {
		Object def = SecurityExpressions.authorizationDefinition(domainContext);
		return def instanceof IDomainAuthorizationDefinition d && d.signable();
	}


	// ----- Refresh authorization (Phase 2) -----

	@Expression(name = "isAuthorizationRefreshable", description = "Returns true if the resolved authorization definition is configured as refreshable (i.e. .refreshable() was called on its DSL).")
	public static boolean isAuthorizationRefreshable(@Nullable Object domainContext) {
		Object def = SecurityExpressions.authorizationDefinition(domainContext);
		return def instanceof IDomainAuthorizationDefinition d && d.refreshable();
	}

	@Expression(name = "refreshNotRevoked", description = "Reads the refresh-revoked field on an authorization entity (the field declared by .refreshable().revokable(field)). Returns true if not revoked or no refresh-revoked field is configured. Returns false if the field reads true.")
	public static boolean refreshNotRevoked(@Nullable Object authzEntity, @Nullable Object domainContext) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("refreshNotRevoked: entity and domainContext are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef)) {
			throw new ApiException("refreshNotRevoked: authorization definition not resolved");
		}
		ObjectAddress refreshRevoked = authzDef.refreshRevoked();
		if (refreshRevoked == null) {
			// No refresh-revoked field configured — treat as never revoked.
			return true;
		}
		try {
			Object value = DefaultMapper.reflection().getFieldValue(authzEntity, refreshRevoked.toString());
			return !Boolean.TRUE.equals(value);
		} catch (Exception e) {
			throw new ApiException("refreshNotRevoked: failed to read refresh-revoked field: " + e.getMessage(), e);
		}
	}

	@Expression(name = "refreshNotExpired", description = "Reads the refresh-expiration Instant on an authorization entity (the field declared by .refreshable().expirable(field)). Returns true when the expiration is in the future or no field is configured. Returns false when the refresh has expired.")
	public static boolean refreshNotExpired(@Nullable Object authzEntity, @Nullable Object domainContext) {
		if (authzEntity == null || domainContext == null) {
			throw new ApiException("refreshNotExpired: entity and domainContext are required");
		}
		Object defObj = SecurityExpressions.authorizationDefinition(domainContext);
		if (!(defObj instanceof IDomainAuthorizationDefinition authzDef)) {
			throw new ApiException("refreshNotExpired: authorization definition not resolved");
		}
		ObjectAddress refreshExpiration = authzDef.refreshExpiration();
		if (refreshExpiration == null) {
			// No refresh-expiration field configured — treat as no expiration.
			return true;
		}
		try {
			Object value = DefaultMapper.reflection().getFieldValue(authzEntity, refreshExpiration.toString());
			if (!(value instanceof java.time.Instant exp)) {
				// Field present but null or wrong type — treat as expired (refuse).
				return false;
			}
			return exp.isAfter(java.time.Instant.now());
		} catch (Exception e) {
			throw new ApiException("refreshNotExpired: failed to read refresh-expiration field: " + e.getMessage(), e);
		}
	}
}
