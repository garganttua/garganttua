package com.garganttua.api.core.domain;

import com.garganttua.api.commons.context.SynchronizationPolicy;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.core.api.ApiBuilder;

/**
 * Where a domain's write synchronization is decided, and what it turns into in the script.
 *
 * <p>
 * Kept apart from the builders and the stage assembler so the concern reads in one place: which
 * declaration wins, which operations are writes, and the exact wrapper expression a write stage is
 * given. Without a declared policy every method here answers "nothing", and the generated pipeline
 * is character-for-character the one that existed before the feature.
 * </p>
 */
final class DomainSynchronization {

	/** Workflow-level alias of the operation request, so a wrap expression can name it. */
	static final String REQUEST_VAR = "_sync_request";

	private DomainSynchronization() {
		// Static helpers
	}

	/**
	 * The policy a domain runs under: its own when it declared one, the api's otherwise.
	 *
	 * @param own    what the domain builder declared, or null
	 * @param parent the api builder this domain hangs from
	 * @return the effective policy, or null when neither declared one
	 */
	static SynchronizationPolicy effective(SynchronizationPolicy own, Object parent) {
		if (own != null) {
			return own;
		}
		return parent instanceof ApiBuilder api ? api.declaredSynchronization() : null;
	}

	/** Whether this CRUD operation writes, and so must be serialized when a policy is declared. */
	static boolean isWrite(String label) {
		return BusinessOperation.create.getLabel().equals(label)
				|| BusinessOperation.update.getLabel().equals(label)
				|| BusinessOperation.deleteOne.getLabel().equals(label)
				|| BusinessOperation.deleteAll.getLabel().equals(label);
	}

	/**
	 * The expression wrapping a stage so it runs under the domain's mutex.
	 *
	 * <p>
	 * {@code @0} is what {@code ScriptGenerator} substitutes with the stage content, so it cannot
	 * also stand for the operation request here: the request is aliased once in the init-codes stage
	 * and read back by name.
	 * </p>
	 *
	 * @param servedBy the business operation label this stage serves, optionally suffixed with the
	 *                 use-case name it serves
	 * @return the wrapper expression
	 */
	static String wrapExpression(String servedBy) {
		return "synchronizeWrite(@2, @" + REQUEST_VAR + ", \"" + servedBy + "\", @0)";
	}

}
