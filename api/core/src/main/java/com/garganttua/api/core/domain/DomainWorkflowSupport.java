package com.garganttua.api.core.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.security.IDomainSecurityBuilder;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.api.core.security.DomainSecurityBuilder;
import com.garganttua.api.core.security.authenticator.AuthenticatorBuilder;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowTimingConfig;
import com.garganttua.core.workflow.dsl.IWorkflowsBuilder;

/**
 * Workflow registration/assembly helpers for {@link DomainBuilder}: auto-registers the
 * security-driven operations (authenticate / refreshAuthorization) and drives the
 * {@link DomainWorkflowAssembler} stage population. Extracted from {@code DomainBuilder} to keep
 * that wide-interface builder under the file-size gate while preserving identical behaviour.
 */
final class DomainWorkflowSupport {

    /** The default CRUD operation labels seeded onto every domain at construction. */
    static final List<String> CRUD_OPERATION_LABELS = List.of(
            BusinessOperation.create.getLabel(),
            BusinessOperation.readAll.getLabel(),
            BusinessOperation.readOne.getLabel(),
            BusinessOperation.update.getLabel(),
            BusinessOperation.deleteOne.getLabel(),
            BusinessOperation.deleteAll.getLabel());

    private DomainWorkflowSupport() {
    }

    /** Validates the domain builder's parent is an {@link ApiBuilder} and returns it. */
    static ApiBuilder requireApiBuilderParent(Object parent, String domainName) throws ApiException {
        if (!(parent instanceof ApiBuilder ab)) {
            throw new ApiException("DomainBuilder's parent is not an ApiBuilder — "
                    + "cannot retrieve the built workflow for domain '" + domainName + "'.");
        }
        return ab;
    }

    /** Returns the parent's workflows builder, failing fast when none was provided. */
    static IWorkflowsBuilder requireWorkflowsBuilder(ApiBuilder parent) throws ApiException {
        IWorkflowsBuilder wb = parent.getWorkflowsBuilder();
        if (wb == null) {
            throw new ApiException("IWorkflowsBuilder not provided to ApiBuilder — "
                    + "Bootstrap should auto-discover it via WorkflowsBuilderFactory, or "
                    + "the caller should provide() one explicitly before build().");
        }
        return wb;
    }

    /** Builds the named workflow, wrapping DSL failures in an {@link ApiException}. */
    static IWorkflow buildNamedWorkflow(IWorkflowsBuilder wb, String domainName) throws ApiException {
        try {
            return wb.workflow(domainName).build();
        } catch (DslException e) {
            throw new ApiException("Failed to build workflow for domain '"
                    + domainName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Auto-registers the authenticate and refreshAuthorization workflows when the domain's security
     * configuration requires them, using the supplied registrar to materialise CRUD metadata.
     */
    @SuppressWarnings("unchecked")
    static <E> void autoRegisterSecurityWorkflows(IDomainSecurityBuilder<E> securityBuilder,
            Map<String, DomainWorkflowBuilder<E>> workflows, Consumer<String> registrar) {
        if (securityBuilder == null) {
            return;
        }
        DomainSecurityBuilder<E> sec = (DomainSecurityBuilder<E>) securityBuilder;
        if (!sec.hasAuthenticator()) {
            return;
        }
        // Auto-register authenticate workflow when domain has an authenticator
        if (!workflows.containsKey(BusinessOperation.authenticate.getLabel())) {
            registrar.accept(BusinessOperation.authenticate.getLabel());
        }
        // Auto-register refreshAuthorization workflow when the authenticator has an authorization
        // config and the linked authorization is refreshable. The runtime guard inside
        // REFRESH_AUTHORIZATION.gs additionally checks isAuthorizationRefreshable so a
        // misconfiguration just rejects requests.
        if (((AuthenticatorBuilder<E>) sec.getAuthenticator()).hasAuthorizationConfig()
                && !workflows.containsKey(BusinessOperation.refreshAuthorization.getLabel())) {
            registrar.accept(BusinessOperation.refreshAuthorization.getLabel());
        }
    }

    /**
     * Derives the security flags from the builder and populates the domain's workflow stages into
     * the shared workflows builder.
     */
    @SuppressWarnings("unchecked")
    static <E> void assembleStages(String domainName,
            IDomainSecurityBuilder<E> securityBuilder,
            Map<String, DomainWorkflowBuilder<E>> workflows,
            Set<String> useCaseNames, ObjectAddress owner, ObjectAddress owned,
            boolean multiTenancyEnabled,
            IInjectionContextBuilder injectionContextBuilder,
            IWorkflowsBuilder workflowsBuilder, WorkflowTimingConfig workflowTimingConfig)
            throws ApiException {
        boolean securityEnabled = securityBuilder != null
                && ((DomainSecurityBuilder<E>) securityBuilder).isSecurityEnabled();
        boolean hasAuthorization = securityBuilder != null
                && ((DomainSecurityBuilder<E>) securityBuilder).hasAuthenticator()
                && ((AuthenticatorBuilder<E>) ((DomainSecurityBuilder<E>) securityBuilder).getAuthenticator()).hasAuthorizationConfig();
        boolean isOwnerOrOwned = owner != null || owned != null;

        new DomainWorkflowAssembler<E>(
                domainName, workflows, useCaseNames, securityEnabled, hasAuthorization,
                multiTenancyEnabled, isOwnerOrOwned,
                injectionContextBuilder,
                workflowsBuilder, workflowTimingConfig).populateStages();
    }
}
