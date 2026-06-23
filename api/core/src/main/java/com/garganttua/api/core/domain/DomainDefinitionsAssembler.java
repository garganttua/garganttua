package com.garganttua.api.core.domain;

import java.util.HashMap;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IUseCaseBuilder;
import com.garganttua.api.commons.definition.IUseCaseDefinition;
import com.garganttua.api.commons.definition.IWorkflowDefinition;
import com.garganttua.api.commons.operation.Scope;
import com.garganttua.api.commons.operation.TechnicalOperation;
import com.garganttua.api.core.usecase.UseCaseBuilder;
import com.garganttua.api.core.usecase.UseCaseDefinition;
import com.garganttua.core.injection.IInjectableElementResolver;

/**
 * Materialises a domain's use-case and workflow definitions from their respective builders.
 * Extracted from {@link DomainBuilder#doBuild()} to keep that builder under the file-size gate.
 */
final class DomainDefinitionsAssembler {

	private DomainDefinitionsAssembler() {
	}

	/**
	 * Builds the use-case definitions, auto-wiring each bound method's annotated parameters first.
	 *
	 * @param useCases         the per-name use-case builders
	 * @param domainName       the domain name (route base)
	 * @param resolverRegistry the {@code @Resolver} registry for parameter auto-wiring
	 * @return the use-case-name → definition map
	 */
	static <E> Map<String, IUseCaseDefinition> buildUseCaseDefinitions(
			Map<String, IUseCaseBuilder<?, ?, E>> useCases, String domainName,
			IInjectableElementResolver resolverRegistry) throws ApiException {
		Map<String, IUseCaseDefinition> useCaseDefinitions = new HashMap<>();
		for (Map.Entry<String, IUseCaseBuilder<?, ?, E>> entry : useCases.entrySet()) {
			@SuppressWarnings("unchecked")
			UseCaseBuilder<?, ?, E> ucb = (UseCaseBuilder<?, ?, E>) entry.getValue();
			// Auto-wire the bound method's annotated parameters before the builder materialises the
			// binder — the method stays "completely free".
			DomainUseCaseSupport.autowireParameters(ucb.getBinderBuilder(), resolverRegistry);
			entry.getValue().build();
			Scope scope = ucb.getScope() != null ? ucb.getScope() : Scope.allEntities;
			TechnicalOperation verb = ucb.getOperation() != null ? ucb.getOperation() : TechnicalOperation.read;
			// Default the route suffix to the use case name so each use case gets a distinct path.
			String suffix = ucb.getPathSuffix() != null ? ucb.getPathSuffix() : ucb.getName();
			useCaseDefinitions.put(entry.getKey(), new UseCaseDefinition(
					ucb.getName(),
					DomainUseCaseSupport.buildUseCasePath(domainName, ucb.getCompletePath(), suffix, scope),
					ucb.getInputType(),
					ucb.getOutputType(),
					ucb.getBuiltBinder(),
					scope,
					verb,
					ucb.getAccess(),
					ucb.hasAuthority(),
					ucb.getCustomAuthority()));
		}
		return useCaseDefinitions;
	}

	/** Builds the workflow definitions (metadata) for all non-security-disabled workflows. */
	static <E> Map<String, IWorkflowDefinition> buildWorkflowDefinitions(
			Map<String, DomainWorkflowBuilder<E>> workflows) {
		Map<String, IWorkflowDefinition> workflowDefinitions = new HashMap<>();
		for (Map.Entry<String, DomainWorkflowBuilder<E>> entry : workflows.entrySet()) {
			DomainWorkflowBuilder<E> wb = entry.getValue();
			if (wb.isSecurityDisabled()) {
				continue;
			}
			workflowDefinitions.put(entry.getKey(), new WorkflowDefinition(
					wb.getWorkflowName(),
					wb.getPathSuffix(),
					wb.getCompletePath(),
					wb.getScope(),
					wb.getOperation(),
					wb.getAccess(),
					wb.hasAuthority(),
					wb.getCustomAuthority(),
					wb.isCustom()));
		}
		return workflowDefinitions;
	}
}
