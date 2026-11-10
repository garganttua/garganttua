package com.garganttua.api.core.expression;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.security.authorization.VerificationPath;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.expression.SecurityVerificationSupport.StoredRecord;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.security.authentication.AuthenticationRequest;
import com.garganttua.api.core.security.authentication.KnownPrincipal;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.runtime.RuntimeExpressionContext;
import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Verifies a token whose domain declares its own authenticator — a token that verifies ITSELF through
 * a strategy — by the paths its domain declares.
 *
 * <p>
 * Two choices, made on the token domain's {@code .authorization()}, both {@link VerificationPath#PIPELINE}
 * by default:
 * </p>
 * <ul>
 * <li><b>{@code authenticateOnVerify}</b> — the domain's {@code authenticate} operation, a workflow nested
 * in the request's; or its strategies {@link VerificationPath#DIRECT directly}, in the request's pipeline,
 * with the checks that operation makes, in the same order and with the same outcome: the signature, the
 * tenant of a tenant-scoped authenticator, the principal, the account status, then the strategies in
 * their declared order — the first authenticated answer wins, none means 401;</li>
 * <li><b>{@code lookupOnVerify}</b> — the principal those strategies receive is found by the domain's
 * {@code readAll} operation, another nested workflow; or {@link VerificationPath#DIRECT from the
 * repository}. A storable token whose login is its uuid then costs no read of its own: its principal IS
 * the stored record the verification reads anyway ({@link StoredRecord}).</li>
 * </ul>
 *
 * <p>
 * The two are independent: a direct lookup is handed to the nested operation as well
 * ({@link KnownPrincipal}). Outside a pipeline — a direct Java call, with no runtime context to run a
 * strategy in — the operation is the only path, whatever is declared.
 * </p>
 */
final class TokenSelfVerification {

	/** The runtime variables a strategy's parameters are read from, set for the time of the call. */
	private static final String REQUEST = "request";
	private static final String DOMAIN_CONTEXT = "domainContext";

	private TokenSelfVerification() {
	}

	/**
	 * Verifies the token through its domain's authenticator, by the paths the token domain declares
	 * ({@code authenticateOnVerify}, {@code lookupOnVerify}). Throws (→ 401) on a tampered signature, a
	 * missing principal, or strategies that all refuse.
	 */
	static void verify(IApi api, IDomain<?> authzDomain, Object authz, Object operationRequest,
			StoredRecord stored) {
		if (SecurityAuthorizationExpressions.isAuthorizationSignable(authzDomain)
				&& !SecuritySigningExpressions.verifyTokenSignature(authz, authzDomain, operationRequest)) {
			throw new ApiException("Authorization signature verification failed");
		}
		String login = SecurityExpressions.readField(authz, authzDomain.getEntityDefinition().uuid());
		ObjectAddress tenantAddr = authzDomain.getEntityDefinition().tenantId();
		String tenantId = tenantAddr != null ? SecurityExpressions.readField(authz, tenantAddr) : null;
		IAuthenticatorDefinition authDef = SecurityExpressions.authenticatorContext(authzDomain);
		Object defObj = SecurityExpressions.authorizationDefinition(authzDomain);
		IDomainAuthorizationDefinition authzDef = defObj instanceof IDomainAuthorizationDefinition d ? d : null;

		Optional<KnownPrincipal> known = authzDef != null && authzDef.lookupOnVerify() == VerificationPath.DIRECT
				? knownPrincipal(authzDomain, authDef, login, tenantId, stored).map(KnownPrincipal::new)
				: Optional.empty();

		IRuntimeContext<?, ?> runtime = RuntimeExpressionContext.get();
		if (authzDef == null || authzDef.authenticateOnVerify() == VerificationPath.PIPELINE || runtime == null) {
			// The domain's authenticate operation — chosen, or the only place a strategy can run outside a
			// pipeline. The token's own tenant drives the lookup (verify flow), not the request body.
			SecurityAuthenticationExpressions.invokeAuthenticate(authzDomain,
					new AuthenticationRequest(login, authz), tenantId, known.orElse(null));
			return;
		}
		if (authDef.scope() == AuthenticatorScope.tenant && (tenantId == null || tenantId.isBlank())) {
			throw new ApiException("Tenant-scoped authentication requires the caller's tenant. "
					+ "Provide it on the caller.");
		}
		OperationRequest invocation = invocation(api, authzDomain, login, authz, tenantId, operationRequest);
		known.ifPresent(principal -> invocation.arg(KnownPrincipal.ARG, principal));
		runStrategies(runtime, authDef, authzDomain, invocation);
	}

