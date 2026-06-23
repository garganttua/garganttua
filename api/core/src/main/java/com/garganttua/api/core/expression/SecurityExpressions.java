package com.garganttua.api.core.expression;
import com.garganttua.core.reflection.annotations.Reflected;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.definition.IDomainKeyDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.operation.OperationType;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.authentication.IAuthenticationRequest;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.Key;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;
import jakarta.annotation.Nullable;
import static com.garganttua.api.core.expression.ExpressionUtils.*;

/**
 * Expressions for security: access control, authentication, and authorization.
 */
@Reflected(queryAllPublicMethods = true)
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals", "PMD.NullAssignment"})
public class SecurityExpressions {

	private static final com.garganttua.core.observability.Logger log =
			com.garganttua.core.observability.Logger.getLogger(SecurityExpressions.class);

	// Framework-internal pipeline invocations on the key/authorization domains: run DURING
	// authentication (before any caller token exists) via access=anonymous + authority=false ops so
	// the security stages short-circuit while the full business pipeline runs; the caller is chosen to
	// keep stamping idempotent (create mirrors the entity's tenant/owner; lookup is a super caller).

	public static IApi apiOf(IDomain<?> domain) {
		return (domain instanceof Domain<?> d) ? d.getApiContext() : null;
	}

	public static String readField(Object entity, ObjectAddress addr) {
		if (addr == null) {
			return null;
		}
		Object v = DefaultMapper.reflection().getFieldValue(entity, addr.toString());
		return v != null ? v.toString() : null;
	}

	private static boolean readBoolean(Object entity, ObjectAddress addr) {
		if (addr == null) {
			return false;
		}
		Object v = DefaultMapper.reflection().getFieldValue(entity, addr.toString());
		return Boolean.TRUE.equals(v);
	}

	/** Caller for an internal CREATE: mirrors the entity's own tenant + owner so CREATE_ONE's ensure-stamping is idempotent. */
	private static ICaller callerFromEntity(IDomain<?> target, Object entity) {
		String tenantId = readField(entity, target.getTenantIdFieldAddress());
		ObjectAddress ownedAddr = target.getDomainDefinition().owned();
		String ownerId = ownedAddr != null ? readField(entity, ownedAddr) : null;
		if (tenantId == null) {
			return Caller.createAnonymousCaller();
		}
		return Caller.createTenantCallerWithOwnerId(tenantId, ownerId);
	}

	/** Caller for an internal lookup (readAll): super + NO requested tenant → RepositoryFilterTools returns the supplied filter unchanged (the explicit filter is the sole scope). */
	private static ICaller lookupCaller(IDomain<?> target) {
		IApi api = apiOf(target);
		String superTenantId = api != null ? api.getSuperTenantId() : null;
		if (superTenantId == null || superTenantId.isBlank()) {
			return Caller.createAnonymousCaller();
		}
		return new Caller(superTenantId, null, null, null, true, true, null);
	}

	/**
	 * Well-known request arg flagging a framework-internal pipeline write (a write
	 * issued by {@link #invokeInternal}, e.g. the authenticate/refresh token persist
	 * or a key auto-create) as opposed to a client-issued CRUD call. Set server-side
	 * only — never read from the wire — so it cannot be forged by a caller. Read by
	 * {@code requireNotDirectAuthorizationCreate} to let the framework mint a signable
	 * authorization while rejecting a direct external create.
	 */
	public static final String FRAMEWORK_INTERNAL_WRITE_ARG = "_frameworkInternalWrite";

