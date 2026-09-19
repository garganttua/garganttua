package com.garganttua.api.core.expression;

import java.util.concurrent.atomic.AtomicReference;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.SynchronizationPolicy;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.script.nodes.StatementBlock;

import jakarta.annotation.Nullable;

/**
 * Runs a write stage under the domain's mutex, so two instances cannot interleave it.
 *
 * <p>
 * The pipeline's write stages are a read-merge-write: {@code UPDATE_ONE.gs} reads the stored entity,
 * merges the non-null fields of the request, and saves the whole thing back. Two nodes doing that at
 * once both answer {@code 200} and the second erases what the first decided. Counting invariants —
 * "count the rows, then authorize the creation" — break the same way and just as silently. Wrapping
 * the stage makes the whole sequence atomic for everyone holding the same key.
 * </p>
 *
 * <p>
 * <strong>The block arrives as a {@link StatementBlock} to run, not as a value.</strong> A
 * parenthesized group substituted into a wrapper expression by {@code ScriptGenerator} reaches the
 * function unevaluated, exactly like the branch of {@code if(condition, block)}. A wrapper that
 * takes it as an already-supplied value takes the lock and executes nothing — the stage silently
 * does no work. That is why this function types the block as {@code Object} and runs it itself.
 * </p>
 */
@Reflected
public final class SynchronizationExpressions {

	private static final Logger log = Logger.getLogger(SynchronizationExpressions.class);

	private SynchronizationExpressions() {
		// Expression function holder
	}

	/**
	 * Executes {@code block} while holding the write key of the domain, when that domain declares a
	 * synchronization policy — and plainly, changing nothing, when it does not.
	 *
	 * <p>
	 * The key narrows to {@code <prefix>:<tenant>:<entity>}: two updates of the same entity exclude
	 * each other, two updates of different entities do not, and creations serialize per tenant since
	 * there is no uuid yet — which is the granularity a per-tenant quota needs.
	 * </p>
	 *
	 * <p>
	 * <strong>The stage guard sits INSIDE the block.</strong> {@code ScriptGenerator} emits the
	 * condition as part of the stage content, so a wrapper runs on every request whatever operation
	 * it carries — a read would take the key of all four write stages and do nothing under each. The
	 * stage therefore names the operation it serves, and the lock is taken only when the request
	 * actually is that operation.
	 * </p>
	 *
	 * @param domainContext the {@code IDomain} serving the request (workflow input {@code @2})
	 * @param request       the {@code IOperationRequest}, read for its tenant and entity uuid
	 * @param servedBy      the business operation label this stage serves, optionally suffixed
	 *                      {@code useCase/<name>} to serve one named use case
	 * @param block         the stage content to run under the lock
	 * @return whatever the stage produced
	 * @throws ApiException {@code 409} when the key could not be taken within the policy's strategy —
	 *                      a lock that silently gives up is not a lock, and the lost update it was
	 *                      meant to prevent would come back with no trace
	 */
	@Expression(name = "synchronizeWrite",
			description = "Runs a write stage under the domain's mutex, keyed by tenant and entity")
	public static Object synchronizeWrite(@Nullable Object domainContext, @Nullable Object request,
			@Nullable String servedBy, @Nullable Object block) {
		IDomain<?> domain = ExpressionUtils.toDomain(domainContext);
		SynchronizationPolicy policy = domain.synchronization().orElse(null);
		if (policy == null || !serves(request, servedBy)) {
			// Declared nowhere: the stage runs exactly as it did before there was a lock at all.
			return run(block);
		}
		MutexName key = policy.keyFor(domain.getDomainName(), tenantOf(request), entityUuidOf(request));
		IMutex mutex = acquireMutex(policy, key);
		if (mutex == null) {
			return run(block);
		}
		return runUnder(mutex, policy, key, block);
	}

	/**
	 * Resolves the key into a mutex, or null when the manager cannot — in which case the write runs
	 * unsynchronized with a warning rather than failing. An unresolvable lock is a deployment
	 * problem, and turning every write into a 500 would take the whole domain down over it.
	 */
	private static IMutex acquireMutex(SynchronizationPolicy policy, MutexName key) {
		try {
			return policy.manager().mutex(key);
		} catch (RuntimeException e) {
			log.warn("Cannot resolve the synchronization mutex '{}': {}; running this write WITHOUT "
					+ "synchronization", key, e.getMessage());
			return null;
		}
	}

