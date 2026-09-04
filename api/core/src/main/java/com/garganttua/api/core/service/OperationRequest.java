package com.garganttua.api.core.service;

import com.garganttua.api.core.SuppressFBWarnings;

import com.garganttua.api.core.caller.Caller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.operation.OperationPath;
import com.garganttua.api.commons.service.ArgKey;
import com.garganttua.api.commons.service.IOperationRequest;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class OperationRequest implements IOperationRequest {

	private final Map<String, Object> args;

	public OperationRequest(Map<String, Object> args) {
		this.args = args != null ? new HashMap<>(args) : new HashMap<>();
	}

	@Override
	public Map<String, Object> args() {
		return this.args;
	}

	@Override
	public <T> Optional<T> arg(ArgKey<T> key) {
		T value = (T) this.args.get(key.name());
		return Optional.ofNullable(value);
	}

	@Override
	public <T> void arg(ArgKey<T> key, T value) {
		this.args.put(key.name(), value);
	}

	@Override
	public String domain() {
		OperationPath path = operationPath();
		return path != null ? path.domain() : null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Returns the caller the verification stage RECONCILED and published under {@link #CALLER} when
	 * there is one, and only falls back to rebuilding one from the protocol-layer args for an
	 * operation that never went through verification (a genuinely anonymous call, a
	 * framework-internal invocation, a test harness).
	 * </p>
	 *
	 * <p>
	 * The fallback used to be unconditional, and that made every membership check written against
	 * {@code ICaller} decorative: the tenant it exposed came from the {@code X-Tenant-Id} header —
	 * a value the caller chooses — rather than from the token the server verified. A consumer had
	 * no way to see it, {@code ICaller} being precisely the type handed to it to answer "who is
	 * calling". Note that the reconciled caller still carries the header's target separately, in
	 * {@link ICaller#requestedTenantId()} / {@link ICaller#requestedOwnerId()}: what changes is that
	 * the HOME tenant/owner is now the authenticated one.
	 * </p>
	 */
	@Override
	public ICaller caller() {
		ICaller verified = arg(CALLER).orElse(null);
		if (verified != null) {
			return verified;
		}
		return new Caller(
				arg(TENANT_ID).orElse(null),
				arg(REQUESTED_TENANT_ID).orElse(null),
				arg(CALLER_ID).orElse(null),
				arg(OWNER_ID).orElse(null),
				Boolean.TRUE.equals(arg(SUPER_TENANT).orElse(null)),
				Boolean.TRUE.equals(arg(SUPER_OWNER).orElse(null)),
				(List<String>) arg(AUTHORITIES).orElse(null));
	}

	@Override
	public OperationDefinition operation() {
		return arg(OPERATION).orElse(null);
	}

	@Override
	public OperationPath operationPath() {
		String path = arg(PATH).orElse(null);
		return path != null ? new OperationPath(path) : null;
	}

	@Override
	public Optional<?> arg(String key) {
		return Optional.ofNullable(this.args.get(key));
	}

	@Override
	public void arg(String key, Object value) {
		this.args.put(key, value);
	}

	@Override
	public UUID executionUuid() {
		return arg(EXECUTION_UUID).orElse(null);
	}

	@Override
	public UUID correlationUuid() {
		return arg(CORRELATION_UUID).orElse(null);
	}
}
