package com.garganttua.api.core.domain;

import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.operation.OperationPath;
import com.garganttua.api.commons.operation.Scope;
import com.garganttua.api.core.usecase.UseCaseBinderBuilder;
import com.garganttua.core.injection.IInjectableElementResolver;
import com.garganttua.core.injection.Resolved;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.injection.context.dsl.InjectableElementResolverBuilder;
import com.garganttua.core.injection.context.dsl.InjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IParameter;

/**
 * Use-case build support for {@link DomainBuilder}: route-path assembly, the dedicated
 * {@code @Resolver} registry, and declarative parameter auto-wiring. Extracted from
 * {@code DomainBuilder} to keep that wide-interface builder under the file-size gate.
 */
final class DomainUseCaseSupport {

	/**
	 * Packages scanned for declarative {@code @Resolver} parameter resolvers — the use case's
	 * {@code @UseCaseInput} and the security {@code @Caller}/{@code @ApiContext}/{@code @DomainContext}/…
	 * resolvers all live under the api tree. Core's built-in resolvers come from
	 * {@code setBuiltInResolvers}, not a scan.
	 */
	private static final String[] FRAMEWORK_RESOLVER_PACKAGES = { "com.garganttua.api" };

	private DomainUseCaseSupport() {
	}

	static OperationPath buildUseCasePath(String domainName, String completePath, String suffix, Scope scope) {
		String p;
		if (completePath != null && !completePath.isBlank()) {
			p = completePath.startsWith("/") ? completePath : "/" + completePath;
		} else {
			String base = "/" + domainName;
			p = (suffix != null && !suffix.isBlank()) ? base + "/" + suffix : base;
		}
		if (scope == Scope.oneEntity && !p.contains("${uuid}")) {
			p = p + "/${uuid}";
		}
		return new OperationPath(p);
	}

	/**
	 * Builds a fresh resolver registry that discovers every declarative {@code @Resolver}. A dedicated
	 * registry is used rather than the shared {@code injectionContextBuilder.resolvers()} because the
	 * latter is memoised — built (without the api packages) when the injection context is, so a late
	 * package scan could never reach it. Returns {@code null} when no injection context is available.
	 */
	static IInjectableElementResolver buildResolverRegistry(IInjectionContextBuilder injectionContextBuilder)
			throws ApiException {
		if (injectionContextBuilder == null) {
			return null;
		}
		try {
			InjectableElementResolverBuilder resolversBuilder =
					new InjectableElementResolverBuilder(injectionContextBuilder);
			resolversBuilder.setReflection(IClass.getReflection());
			InjectionContextBuilder.setBuiltInResolvers(resolversBuilder, Set.of(), false);
			resolversBuilder.withPackages(FRAMEWORK_RESOLVER_PACKAGES);
			resolversBuilder.autoDetect(true);
			return resolversBuilder.build();
		} catch (com.garganttua.core.dsl.DslException e) {
			throw new ApiException("Failed to build the use-case parameter resolver registry: "
					+ e.getMessage(), e);
		}
	}

	/**
	 * Auto-wires a use case's bound method parameters from their framework injection annotations — the
	 * declarative dual of the explicit {@code .withParam(i, supplier)}. A parameter no resolver matches
	 * is left untouched, so an explicit {@code .withParam(...)} still wins.
	 */
	static <E> void autowireParameters(UseCaseBinderBuilder<?, ?, E> binder,
			IInjectableElementResolver resolvers) throws ApiException {
		if (binder == null || resolvers == null) {
			return;
		}
		IMethod method;
		try {
			method = binder.method();
		} catch (com.garganttua.core.dsl.DslException e) {
			// No method bound yet (or address-only) — nothing to auto-wire.
			return;
		}
		if (method == null) {
			return;
		}
		IParameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			IParameter parameter = parameters[i];
			try {
				Resolved resolved = resolvers.resolve(parameter.getType(), parameter);
				if (resolved != null && resolved.resolved()) {
					binder.withParam(i, resolved.elementSupplier(), resolved.nullable());
				}
			} catch (com.garganttua.core.injection.DiException | com.garganttua.core.dsl.DslException e) {
				throw new ApiException("Failed to auto-wire parameter " + i + " of use case method '"
						+ method.getName() + "': " + e.getMessage(), e);
			}
		}
	}
}
