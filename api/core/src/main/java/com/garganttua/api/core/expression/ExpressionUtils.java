package com.garganttua.api.core.expression;

import java.util.Optional;

import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.core.reflection.IMethodReturn;
import com.garganttua.core.reflection.IReflection;

/**
 * Shared utility methods used by expression classes.
 */
public class ExpressionUtils {

	static final IReflection REFLECTION = DefaultMapper.reflection();

	/**
	 * The value a bound method returned — <strong>surfacing the exception it threw</strong>.
	 *
	 * <p>
	 * A binder does not throw what the bound method threw: {@code MethodInvoker.invokeMethodSafely}
	 * catches the {@code InvocationTargetException} and CAPTURES the cause in the returned
	 * {@link IMethodReturn}. Calling {@code single()} on that result therefore yields {@code null}
	 * and the failure vanishes — the caller sees "the method returned nothing", which is a different
	 * and much less informative statement than "the method refused, and here is why".
	 * </p>
	 *
	 * <p>
	 * Every call site that invokes consumer code through a binder must go through this method. The
	 * framework got that wrong at four of its five such sites, with consequences ranging from a
	 * misleading error message to an entity persisted UNSECURED because the method meant to hash its
	 * credential had failed silently.
	 * </p>
	 *
	 * @param result what the binder returned
	 * @param what   how to name the invocation in the error, when the thrown type carries no status
	 * @return the single returned value, or {@code null} when the binder produced no result
	 */
	@SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
			justification = "Rethrowing the consumer's own captured exception verbatim IS the contract "
					+ "of this method: its whole purpose is to stop the binder from swallowing it. Wrapping "
					+ "it in a declared type would lose the status and message the consumer chose.")
	static Object singleOrThrow(Optional<? extends IMethodReturn<?>> result, String what) {
		if (result.isEmpty()) {
			return null;
		}
		IMethodReturn<?> returned = result.get();
		if (returned.hasException()) {
			Throwable error = returned.getException();
			// An ApiException carrying a chosen status propagates verbatim — the consumer's message
			// and status are the point. Anything else is wrapped so it still reaches the caller.
			if (error instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (error instanceof Error err) {
				throw err;
			}
			throw new ApiException(what + " threw: " + error.getMessage(), error);
		}
		return returned.single();
	}

	public static Object unwrapOptional(Object value) {
		if (value instanceof Optional<?> opt) {
			return opt.orElse(null);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	static <T> Optional<T> unwrap(Object value, Class<T> type) {
		Object unwrapped = value;
		while (unwrapped instanceof Optional<?> opt) {
			if (opt.isEmpty()) return Optional.empty();
			Object inner = opt.get();
			if (type.isInstance(inner)) return Optional.of(type.cast(inner));
			unwrapped = inner;
		}
		return Optional.ofNullable(type.isInstance(unwrapped) ? type.cast(unwrapped) : null);
	}

	public static IDomain<?> toDomain(Object context) {
		return context instanceof Optional<?> opt
				? (IDomain<?>) opt.get()
				: (IDomain<?>) context;
	}

	public static DomainDefinition<?> toDomainDefinition(IDomain<?> dc) {
		if (dc instanceof Domain<?> domCtx) {
			return (DomainDefinition<?>) domCtx.getDomainDefinition();
		}
		return null;
	}

	private ExpressionUtils() {}
}