	/** The request the strategies read, shaped as {@code Domain.invoke} shapes an authenticate request. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static OperationRequest invocation(IApi api, IDomain<?> authzDomain, String login, Object authz,
			String tenantId, Object operationRequest) {
		OperationRequest invocation = new OperationRequest(new HashMap<>());
		IClass<?> entityClass = ((IDomain) authzDomain).getEntityClass();
		invocation.arg(IOperationRequest.OPERATION,
				OperationDefinition.authenticate(authzDomain.getDomainName(), entityClass));
		invocation.arg("entity", new AuthenticationRequest(login, authz));
		if (tenantId != null && !tenantId.isBlank()) {
			invocation.arg(IOperationRequest.TENANT_ID, tenantId);
			invocation.arg(IOperationRequest.REQUESTED_TENANT_ID, tenantId);
		}
		// The verification belongs to the request under way: same execution id, correlatable with it.
		UUID executionUuid = operationRequest instanceof IOperationRequest outer
				? outer.arg(IOperationRequest.EXECUTION_UUID).orElse(null) : null;
		invocation.arg(IOperationRequest.EXECUTION_UUID,
				executionUuid != null ? executionUuid : UuidCreator.getTimeOrderedEpoch());
		invocation.arg(IOperationRequest.API_CONTEXT, api);
		invocation.arg(IOperationRequest.DOMAIN_CONTEXT, authzDomain);
		invocation.arg(IOperationRequest.REPOSITORY, authzDomain.getRepository());
		invocation.arg("caller", tenantId != null && !tenantId.isBlank()
				? invocation.caller() : Caller.createAnonymousCaller());
		return invocation;
	}

	/**
	 * The principal read from the repository, as the lookup by login does — by login, and by tenant for a
	 * tenant-scoped authenticator. For a storable token whose login is its uuid, the principal IS the
	 * stored record, already read (or to be read) once for the whole verification. Empty when no login
	 * field is declared: the supplier then says so, as before.
	 */
	private static Optional<Supplier<Optional<Object>>> knownPrincipal(IDomain<?> authzDomain,
			IAuthenticatorDefinition authDef, String login, String tenantId, StoredRecord stored) {
		ObjectAddress loginField = authDef.login();
		if (loginField == null) {
			return Optional.empty();
		}
		ObjectAddress tenantField = authzDomain.getEntityDefinition().tenantId();
		boolean byTenant = authDef.scope() == AuthenticatorScope.tenant && tenantField != null
				&& tenantId != null && !tenantId.isBlank();
		if (stored.exists() && loginField.equals(authzDomain.getEntityDefinition().uuid())) {
			return Optional.of(() -> stored.strict().filter(
					record -> !byTenant || tenantId.equals(SecurityExpressions.readField(record, tenantField))));
		}
		Filter byLogin = Filter.eq(loginField.toString(), login);
		IFilter filter = byTenant ? Filter.and(byLogin, Filter.eq(tenantField.toString(), tenantId)) : byLogin;
		// Read once, whichever strategy asks first: several may be declared on one authenticator.
		AtomicReference<Optional<Object>> found = new AtomicReference<>();
		return Optional.of(() -> {
			if (found.get() == null) {
				List<Object> results = authzDomain.getRepository()
						.getEntities(Optional.empty(), Optional.of(filter), Optional.empty());
				found.set(results == null || results.isEmpty() ? Optional.empty() : Optional.of(results.get(0)));
			}
			return found.get();
		});
	}

	/**
	 * Runs the strategies with the runtime variables their parameters read, then puts back what those
	 * variables held: the pipeline under way is not the authenticate one, and its own steps may read them.
	 */
	private static void runStrategies(IRuntimeContext<?, ?> runtime, IAuthenticatorDefinition authDef,
			IDomain<?> authzDomain, IOperationRequest invocation) {
		IClass<Object> any = IClass.getClass(Object.class);
		Optional<Object> previousRequest = runtime.getVariable(REQUEST, any);
		Optional<Object> previousDomain = runtime.getVariable(DOMAIN_CONTEXT, any);
		runtime.setVariable(REQUEST, invocation);
		runtime.setVariable(DOMAIN_CONTEXT, authzDomain);
		try {
			SecurityAuthenticationExpressions.tryAuthenticate(authDef);
		} finally {
			previousRequest.ifPresent(value -> runtime.setVariable(REQUEST, value));
			previousDomain.ifPresent(value -> runtime.setVariable(DOMAIN_CONTEXT, value));
		}
	}
}