	private static Object invokeInternal(IDomain<?> target, OperationDefinition op, ICaller caller,
			java.util.function.Consumer<com.garganttua.api.core.service.OperationRequest> setup) {
		com.garganttua.api.core.service.OperationRequest req =
				new com.garganttua.api.core.service.OperationRequest(new java.util.HashMap<>());
		req.arg(IOperationRequest.OPERATION, op);
		req.arg(IOperationRequest.TENANT_ID, caller.tenantId());
		req.arg(IOperationRequest.REQUESTED_TENANT_ID, caller.requestedTenantId());
		req.arg(IOperationRequest.CALLER_ID, caller.callerId());
		req.arg(IOperationRequest.OWNER_ID, caller.ownerId());
		req.arg(IOperationRequest.SUPER_TENANT, caller.superTenant());
		req.arg(IOperationRequest.SUPER_OWNER, caller.superOwner());
		req.arg(FRAMEWORK_INTERNAL_WRITE_ARG, Boolean.TRUE);
		setup.accept(req);
		IOperationResponse response = target.invoke(req);
		OperationResponseCode code = response.getResponseCode();
		if (code != OperationResponseCode.OK && code != OperationResponseCode.CREATED
				&& code != OperationResponseCode.UPDATED && code != OperationResponseCode.DELETED) {
			Throwable cause = response.getException().orElse(null);
			throw new ApiException("Internal pipeline invocation (" + op.technicalOperation()
					+ ") on domain '" + target.getDomainName() + "' returned " + code, cause);
		}
		return response.getResponse();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Object invokeCreate(IDomain<?> target, Object entity) {
		OperationDefinition op = OperationDefinition.createOne(target.getDomainName(),
				((IDomain) target).getEntityClass(), false, null, Access.anonymous);
		return invokeInternal(target, op, callerFromEntity(target, entity), req -> req.arg("entity", entity));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static List<Object> invokeReadAll(IDomain<?> target, @Nullable IFilter filter) {
		OperationDefinition op = OperationDefinition.readAll(target.getDomainName(),
				((IDomain) target).getEntityClass(), false, null, Access.anonymous);
		Object body = invokeInternal(target, op, lookupCaller(target), req -> {
			if (filter != null) {
				req.arg(IOperationRequest.FILTER, filter);
			}
		});
		if (body instanceof List<?> list) {
			return (List<Object>) list;
		}
		return body == null ? List.of() : List.of(body);
	}

	@Expression(name = "operationAccess", description = "Returns the Access level string from an OperationDefinition")
	public static String operationAccess(@Nullable Object operation) {
		if (operation == null) return "anonymous";
		OperationDefinition opDef = (OperationDefinition) unwrapOptional(operation);
		if (opDef == null) return "anonymous";
		return opDef.access() != null ? opDef.access().name() : "anonymous";
	}

	@Expression(name = "shouldSkipAuthorization", description = "True when VERIFY_AUTHORIZATION can short-circuit. An operation is verified when it is non-anonymous, OR it is anonymous but a token was actually presented (Mode A raw header or Mode B pre-decoded authorization) — that is OPTIONAL authentication: an anonymous op honours a valid token (identity persists) and rejects an invalid one (401). Skips when anonymous with no token. authenticate / refresh ops always skip: they carry their own credentials in the body.")
	public static boolean shouldSkipAuthorization(@Nullable Object request) {
		IOperationRequest req = (unwrapOptional(request) instanceof IOperationRequest r) ? r : null;
		if (req == null) {
			return true;
		}
		OperationDefinition op = req.arg(IOperationRequest.OPERATION).orElse(null);
		if (op == null) {
			return true;
		}
		// authenticate / refresh validate their own token (presented in the body); never gate them here.
		if (op.type() == OperationType.authentication || op.type() == OperationType.refreshAuthorization) {
			return true;
		}
		// A non-anonymous operation always verifies.
		if (op.access() != Access.anonymous) {
			return false;
		}
		// Anonymous: skip only when NO token was presented; a presented token is verified (→ 401 if invalid).
		boolean preDecoded = req.arg(IOperationRequest.AUTHORIZATION).orElse(null) != null;
		String raw = AuthorizationProtocolExpressions
				.rawAuthorizationAsString(req.arg(IOperationRequest.RAW_AUTHORIZATION).orElse(null));
		boolean rawPresent = raw != null && !raw.isBlank();
		return !(preDecoded || rawPresent);
	}

	@Expression(name = "operationAuthority", description = "Returns whether the operation requires an authority check")
	public static boolean operationAuthority(Object operation) {
		if (operation == null) return false;
		OperationDefinition opDef = (OperationDefinition) unwrapOptional(operation);
		if (opDef == null) return false;
		return opDef.authority();
	}

	@Expression(name = "operationAuthorityName", description = "Returns the authority name enforced for the operation: the explicit name configured via authority(String), or the auto-generated default <domain>:<operation> when only authority(true) was set. Returns null when no authority is required.")
	public static @Nullable String operationAuthorityName(@Nullable Object operation) {
		if (operation == null) return null;
		OperationDefinition opDef = (OperationDefinition) unwrapOptional(operation);
		if (opDef == null) return null;
		return opDef.effectiveAuthorityName();
	}

	@Expression(name = "callerHasAuthority", description = "Returns true when the caller carries an authority equal to the supplied name. Safe: returns false when caller is null, has no authorities, or the name is blank. Super-tenant / super-owner status does NOT bypass the check — being super grants cross-tenant / cross-owner reach, not the authority to perform an operation; a super caller must still carry the required authority.")
	public static boolean callerHasAuthority(@Nullable Object caller, @Nullable Object authorityName) {
		ICaller c = (ICaller) unwrapOptional(caller);
		if (c == null) return false;
		Object name = unwrapOptional(authorityName);
		if (!(name instanceof String authority) || authority.isBlank()) return false;
		java.util.List<String> authorities = c.authorities();
		return authorities != null && authorities.contains(authority);
	}

	@Expression(name = "guardSuperStatusOnWrite", description = "Before persisting a tenant/owner entity, rejects a LOCKED promotion to super-tenant / super-owner. No-op unless: the domain is a tenant (resp. owner) carrying a superTenant (resp. superOwner) field, that flag is true on the entity, the entity's uuid is NOT already registered as super, and the matching creation lock is on. Returns the entity unchanged.")
	public static Object guardSuperStatusOnWrite(@Nullable Object entity, @Nullable Object domainContext) {
		IDomain<?> domain = toDomain(domainContext);
		Object e = unwrapOptional(entity);
		if (domain == null || e == null) return entity;
		IApi api = apiOf(domain);
		if (api == null) return entity;
		var def = domain.getDomainDefinition();
		if (def == null) return entity;
		String uuid = readField(e, domain.getEntityDefinition().uuid());
		if (domain.isTenantEntity() && def.superTenant() != null
				&& readBoolean(e, def.superTenant())
				&& !api.isSuperTenant(uuid) && api.isSuperTenantCreationLocked()) {
			throw new ApiException("Super-tenant creation is locked: cannot promote tenant '" + uuid
					+ "' to super-tenant at runtime. Only the startup scan and the auto-created master tenant may "
					+ "seed super-tenants. Call .lockSuperTenantCreation(false) on the ApiBuilder to allow runtime promotion.");
		}
		if (def.owner() != null && def.superOwner() != null
				&& readBoolean(e, def.superOwner())
				&& !api.isSuperOwner(uuid) && api.isSuperOwnerCreationLocked()) {
			throw new ApiException("Super-owner creation is locked: cannot promote owner '" + uuid
					+ "' to super-owner at runtime. Call .lockSuperOwnerCreation(false) on the ApiBuilder to allow it.");
		}
		return entity;
	}

	@Expression(name = "syncSuperStatusRegistry", description = "After persisting a tenant/owner entity, maintains the server-side super registries: registers the entity's uuid when its superTenant/superOwner flag is true, unregisters it (demotion) when false. No-op for non-tenant/owner domains. Returns the entity unchanged.")
	public static Object syncSuperStatusRegistry(@Nullable Object entity, @Nullable Object domainContext) {
		IDomain<?> domain = toDomain(domainContext);
		Object e = unwrapOptional(entity);
		if (domain == null || e == null) return entity;
		IApi api = apiOf(domain);
		if (api == null) return entity;
		var def = domain.getDomainDefinition();
		if (def == null) return entity;
		String uuid = readField(e, domain.getEntityDefinition().uuid());
		if (uuid == null) return entity;
		if (domain.isTenantEntity() && def.superTenant() != null) {
			if (readBoolean(e, def.superTenant())) api.registerSuperTenant(uuid);
			else api.unregisterSuperTenant(uuid);
		}
		if (def.owner() != null && def.superOwner() != null) {
			if (readBoolean(e, def.superOwner())) api.registerSuperOwner(uuid);
			else api.unregisterSuperOwner(uuid);
		}
		return entity;
	}

	@Expression(name = "reconcileCaller", description = "Folds the (untrusted) protocol caller into the verified, trusted IAuthentication. Default (R1-R3): IAuthentication.reconcile — the token's identity (tenant/owner/super) wins over the headers; a header contradicting a non-super token is rejected (403); then the super flags are recomputed from the server registries on the resolved home (authoritative). Custom: when .authorization().reconcile(supplier,\"method\") is declared, that method owns caller resolution entirely (no registry super-recompute) — enabling self-contained tokens whose super status comes from signed claims. Returns the protocol caller unchanged when there is no authentication.")
	public static ICaller reconcileCaller(@Nullable Object authResult, @Nullable Object protocolCaller,
			@Nullable Object request, @Nullable Object domainContext, @Nullable Object apiContext) {
		Object auth = unwrapOptional(authResult);
		ICaller caller = (ICaller) unwrapOptional(protocolCaller);
		if (!(auth instanceof IAuthentication authentication)) {
			return caller;
		}

		// Custom reconcile method (default-or-custom): when .authorization().reconcile(supplier,
		// "method") is declared, delegate caller resolution to it. Its params — (IAuthentication,
		// ICaller) — are resolved from the runtime context by their suppliers, so we publish the
		// authentication / request first (mirrors the mint-side issuer). Otherwise: default R1-R3.
		IMethodBinder<?> binder = resolveReconcileBinder(domainContext);
		if (binder != null) {
			Object req = unwrapOptional(request);
			IOperationRequest opReq = (req instanceof IOperationRequest r) ? r : null;
			// Custom reconcile owns the result entirely — trust its super flags (self-contained).
			return SecurityExpressionsSupport.runCustomReconcile(
					binder, authentication, toDomain(domainContext), opReq);
		}
		// Default R1-R3, then the server-authoritative super-recompute on the resolved home.
		ICaller reconciled = authentication.reconcile(caller);
		return applyServerAuthoritativeSuperStatus(reconciled, apiContext);
	}

	private static IMethodBinder<?> resolveReconcileBinder(Object domainContext) {
		Object defObj = authorizationDefinition(domainContext);
		return (defObj instanceof IDomainAuthorizationDefinition def) ? def.reconcileBinder() : null;
	}

	@Expression(name = "applyServerAuthoritativeSuperStatus", description = "Recomputes the caller's superTenant/superOwner flags from the server-side registries (membership of the caller's tenantId / ownerId), OVERRIDING whatever the protocol claimed, and returns a corrected ICaller. The verify script stores it back as the request's 'caller' arg so all downstream stages (filtering, authority, update) trust the registry, not the token.")
	public static ICaller applyServerAuthoritativeSuperStatus(@Nullable Object caller, @Nullable Object apiContext) {
		ICaller c = (ICaller) unwrapOptional(caller);
		if (c == null) return null;
		Object ctx = unwrapOptional(apiContext);
		if (!(ctx instanceof IApi api)) return c;
		boolean superTenant = api.isSuperTenant(c.tenantId());
		boolean superOwner = api.isSuperOwner(c.ownerId());
		if (superTenant == c.superTenant() && superOwner == c.superOwner()) {
			return c;
		}
		return new Caller(c.tenantId(), c.requestedTenantId(), c.callerId(), c.ownerId(),
				superTenant, superOwner, c.authorities());
	}

	@Expression(name = "isSecurityDisabled", description = "Returns true if the domain has security disabled")
	public static boolean isSecurityDisabled(Object context) {
		IDomain<?> dc = toDomain(context);
		DomainDefinition<?> domDef = toDomainDefinition(dc);
		if (domDef != null) {
			return domDef.domainSecurityDefinition() == null || domDef.domainSecurityDefinition().disabled();
		}
		return true;
	}

	@Expression(name = "requireAuthentication", description = "Checks that the caller has been authenticated (authorization present in request)")
	public static boolean requireAuthentication(@Nullable Object request) {
		IOperationRequest opRequest = (IOperationRequest) request;
		Optional<Object> authorization = opRequest.arg(IOperationRequest.AUTHORIZATION);
		if (authorization.isEmpty()) {
			throw new ApiException("Authentication required but no authorization token provided");
		}
		return true;
	}

	@Expression(name = "requireTenantId", description = "Checks that the caller has a tenantId set")
	public static boolean requireTenantId(@Nullable Object caller) {
		ICaller c = (ICaller) unwrapOptional(caller);
		if (c == null || c.requestedTenantId() == null) {
			throw new ApiException("Tenant ID is required for this operation");
		}
		return true;
	}

	@Expression(name = "requireOwnerId", description = "Checks that the caller has an ownerId set")
	public static boolean requireOwnerId(@Nullable Object caller) {
		ICaller c = (ICaller) unwrapOptional(caller);
		if (c == null || c.ownerId() == null) {
			throw new ApiException("Owner ID is required for this operation");
		}
		return true;
	}

	@Expression(name = "callerHasTenantId", description = "Returns true if the caller has a non-null requestedTenantId (safe, never throws)")
	public static boolean callerHasTenantId(@Nullable Object caller) {
		ICaller c = (ICaller) unwrapOptional(caller);
		return c != null && c.requestedTenantId() != null;
	}

	@Expression(name = "callerHasOwnerId", description = "Returns true if the caller has a non-null ownerId (safe, never throws)")
	public static boolean callerHasOwnerId(@Nullable Object caller) {
		ICaller c = (ICaller) unwrapOptional(caller);
		return c != null && c.ownerId() != null;
	}

	@Expression(name = "authRequestLogin", description = "Extracts the login from an IAuthenticationRequest (safe, never throws)")
	public static @Nullable Object authRequestLogin(@Nullable Object entity) {
		Object unwrapped = unwrapOptional(entity);
		if (unwrapped instanceof IAuthenticationRequest req) {
			return req.login();
		}
		return null;
	}

	@Expression(name = "requireCallerTenantForScope", description = "For a tenant-scoped authenticator, requires the caller to carry a tenantId (taken from the caller — over HTTP, the X-Tenant-Id header — never from the AuthenticationRequest body). Throws a parlant ApiException naming what is missing; no-op for non-tenant scopes.")
	public static boolean requireCallerTenantForScope(@Nullable Object operationRequest, @Nullable Object scope) {
		Object scopeUnwrapped = unwrapOptional(scope);
		String scopeName = scopeUnwrapped == null ? null : scopeUnwrapped.toString();
		if (!"tenant".equals(scopeName)) {
			return true;
		}
		IOperationRequest req = (IOperationRequest) unwrapOptional(operationRequest);
		String tenantId = req == null ? null : req.arg(IOperationRequest.TENANT_ID).orElse(null);
		if (tenantId == null || tenantId.isBlank()) {
			throw new ApiException("Tenant-scoped authentication requires the caller's tenant. "
					+ "Provide it on the caller.");
		}
		return true;
	}

	@Expression(name = "isTenantIdMandatory", description = "Always false since the token-authoritative redesign: there is no Access.tenant gate. Tenant isolation is folded into IAuthentication.reconcile (the verified token carries the caller's tenant) and the repository filter; a tenantId is never required on the caller based on the access level.")
	public static boolean isTenantIdMandatory(Object operation, Object context) {
		return false;
	}

	@Expression(name = "isOwnerIdMandatory", description = "Always false since the token-authoritative redesign: there is no Access.owner gate. Owner isolation is folded into reconcile + the repository filter.")
	public static boolean isOwnerIdMandatory(Object operation, Object context) {
		return false;
	}

	@Expression(name = "authenticatorContext", description = "Returns the IAuthenticatorDefinition from the domain's security definition")
	public static IAuthenticatorDefinition authenticatorContext(Object context) {
		IDomain<?> dc = toDomain(context);
		DomainDefinition<?> domDef = toDomainDefinition(dc);
		if (domDef != null) {
			var secDef = domDef.domainSecurityDefinition();
			if (secDef != null) {
				return secDef.authenticatorDefinition();
			}
		}
		return null;
	}

	@Expression(name = "applySecurityOnEntity", description = "Runs the authenticator's custom applySecurityOnEntity method(s) on the entity being created/updated (after validation, before persist) — e.g. hashing a password. Each configured authentication's binder receives the CURRENT entity (SecuredEntitySupplier) and may mutate it in place or return a secured entity (both honored, chained). No-op when the domain is not an authenticator or declares no applySecurityOnEntity. Returns the (possibly secured) entity.")
	public static Object applySecurityOnEntity(@Nullable Object entityObj, @Nullable Object domainContextObj,
			@Nullable Object request) {
		Object entity = unwrapOptional(entityObj);
		if (entity == null) {
			return entity;
		}
		IAuthenticatorDefinition authDef = authenticatorContext(domainContextObj);
		if (authDef == null || authDef.authenticationDefinitions() == null) {
			return entity;
		}
		IDomain<?> domain = toDomain(domainContextObj);
		Object req = unwrapOptional(request);
		IOperationRequest opReq = (req instanceof IOperationRequest r) ? r : null;

		for (IAuthenticationDefinition auth : authDef.authenticationDefinitions()) {
			entity = SecurityExpressionsSupport.applyOneSecurityBinder(auth, entity, domain, opReq);
		}
		return entity;
	}

	@Expression(name = "authenticatorScope", description = "Returns the authenticator scope string from IAuthenticatorDefinition")
	public static String authenticatorScope(Object authContext) {
		if (authContext instanceof IAuthenticatorDefinition def) {
			return def.scope() != null ? def.scope().name() : null;
		}
		return null;
	}

	@Expression(name = "hasAuthorizationConfig", description = "Returns true if the authenticator has an authorization definition configured")
	public static boolean hasAuthorizationConfig(@Nullable Object authContextObj) {
		if (authContextObj instanceof IAuthenticatorDefinition def) {
			return def.authorizationDefinition() != null;
		}
		return false;
	}

	@Expression(name = "authorizationDefinition", description = "Returns the IDomainAuthorizationDefinition from the domain's security definition, resolving from the linked authorization domain if needed")
	public static @Nullable Object authorizationDefinition(@Nullable Object context) {
		IDomain<?> dc = toDomain(context);
		DomainDefinition<?> domDef = toDomainDefinition(dc);
		if (domDef != null) {
			var secDef = domDef.domainSecurityDefinition();
			if (secDef != null) {
				if (secDef.authorizationDefinition() != null) {
					return secDef.authorizationDefinition();
				}
				IDomain<?> authzDomain = SecurityAuthorizationExpressions.resolveAuthorizationDomain(dc);
				if (authzDomain != null && authzDomain.getDomainDefinition() instanceof DomainDefinition<?> dd
						&& dd.domainSecurityDefinition() != null) {
					return dd.domainSecurityDefinition().authorizationDefinition();
				}
			}
		}
		return null;
	}

	/**
	 * Returns the authorization definition the domain carries <strong>itself</strong>
	 * (its own {@code .security().authorization()}), with <strong>no</strong> fallback
	 * to a linked authorization domain. This is the token domain's own view — distinct
	 * from {@link #authorizationDefinition(Object)}, whose fallback makes an
	 * <em>authenticator</em> domain (which merely references a token domain) report the
	 * token's definition. CRUD-write guards must key on the OWN definition: a {@code users}
	 * authenticator that references a signable token domain is NOT itself a signable
	 * authorization and its CRUD writes must stay unguarded.
	 */
	public static @Nullable Object ownAuthorizationDefinition(@Nullable Object context) {
		IDomain<?> dc = toDomain(context);
		DomainDefinition<?> domDef = toDomainDefinition(dc);
		if (domDef != null && domDef.domainSecurityDefinition() != null) {
			return domDef.domainSecurityDefinition().authorizationDefinition();
		}
		return null;
	}

	@Expression(name = "isOwnAuthorizationSignable", description = "True when the domain ITSELF is a signable authorization (its own .security().authorization().signable()), with NO fallback to a linked token domain. Used by the CRUD-write guards so that an authenticator domain referencing a signable token domain is not mistaken for one — only the token domain itself is guarded.")
	public static boolean isOwnAuthorizationSignable(@Nullable Object domainContext) {
		Object def = ownAuthorizationDefinition(domainContext);
		return def instanceof IDomainAuthorizationDefinition d && d.signable();
	}

	// Persisted @Key entity ↔ IKeyRealm bridge — logic in SecurityKeyExpressions; kept here as the
	// stable cross-package entry points.

	public static IKeyRealm materializeKeyRealm(Object entity, IDomainKeyDefinition keyDef,
			IReflection reflection) {
		return SecurityKeyExpressions.materializeKeyRealm(entity, keyDef, reflection);
	}

	public static Object generateAndStampKeyEntity(IClass<?> entityClass, IDomainKeyDefinition keyDef,
			IKeyAlgorithm algorithm, SignatureAlgorithm signatureAlgorithm,
			String realmName, int duration, TimeUnit unit, IReflection reflection) {
		return SecurityKeyExpressions.generateAndStampKeyEntity(entityClass, keyDef, algorithm,
				signatureAlgorithm, realmName, duration, unit, reflection);
	}

}