	/**
	 * Holds {@code key} for the duration of the stage.
	 *
	 * <p>
	 * The stage's own failure is carried out of the critical section in a holder rather than thrown
	 * through {@code acquire}: an implementation that wraps whatever the function threw into a
	 * {@link MutexException} would otherwise turn a business refusal — a 400, a 404 — into "could
	 * not take the lock". Only a real acquisition failure reaches the {@code catch}.
	 * </p>
	 */
	@SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
			justification = "Rethrowing the stage's own failure verbatim IS the point: it is carried "
					+ "out of the critical section precisely so a business refusal keeps its status "
					+ "instead of being reported as a failure to take the lock.")
	private static Object runUnder(IMutex mutex, SynchronizationPolicy policy, MutexName key,
			@Nullable Object block) {
		AtomicReference<RuntimeException> stageFailure = new AtomicReference<>();
		Object result;
		try {
			result = mutex.acquire(() -> {
				try {
					return run(block);
				} catch (RuntimeException e) {
					stageFailure.set(e);
					return null;
				}
			}, policy.strategy());
		} catch (MutexException e) {
			log.warn("Could not take the synchronization key '{}' within the configured strategy", key);
			ApiException conflict = ApiException.conflict(
					"This write could not take its synchronization lock on '" + key.name()
							+ "' in time — another instance is holding it. Retry.");
			conflict.initCause(e);
			throw conflict;
		}
		RuntimeException failed = stageFailure.get();
		if (failed != null) {
			throw failed;
		}
		return result;
	}

	/**
	 * Whether this request is the operation the wrapped stage serves.
	 *
	 * <p>
	 * Anything else and the block is the stage's own {@code if(false, …)} — no work to protect, and
	 * a key taken for nothing would serialize unrelated requests on it.
	 * </p>
	 */
	private static boolean serves(@Nullable Object request, @Nullable String servedBy) {
		IOperationRequest req = toRequest(request);
		if (req == null || servedBy == null || req.operation() == null) {
			return false;
		}
		int useCaseMark = servedBy.indexOf(USE_CASE_MARK);
		if (!servedBy.contains(USE_CASE_MARK)) {
			return servedBy.equals(req.operation().getBusinessOperation().getLabel());
		}
		return servedBy.substring(useCaseMark + USE_CASE_MARK.length())
				.equals(req.operation().useCaseName());
	}

	/** The lookup type naming an entity by its uuid — the form every HTTP route uses. */
	private static final String UUID_LOOKUP = "uuid";

	/** Separates the business operation label from the use-case name it serves. */
	public static final String USE_CASE_MARK = "/";

	/** Runs the wrapped stage content. */
	private static Object run(@Nullable Object block) {
		if (block instanceof StatementBlock statements) {
			return statements.execute();
		}
		return block;
	}

	private static String tenantOf(@Nullable Object request) {
		IOperationRequest req = toRequest(request);
		return req == null ? null : req.arg(IOperationRequest.TENANT_ID).orElse(null);
	}

	/**
	 * The entity this write targets, when the request names one by uuid.
	 *
	 * <p>
	 * {@code Domain.bindScriptArgs} maps {@code entityUuid} onto the {@code identifier}/{@code type}
	 * pair the stage scripts actually read, and a request may set that pair directly, so the pair is
	 * the reliable source. A lookup by anything other than uuid yields null on purpose: the write
	 * then takes the coarser per-tenant key instead of a key the concurrent request would not share
	 * — narrower would be wrong, never safer.
	 * </p>
	 */
	private static String entityUuidOf(@Nullable Object request) {
		IOperationRequest req = toRequest(request);
		if (req == null) {
			return null;
		}
		Object lookupType = req.arg("type").orElse(null);
		if (UUID_LOOKUP.equals(lookupType)) {
			Object identifier = req.arg("identifier").orElse(null);
			if (identifier != null) {
				return identifier.toString();
			}
		}
		return req.arg(IOperationRequest.ENTITY_UUID).orElse(null);
	}

	private static IOperationRequest toRequest(@Nullable Object request) {
		return ExpressionUtils.unwrapOptional(request) instanceof IOperationRequest req ? req : null;
	}
}
