package com.garganttua.api.core.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IAuthoritiesEndpoint;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.protocol.IProtocol;
import com.garganttua.api.commons.security.authorization.IAuthorizationProtocol;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.core.domain.DomainBuilder;
import com.garganttua.core.observability.IObservable;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservabilityBinding;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.core.workflow.IWorkflow;

/**
 * Mechanical build-step helpers for {@link ApiBuilder#doBuild()}: builds the per-domain contexts,
 * startup binders, serializers / protocols / authorization-protocols pools, the authorities endpoint,
 * and wires observability. Extracted from {@code ApiBuilder} to keep that wide builder under the
 * file-size gate; behaviour is identical.
 */
final class ApiBuilderBuild {

    private static final Logger log = Logger.getLogger(ApiBuilderBuild.class);

    private ApiBuilderBuild() {
    }

    /**
     * Full {@link com.garganttua.api.commons.context.IApi} assembly: validates wiring, builds the
     * domain contexts / startup binders / asset pools / authorities endpoint, builds the security
     * context, constructs the {@link Api} and wires observability. Mirrors the original
     * {@code ApiBuilder.doBuild()} exactly.
     */
    static com.garganttua.api.commons.context.IApi assembleApi(ApiBuilder b) throws ApiException {
        log.trace("Entering doBuild() method");
        try {
            requireInjectionContext(b);
            ApiBuilderRegistration.registerMapperBean(b.injectionContext);

            Map<String, IDomain<?>> domainContexts = buildDomainContexts(b);
            requireTenantDomainWhenMultiTenant(b, domainContexts);

            // Validate that every domain whose linked authorization is signable also has a key
            // configured. Surfaces misconfiguration at build time rather than at the first sign call.
            ApiBuilderValidation.validateSignableKeyConfig(domainContexts);

            if (b.securityBuilder != null) {
                b.securityBuilder.build();
                log.debug("Built security context");
            }

            List<IMethodBinder<Void>> startupBinders = buildStartupBinders(b);

            Api apiContext = new Api(b.injectionContext,
                    domainContexts, b.superTenantId, b.superTenantAutoCreate, b.multiTenant,
                    b.lockSuperTenantCreation, b.lockSuperOwnerCreation,
                    startupBinders, buildSerializers(b), buildProtocols(b),
                    buildAuthorizationProtocols(b), buildAuthoritiesEndpoint(b));
            apiContext.adoptAutoConfigurationResources(b.autoConfigResources);

            attachObservability(b, domainContexts);

            log.debug("Built Api with {} domains", domainContexts.size());
            log.trace("Exiting doBuild() method");
            return apiContext;
        } catch (ApiException e) {
            throw new ApiException("Failed to build API context: " + e.getMessage(), e);
        }
    }

    /** Fails the build with concrete wiring guidance when no injection context was provided. */
    private static void requireInjectionContext(ApiBuilder b) throws ApiException {
        // The caller is responsible for wiring it, either by registering an IInjectionContextBuilder
        // into the Bootstrap that drives this ApiBuilder, or by calling
        // ((IDependentBuilder) apiBuilder).provide(builder) before .build().
        if (b.injectionContext == null) {
            throw new ApiException(
                    "InjectionContext is required but no IInjectionContextBuilder was provided.\n"
                    + "\n"
                    + "Register one on the Bootstrap that drives ApiBuilder:\n"
                    + "    bootstrap.withBuilder(injectionContextBuilder);\n"
                    + "\n"
                    + "Or wire it directly on the ApiBuilder:\n"
                    + "    ((IDependentBuilder) apiBuilder).provide(injectionContextBuilder);\n");
        }
    }

    /** Refuses the build when multi-tenancy is on but no domain is marked as the tenant. */
    private static void requireTenantDomainWhenMultiTenant(ApiBuilder b,
            Map<String, IDomain<?>> domainContexts) throws ApiException {
        if (b.multiTenant && domainContexts.values().stream().noneMatch(IDomain::isTenantEntity)) {
            throw new ApiException(
                    "Multi-tenancy is enabled but no domain is marked as tenant. "
                    + "Use .tenant(true) on a domain or disable multi-tenancy with .multiTenant(false)");
        }
    }

