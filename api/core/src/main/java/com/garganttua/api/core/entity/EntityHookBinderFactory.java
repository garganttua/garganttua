package com.garganttua.api.core.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IParameter;
import com.garganttua.core.reflection.binders.IMethodBinder;

/**
 * Builds the free lifecycle-hook binders for an {@link EntityBuilder} at build time, auto-wiring each
 * bound method's parameters (the entity-typed parameter receives the current entity; the rest are
 * resolved via the {@code @Resolver} registry). Extracted from {@code EntityBuilder} to keep that
 * wide-interface builder under the file-size gate.
 */
final class EntityHookBinderFactory {

	private EntityHookBinderFactory() {
	}

	/**
	 * Builds the free-hook binders keyed by hook name from the collected per-hook builders.
	 *
	 * @param freeHookBuilders the per-hook-name list of method-binder builders collected during DSL config
	 * @param entityClass      the entity class (used to detect the entity-typed parameter)
	 * @param resolverRegistry the {@code @Resolver} registry for the non-entity parameters (may be null)
	 * @return the hook-name → built binders map
	 */
	static <E> Map<String, List<IMethodBinder<?>>> buildFreeBinders(
			Map<String, List<EntityMethodBinderBuilder<E>>> freeHookBuilders, IClass<?> entityClass,
			com.garganttua.core.injection.IInjectableElementResolver resolverRegistry) throws ApiException {
		Map<String, List<IMethodBinder<?>>> result = new HashMap<>();
		for (Map.Entry<String, List<EntityMethodBinderBuilder<E>>> entry : freeHookBuilders.entrySet()) {
			List<IMethodBinder<?>> binders = new ArrayList<>();
			for (EntityMethodBinderBuilder<E> builder : entry.getValue()) {
				autowireHookParameters(builder, entityClass, resolverRegistry);
				binders.add(builder.build());
			}
			result.put(entry.getKey(), binders);
		}
		return result;
	}

	private static <E> void autowireHookParameters(EntityMethodBinderBuilder<E> binder, IClass<?> entityClass,
			com.garganttua.core.injection.IInjectableElementResolver resolverRegistry) throws ApiException {
		IMethod method;
		try {
			method = binder.method();
		} catch (com.garganttua.core.dsl.DslException e) {
			return;
		}
		if (method == null) {
			return;
		}
		IParameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			IParameter parameter = parameters[i];
			try {
				if (parameter.getType() != null && entityClass.getName().equals(parameter.getType().getName())) {
					binder.withParam(i, new HookEntitySupplierBuilder(entityClass));
				} else if (resolverRegistry != null) {
					com.garganttua.core.injection.Resolved resolved =
							resolverRegistry.resolve(parameter.getType(), parameter);
					if (resolved != null && resolved.resolved()) {
						binder.withParam(i, resolved.elementSupplier(), resolved.nullable());
					}
				}
			} catch (com.garganttua.core.injection.DiException | com.garganttua.core.dsl.DslException e) {
				throw new ApiException("Failed to auto-wire parameter " + i + " of hook method '"
						+ method.getName() + "': " + e.getMessage(), e);
			}
		}
	}
}
