package com.garganttua.api.core.domain;

import java.util.Map;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.expression.SecurityExpressions;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Invocation diagnostics for {@link Domain}: recovers the functional exception/message a workflow
 * stage script would have surfaced before {@code ! -> CODE} ate it (by replaying the matching
 * Java-side validator or inferring from the request state), and builds the human-facing fallback
 * messages per failing stage / response code. Also hosts the empty-caller detection used by
 * {@code Domain.invoke}. Extracted from {@code Domain} to keep that wide context under the file-size
 * gate; behaviour is identical.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // repeated "' on '" fragments in fallback messages
final class DomainInvocationDiagnostics {

    private DomainInvocationDiagnostics() {
    }

    static Optional<String> nonBlank(Optional<String> opt) {
        return opt.filter(s -> s != null && !s.isBlank());
    }

    /**
     * Builds the caller {@code Domain.invoke} uses when the request carries no caller information:
     * anonymous. The tenant of a tenant-scoped authentication is carried by the caller itself (the
     * X-Tenant-Id header over HTTP), not read from the body, so there is nothing to materialize here.
     */
    static ICaller autoCreateCallerFromBody(IOperationRequest request) {
        return Caller.createAnonymousCaller();
    }

    /**
     * True when the caller carries no meaningful information — every identification field is null and
     * neither super flag is set (the synthetic Caller {@code OperationRequest.caller()} produces when
     * no caller/tenant/owner builder call ran). In that case Domain.invoke swaps in
     * {@link #autoCreateCallerFromBody}.
     */
    static boolean isEmptyCaller(ICaller caller) {
        if (caller == null) {
            return true;
        }
        return caller.tenantId() == null
                && caller.requestedTenantId() == null
                && caller.callerId() == null
                && caller.ownerId() == null
                && !caller.superTenant()
                && !caller.superOwner()
                && (caller.authorities() == null || caller.authorities().isEmpty());
    }

    static String resolveOperationLabel(IOperationRequest request) {
        return request.arg(IOperationRequest.OPERATION)
                .map(op -> op.getBusinessOperation())
                .map(bo -> bo.getLabel())
                .orElse("unknown");
    }

    /**
     * Recovers the functional exception the script-side guard would have thrown, by replaying the
     * same check Java-side. A stage script doing {@code ! -> CODE} ends with a non-zero code, but
     * core's {@code Workflow.execute} clears {@code lastException} on non-aborted exits — so the
     * original message never reaches {@code WorkflowResult.exceptionMessage()}. Stage-based and
     * inference-based replay reproduce the exact {@link ApiException}; a synthesized fallback carries
     * the best message we can build from the stage hint and response code.
     */
    static Throwable recoverFunctionalException(WorkflowResult result, IOperationRequest request,
            String opLabel, String domainName) {
        Integer code = result.code();
        String stage = findFailingStage(result).orElse(null);

        // 1) Stage-based replay — only works when core's collectVariables surfaces the matching
        //    _<stage>_<script>_code variable (broken for dashed-name stages; see tryReplayValidator).
        Throwable replayed = tryReplayValidator(stage, code, request);
        if (replayed != null) {
            return replayed;
        }

        // 2) Inference-based replay — independent of which stage variable the engine surfaced. Looks
        //    at the operation's access requirements + the caller's state and invokes the same guard.
        Throwable inferred = tryReplayFromRequestState(code, request);
        if (inferred != null) {
            return inferred;
        }

        // 3) Fallback: synthesize an ApiException with the most informative message from stage + code.
        String msg = nonBlank(result.exceptionMessage())
                .orElseGet(() -> functionalMessage(stage, code, opLabel, domainName));
        return new ApiException(msg);
    }

    /**
     * Replays the validator matching the request's state (caller + operation access requirements),
     * returning the thrown exception so the caller hands it straight back. Complements
     * {@link #tryReplayValidator}: this reads only the caller + operation, so it works regardless of
     * per-stage code-variable visibility.
     */
    static Throwable tryReplayFromRequestState(Integer code, IOperationRequest request) {
        if (request == null || code == null || code != 400) {
            return null;
        }
        ICaller caller = request.caller();
        OperationDefinition op = request.arg(IOperationRequest.OPERATION).orElse(null);
        if (op == null) {
            return null;
        }
        Access access = op.access();
        boolean needsOwner = access == Access.authenticated;
        boolean needsTenant = needsOwner || access == Access.authenticated;
        try {
            if (needsOwner && (caller == null || caller.ownerId() == null)) {
                SecurityExpressions.requireOwnerId(caller);
            }
            if (needsTenant && (caller == null || caller.tenantId() == null)) {
                SecurityExpressions.requireTenantId(caller);
            }
        } catch (RuntimeException replayed) {
            return replayed;
        }
        return null;
    }