    /**
     * Contribution to {@code WorkflowsBuilder}'s pre-build registry, run at CONFIGURATION stage
     * (topo orders WorkflowsBuilder before ApiBuilder). Runs the api's own auto-detection scanners
     * first so annotation-driven domains/security are registered before stage assembly, then asks
     * every domain builder to assemble its workflow stages into the shared builder. {@code doBuild()}
     * later retrieves each built workflow from {@code WorkflowsBuilder.build()}.
     */
    static void populateWorkflowStagesAtConfiguration(ApiBuilder b,
            com.garganttua.core.workflow.dsl.IWorkflowsBuilder workflowsBuilder)
            throws com.garganttua.core.dsl.DslException {
        try {
            if (b.isAutoDetected()) {
                b.doAutoDetection();
            }
            for (DomainBuilder<?> domainBuilder : b.domainBuilders.values()) {
                domainBuilder.populateWorkflowStages(
                        workflowsBuilder,
                        b.injectionContextBuilder,
                        b.expressionContextBuilder,
                        b.multiTenant,
                        b.workflowTimingConfig);
            }
        } catch (ApiException e) {
            throw new com.garganttua.core.dsl.DslException(
                    "Failed to contribute workflows to IWorkflowsBuilder at CONFIGURATION: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Configures a captured expression context: enables auto-detect and registers the framework
     * function packages (core expression / script / observability + api expression). The
     * observability package is required so script-side {@code observe("start"|"end", source)} markers
     * (emitted by ScriptGenerator when workflowTiming is enabled) resolve against
     * ObservabilityExpressions; without it those calls stay unresolved and the events never reach
     * observers.
     */
    static void configureExpressionContext(
            com.garganttua.core.expression.dsl.IExpressionContextBuilder builder) {
        if (!builder.isAutoDetected()) {
            builder.autoDetect(true);
        }
        builder.withPackage("com.garganttua.core.expression.functions");
        builder.withPackage("com.garganttua.core.script.functions");
        builder.withPackage("com.garganttua.core.observability");
        builder.withPackage("com.garganttua.api.core.expression");
    }

    /** Builds every declared domain context, attaching the starter default interface where needed. */
    static Map<String, IDomain<?>> buildDomainContexts(ApiBuilder b) throws ApiException {
        Map<String, IDomain<?>> domainContexts = new HashMap<>();
        for (DomainBuilder<?> domainBuilder : b.domainBuilders.values()) {
            // A starter-registered default interface (e.g. Javalin) is attached to every domain that
            // did not declare one explicitly, so the normal lifecycle (handle + onStart) exposes it.
            // An explicit .interfasse(...) always wins.
            if (b.defaultInterface != null && !domainBuilder.hasInterfaces()) {
                domainBuilder.interfasse(b.defaultInterface);
            }
            domainBuilder.setDependencyBuilders(b.injectionContextBuilder, b.expressionContextBuilder);
            IDomain<?> domainContext = domainBuilder.build();
            domainContexts.put(domainContext.getDomain(), domainContext);
            log.debug("Built domain context: {}", domainContext.getDomain());
        }
        return domainContexts;
    }

    /** Builds the API-level startup method binders. */
    static List<IMethodBinder<Void>> buildStartupBinders(ApiBuilder b) throws ApiException {
        List<IMethodBinder<Void>> startupBinders = new ArrayList<>();
        for (ApiStartupBinderBuilder binder : b.startupBinderBuilders) {
            startupBinders.add(binder.build());
        }
        log.debug("Built {} startup binders", startupBinders.size());
        return startupBinders;
    }

    /** Builds the serializers, combining directly-registered instances with supplier-built ones. */
    static List<ISerializer> buildSerializers(ApiBuilder b) throws ApiException {
        List<ISerializer> built = new ArrayList<>(b.serializers);
        for (ISupplierBuilder<?, ? extends ISupplier<?>> sb : b.serializerBuilders) {
            built.add((ISerializer) sb.build().supply().orElse(null));
        }
        log.debug("Built {} serializers", built.size());
        return built;
    }

    /** Builds the protocols, combining directly-registered instances with supplier-built ones. */
    static List<IProtocol<?, ?>> buildProtocols(ApiBuilder b) throws ApiException {
        List<IProtocol<?, ?>> built = new ArrayList<>(b.protocols);
        for (ISupplierBuilder<?, ? extends ISupplier<?>> pb : b.protocolBuilders) {
            built.add((IProtocol<?, ?>) pb.build().supply().orElse(null));
        }
        log.debug("Built {} protocols", built.size());
        return built;
    }

    /** Builds the authorization protocols, combining registered instances with supplier-built ones. */
    static List<IAuthorizationProtocol> buildAuthorizationProtocols(ApiBuilder b) throws ApiException {
        List<IAuthorizationProtocol> built = new ArrayList<>(b.authorizationProtocols);
        for (ISupplierBuilder<?, ? extends ISupplier<?>> ab : b.authorizationProtocolBuilders) {
            built.add((IAuthorizationProtocol) ab.build().supply().orElse(null));
        }
        log.debug("Built {} authorization protocols", built.size());
        return built;
    }

    /**
     * Builds the authorities-endpoint descriptor when opted-in via {@code .exposeAuthorities()};
     * null otherwise (the Api context then refuses every authorities call).
     */
    static IAuthoritiesEndpoint buildAuthoritiesEndpoint(ApiBuilder b) throws ApiException {
        return b.authoritiesEndpointBuilder != null ? b.authoritiesEndpointBuilder.build() : null;
    }

    /**
     * Attaches each domain (and its observable workflow) to the bootstrap-wired ObservabilityBinding
     * so @Observer-scanned observers see api:operation:&lt;domain&gt;:&lt;op&gt; events and the
     * workflow timing markers. No-op when no observability builder/binding was provided or when
     * timing is disabled (core's hasObservers() short-circuit).
     */
    static void attachObservability(ApiBuilder b, Map<String, IDomain<?>> domainContexts) {
        if (b.observabilityBuilder == null) {
            return;
        }
        ObservabilityBinding binding = b.observabilityBuilder.getBinding();
        if (binding == null) {
            return;
        }
        for (IDomain<?> domain : domainContexts.values()) {
            binding.attachSource(domain);
            IWorkflow wf = domain.getWorkflow();
            if (wf instanceof IObservable wfObs) {
                binding.attachSource(wfObs);
            }
        }
        log.debug("Attached {} domain(s) and their workflows to the ObservabilityBinding",
                domainContexts.size());
    }
}
