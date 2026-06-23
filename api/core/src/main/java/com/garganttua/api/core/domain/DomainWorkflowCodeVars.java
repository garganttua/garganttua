package com.garganttua.api.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.api.commons.operation.BusinessOperation;

/**
 * Computes the ordered list of per-stage {@code _code} workflow variables a domain's merged
 * workflow declares. Extracted from {@link DomainWorkflowAssembler} to keep that assembler under
 * the file-size gate; the variable names and ordering mirror the pipeline documented in PIPELINE.md.
 */
final class DomainWorkflowCodeVars {

	/** CRUD/business operation label → workflow script classpath (membership + lookup). */
	static final Map<String, String> CRUD_SCRIPT_PATHS = Map.of(
			BusinessOperation.create.getLabel(), "scripts/business/CREATE_ONE.gs",
			BusinessOperation.readAll.getLabel(), "scripts/business/READ_ALL.gs",
			BusinessOperation.readOne.getLabel(), "scripts/business/READ_ONE.gs",
			BusinessOperation.update.getLabel(), "scripts/business/UPDATE_ONE.gs",
			BusinessOperation.deleteOne.getLabel(), "scripts/business/DELETE_ONE.gs",
			BusinessOperation.deleteAll.getLabel(), "scripts/business/DELETE_ALL.gs",
			BusinessOperation.authenticate.getLabel(), "scripts/business/AUTHENTICATE.gs",
			BusinessOperation.refreshAuthorization.getLabel(), "scripts/business/REFRESH_AUTHORIZATION.gs"
	);

	private DomainWorkflowCodeVars() {
	}

	/**
	 * Builds the full ordered code-var list for a domain workflow.
	 *
	 * @param workflowLabels       the domain's declared workflow labels (CRUD ops are matched against {@code crudScriptPaths})
	 * @param useCaseNames         declared use-case names
	 * @param crudScriptPaths      the CRUD label → script-path map (membership test only)
	 * @param securityEnabled      whether the security pipeline is installed
	 * @param hasAuthorization     whether a create-authorization stage is declared
	 * @param multiTenancyEnabled  whether tenant rules / verification apply
	 * @param isOwnerOrOwned       whether owner rules / verification apply
	 * @return the ordered, mutable list of code-var names
	 */
	static List<String> collect(Set<String> workflowLabels, Set<String> useCaseNames,
			Map<String, String> crudScriptPaths, boolean securityEnabled, boolean hasAuthorization,
			boolean multiTenancyEnabled, boolean isOwnerOrOwned) {
		List<String> codeVars = new ArrayList<>();

		// Stage 1 — protocol extract (always declared; guarded at runtime)
		codeVars.add("_protocol_extract_protocol_extract_code");

		// Stage 4 — deserialize (always declared; guarded at runtime)
		codeVars.add("_deserialize_deserialize_code");

		// Business rules code vars
		if (multiTenancyEnabled) {
			codeVars.add("_tenant_rules_tenant_rules_code");
		}
		if (isOwnerOrOwned) {
			codeVars.add("_owner_rules_owner_rules_code");
		}

		collectSecurityCodeVars(codeVars, securityEnabled, multiTenancyEnabled, isOwnerOrOwned);
		collectOperationCodeVars(codeVars, workflowLabels, useCaseNames, crudScriptPaths);

		// Authorization code var
		if (hasAuthorization) {
			codeVars.add("_create_authorization_create_authorization_code");
		}

		// Stage 9 — serialize (always declared; guarded at runtime)
		codeVars.add("_serialize_serialize_code");

		// Stage 10 — protocol response (always declared; guarded at runtime)
		codeVars.add("_protocol_response_protocol_response_code");

		return codeVars;
	}

	private static void collectSecurityCodeVars(List<String> codeVars, boolean securityEnabled,
			boolean multiTenancyEnabled, boolean isOwnerOrOwned) {
		if (securityEnabled) {
			codeVars.add("_verify_authorization_verify_authorization_code");
			if (multiTenancyEnabled) {
				codeVars.add("_verify_tenant_verify_tenant_code");
			}
			if (isOwnerOrOwned) {
				codeVars.add("_verify_owner_verify_owner_code");
			}
			codeVars.add("_verify_authority_verify_authority_code");
		}
	}

	private static void collectOperationCodeVars(List<String> codeVars, Set<String> workflowLabels,
			Set<String> useCaseNames, Map<String, String> crudScriptPaths) {
		// CRUD operation code vars
		for (String label : workflowLabels) {
			if (crudScriptPaths.containsKey(label)) {
				String sanitized = label.replace("-", "_");
				codeVars.add("_" + sanitized + "_" + sanitized + "_code");
			}
		}

		// Use-case operation code vars — one per declared use case, treated like a CRUD
		// op (init 405, skipped→405, error propagates) so a request matching no use case
		// yields 405 and a failing use case surfaces its real code.
		for (String name : useCaseNames) {
			String sanitized = ("usecase-" + name).replace("-", "_");
			codeVars.add("_" + sanitized + "_" + sanitized + "_code");
		}
	}
}
