package com.garganttua.api.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.api.commons.context.SynchronizationPolicy;
import com.garganttua.api.core.expression.SynchronizationExpressions;
import com.garganttua.core.workflow.dsl.IWorkflowBuilder;

/**
 * Stage 8 of a domain's pipeline: the stages that actually do the business.
 *
 * <p>
 * One stage per CRUD operation the domain exposes, one per declared use case, and — on an
 * authenticator domain — the authorization-minting stage that follows a successful authentication.
 * Extracted from {@link DomainWorkflowAssembler}, which is left with the pipeline's ordering and its
 * guards, so that "what a business stage looks like" reads in one place. That includes where the
 * write synchronization wrapper is applied, which only these stages carry.
 * </p>
 *
 * @param <E> the domain's entity type
 */
final class BusinessStageAssembler<E> {

	private final Map<String, DomainWorkflowBuilder<E>> workflows;
	private final Set<String> useCaseNames;
	private final boolean hasAuthorization;
	/** Null — the normal case — means no stage is wrapped and the generated script is unchanged. */
	private final SynchronizationPolicy synchronization;

	BusinessStageAssembler(Map<String, DomainWorkflowBuilder<E>> workflows, Set<String> useCaseNames,
			boolean hasAuthorization, SynchronizationPolicy synchronization) {
		this.workflows = workflows;
		this.useCaseNames = useCaseNames;
		this.hasAuthorization = hasAuthorization;
		this.synchronization = synchronization;
	}

	/** Maps a CRUD operation label to the script serving it. */
	private static final Map<String, String> CRUD_SCRIPT_PATHS = DomainWorkflowCodeVars.CRUD_SCRIPT_PATHS;

	List<String> buildOperationStages(IWorkflowBuilder builder, String guard) {
		List<String> operationCodeVars = new ArrayList<>();
		for (Map.Entry<String, DomainWorkflowBuilder<E>> entry : this.workflows.entrySet()) {
			String label = entry.getKey();
			DomainWorkflowBuilder<E> wb = entry.getValue();
			if (wb.isSecurityDisabled()) {
				continue;
			}

			String scriptPath = CRUD_SCRIPT_PATHS.get(label);
			if (scriptPath != null) {
				var stageBuilder = builder.stage(label)
						.when("equals(businessOperation(@0), \"" + label + "\")");
				// Writes only: a read loses nothing by interleaving, and locking it would cost on the
				// hot path for nothing (the fiche asking for this explicitly excludes reads).
				if (this.synchronization != null && DomainSynchronization.isWrite(label)) {
					stageBuilder = stageBuilder.wrap(DomainSynchronization.wrapExpression(label));
				}
				var scriptBuilder = stageBuilder
						.script("classpath:" + scriptPath)
							.name(label)
							.input("operationRequest", "@0")
							.input("repository", "@1")
							.input("domainContext", "@2");
				if (guard != null) {
					scriptBuilder.when(guard);
				}
				scriptBuilder.up().up();
				String sanitized = label.replace("-", "_");
				operationCodeVars.add("_" + sanitized + "_" + sanitized + "_code");
			}
		}
		return operationCodeVars;
	}

	/**
	 * One business stage per declared use case — the use-case counterpart of the CRUD stages. Each
	 * runs {@code USE_CASE.gs} (→ {@code invokeUseCase}) and is guarded by the business operation AND
	 * the use case's name, so a domain hosting several use cases routes each request to exactly one.
	 */
	List<String> buildUseCaseStages(IWorkflowBuilder builder, String guard) {
		List<String> codeVars = new ArrayList<>();
		String useCaseLabel = com.garganttua.api.commons.operation.BusinessOperation.useCase.getLabel();
		for (String name : this.useCaseNames) {
			String stageName = "usecase-" + name;
			var stageBuilder = builder.stage(stageName)
					.when("and(equals(businessOperation(@0), \"" + useCaseLabel + "\"), "
							+ "equals(useCaseName(@0), \"" + name + "\"))");
			// Opt-in, unlike CRUD writes: the framework cannot tell whether a use case writes, and
			// locking one that only reads would serialize it for nothing. The policy names those
			// that must be.
			if (this.synchronization != null && this.synchronization.covers(name)) {
				stageBuilder = stageBuilder.wrap(DomainSynchronization.wrapExpression(
						useCaseLabel + SynchronizationExpressions.USE_CASE_MARK + name));
			}
			var scriptBuilder = stageBuilder
					.script("classpath:scripts/business/USE_CASE.gs")
						.name(stageName)
						.input("operationRequest", "@0")
						.input("repository", "@1")
						.input("domainContext", "@2");
			if (guard != null) {
				scriptBuilder.when(guard);
			}
			scriptBuilder.up().up();
			String sanitized = stageName.replace("-", "_");
			codeVars.add("_" + sanitized + "_" + sanitized + "_code");
		}
		return codeVars;
	}

	List<String> buildCreateAuthorizationStage(IWorkflowBuilder builder, String guard) {
		if (!hasAuthorization) return List.of();

		String createAuthGuard = "equals(@_authenticate_authenticate_code, 0)";
		if (guard != null) {
			createAuthGuard = "and(" + createAuthGuard + ", " + guard + ")";
		}
		builder.stage("create-authorization")
				.when("equals(businessOperation(@0), \"authenticate\")")
				.script("classpath:scripts/business/CREATE_AUTHORIZATION.gs")
					.name("create-authorization")
					.input("operationRequest", "@0")
					.input("repository", "@1")
					.input("domainContext", "@2")
					.input("authResult", "@output")
					.output("output", "output")
					.when(createAuthGuard)
					.up()
				.up();
		return List.of("_create_authorization_create_authorization_code");
	}
}
