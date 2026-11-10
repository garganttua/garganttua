package com.garganttua.api.core.expression;

import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.security.authenticator.AuthenticatorScope;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.expression.SecurityVerificationSupport.StoredRecord;
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
 * a strategy — by running that strategy directly, in the pipeline already under way.
 *
 * <p>
 * It used to invoke the token domain's whole {@code authenticate} operation at every request: a nested
 * workflow, whose principal lookup was itself a nested {@code readAll} workflow. Half of a request's
 * time went there. The checks that operation makes are made here, in the same order and with the same
 * outcome, without either workflow:
 * </p>
 * <ol>
 * <li>the signature, against the key the token names (no-op when not signable);</li>
 * <li>a tenant-scoped authenticator requires the token's tenant;</li>
 * <li>the principal is found by login — for a storable token whose login is its uuid, it IS the stored
 * record the verification reads anyway ({@link StoredRecord}), so it costs nothing more;</li>
 * <li>the account status, and then the strategies in their declared order, exactly as
 * {@code AUTHENTICATE.gs} runs them — the first authenticated answer wins, none means 401.</li>
 * </ol>
 *
 * <p>
 * Outside a pipeline — a direct Java call, with no runtime context to run a strategy in — it falls back
 * to the {@code authenticate} operation, which brings its own.
 * </p>
 */
final class TokenSelfVerification {

	/** The runtime variables a strategy's parameters are read from, set for the time of the call. */
	private static final String REQUEST = "request";
	private static final String DOMAIN_CONTEXT = "domainContext";

	private TokenSelfVerification() {
	}

	/**
	 * Verifies the token through its domain's authenticator. Throws (→ 401) on a tampered signature, a
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
		IRuntimeContext<?, ?> runtime = RuntimeExpressionContext.get();
		if (runtime == null) {
			// The token's own tenant drives the lookup (verify flow), not the request body.
			SecurityAuthenticationExpressions.invokeAuthenticate(api, authzDomain,
					new AuthenticationRequest(login, authz), tenantId);
			return;
		}
		IAuthenticatorDefinition authDef = SecurityExpressions.authenticatorContext(authzDomain);
		if (authDef.scope() == AuthenticatorScope.tenant && (tenantId == null || tenantId.isBlank())) {
			throw new ApiException("Tenant-scoped authentication requires the caller's tenant. "
					+ "Provide it on the caller.");
		}
		OperationRequest invocation = invocation(api, authzDomain, login, authz, tenantId, operationRequest);
		knownPrincipal(authzDomain, authDef, tenantId, stored)
				.ifPresent(lookup -> invocation.arg(KnownPrincipal.ARG, new KnownPrincipal(lookup)));
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
	 * The principal, when it is the token's stored record: a storable token whose login is its uuid. A
	 * tenant-scoped authenticator also matches the record's tenant, as the lookup by login does. Empty for
	 * any other shape: the strategy's supplier then looks the principal up by login, as before.
	 */
	private static Optional<Supplier<Optional<Object>>> knownPrincipal(IDomain<?> authzDomain,
			IAuthenticatorDefinition authDef, String tenantId, StoredRecord stored) {
		ObjectAddress uuidField = authzDomain.getEntityDefinition().uuid();
		if (!stored.exists() || !Objects.equals(authDef.login(), uuidField)) {
			return Optional.empty();
		}
		ObjectAddress tenantField = authzDomain.getEntityDefinition().tenantId();
		boolean byTenant = authDef.scope() == AuthenticatorScope.tenant && tenantField != null;
		return Optional.of(() -> stored.strict()
				.filter(record -> !byTenant || tenantId.equals(SecurityExpressions.readField(record, tenantField))));
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