    /**
     * Attempts to replay the validation the named stage's script would have performed, returning the
     * thrown exception (or {@code null} when no replay is wired for this stage). This recovers the
     * lost functional message — the Java-side helper throws exactly the {@link ApiException} the
     * script would have surfaced before {@code ! -> CODE} ate it.
     */
    static Throwable tryReplayValidator(String stage, Integer code, IOperationRequest request) {
        if (stage == null || code == null || request == null) {
            return null;
        }
        ICaller caller = request.caller();
        if (caller == null) {
            return null;
        }
        try {
            if (stage.startsWith("owner_rules") && code == 400) {
                SecurityExpressions.requireOwnerId(caller);
                return null;
            }
            if (stage.startsWith("tenant_rules") && code == 400) {
                SecurityExpressions.requireTenantId(caller);
                return null;
            }
        } catch (RuntimeException replayed) {
            // This IS the original exception — same class, same message.
            return replayed;
        }
        return null;
    }

    /**
     * Synthesizes a message-only fallback when replay isn't possible: the stage-aware hint when
     * available, else the per-code default.
     */
    static String functionalMessage(String stage, Integer code, String opLabel, String domainName) {
        String hint = stageFunctionalHint(stage, code);
        if (hint != null) {
            return hint + " — '" + opLabel + "' on '" + domainName + "'"
                    + (code != null ? " (code " + code + ")" : "");
        }
        return defaultMessageForCode(code, opLabel, domainName);
    }

    /**
     * Scans the workflow variables for the first non-zero per-stage code. Variables of the form
     * {@code _<stage>_<script>_code} are populated by core's {@code Workflow.collectVariables} on
     * every stage execution, regardless of the parent workflow's outcome.
     */
    static Optional<String> findFailingStage(WorkflowResult result) {
        if (result == null || result.variables() == null) {
            return Optional.empty();
        }
        return result.variables().entrySet().stream()
                .filter(e -> e.getKey() != null
                        && e.getKey().startsWith("_")
                        && e.getKey().endsWith("_code"))
                .filter(e -> e.getValue() instanceof Integer i && i != 0)
                .map(Map.Entry::getKey)
                // Strip leading underscore and trailing "_code", keep the raw "<stage>_<script>" body
                // so the hint can match prefixes even when the script name differs from the stage name.
                .map(k -> k.substring(1, k.length() - "_code".length()))
                .findFirst();
    }

    /**
     * Translates a sanitized stage identifier into a functional sentence, or {@code null} for unknown
     * stages (the caller then uses the generic per-code fallback).
     */
    static String stageFunctionalHint(String stageKey, Integer code) {
        if (stageKey == null) {
            return null;
        }
        // Stage names are slugified by the workflow engine ("-" → "_"); match against the prefix
        // because <stage>_<script> is collapsed (e.g. "owner_rules_owner_rules").
        if (stageKey.startsWith("verify_authorization")) {
            return code != null && code == 401
                    ? "Authorization required (token missing, malformed, or rejected)"
                    : "Authorization verification failed";
        }
        if (stageKey.startsWith("verify_tenant")) {
            return "Tenant verification failed — caller's tenantId does not match the request";
        }
        if (stageKey.startsWith("verify_owner")) {
            return "Owner verification failed — caller is not the owner of the resource";
        }
        if (stageKey.startsWith("verify_authority")) {
            return "Authority check failed — caller lacks the required authority";
        }
        if (stageKey.startsWith("tenant_rules")) {
            return "Tenant rules failed — required tenantId missing on the caller";
        }
        if (stageKey.startsWith("owner_rules")) {
            return "Owner rules failed — required ownerId missing on the caller";
        }
        return null;
    }

    /**
     * Generic per-code fallback used when no failing stage can be identified. Still names the
     * operation and domain for context.
     */
    static String defaultMessageForCode(Integer code, String opLabel, String domainName) {
        if (code == null) {
            return "Operation '" + opLabel + "' on domain '" + domainName + "' failed";
        }
        return switch (code) {
            case 400 -> "Bad request — '" + opLabel + "' on '" + domainName
                    + "' rejected by validation";
            case 401 -> "Authorization required to perform '" + opLabel
                    + "' on '" + domainName + "'";
            case 403 -> "Forbidden — caller lacks the privilege to perform '"
                    + opLabel + "' on '" + domainName + "'";
            case 404 -> "Not found — no matching resource for '" + opLabel
                    + "' on '" + domainName + "'";
            case 409 -> "Conflict — '" + opLabel + "' on '" + domainName
                    + "' could not be applied to the current state";
            default -> "Operation '" + opLabel + "' on '" + domainName
                    + "' failed with code " + code;
        };
    }
}
