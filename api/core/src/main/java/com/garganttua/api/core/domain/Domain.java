package com.garganttua.api.core.domain;

import com.garganttua.api.core.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.garganttua.api.core.service.OperationResponse;
import com.garganttua.api.core.service.RequestBuilder;
import com.garganttua.api.core.repository.Repository;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.commons.context.DomainSyncDef;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.core.injection.BeanDefinition;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.api.commons.security.IDomainSecurityContext;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IRequestBuilder;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservabilityEmitter;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.ObservableRegistry;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.workflow.IWorkflow;
import com.garganttua.core.workflow.WorkflowExecutionOptions;
import com.garganttua.core.workflow.WorkflowInput;
import com.garganttua.core.workflow.WorkflowResult;
import com.github.f4b6a3.uuid.UuidCreator;
import com.garganttua.core.observability.Logger;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals"})
public class Domain<E> extends AbstractLifecycle implements IDomain<E> {
	private static final Logger log = Logger.getLogger(Domain.class);

    final DomainDefinition<E> domainDefinition;
    private final List<ISupplier<IInterface>> interfaces;
    private final List<ISupplier<IEventPublisher>> events;
    private final IDomainSecurityContext domainSecurityContext;

    private final IEntityContext<E> entityContext;
    private final List<IDtoContext<?>> dtoContexts;
    final IRepository repository;

    @Override
    public IRepository getRepository() { return this.repository; }

    // Single workflow handling the full pipeline (business → security → execution)
    private IWorkflow workflow;

    public List<ISupplier<IInterface>> getInterfaces() { return interfaces; }
    public List<ISupplier<IEventPublisher>> getEvents() { return events; }

    // Bean definition for runtime DI injection on entities
    private BeanDefinition<?> entityBeanDefinition;
    private boolean doInjection;

    public BeanDefinition<?> getEntityBeanDefinition() { return this.entityBeanDefinition; }
    public boolean isDoInjection() { return this.doInjection; }

    private IApi apiContext;

    /**
     * Local registry for {@code api:operation:*} ObservableEvents emitted by
     * {@link #invoke(IOperationRequest)}. The bootstrap-wired {@code ObservabilityBuilder} subscribes
     * every {@code @Observer}-scanned observer onto it via {@code ObservabilityBinding.attachSource}.
     */
    private final ObservableRegistry observableRegistry = new ObservableRegistry();

    public void setApi(IApi apiContext) {
        this.apiContext = apiContext;
    }

    @Override
    public void addObserver(IObserver<ObservableEvent> observer) {
        this.observableRegistry.addObserver(observer);
    }

    @Override
    public void removeObserver(IObserver<ObservableEvent> observer) {
        this.observableRegistry.removeObserver(observer);
    }

    @Override
    public IApi getApiContext() {
        return this.apiContext;
    }

    @Override
    public boolean isMultiTenant() {
        return this.apiContext != null && this.apiContext.isMultiTenant();
    }

    public void setEntityBeanDefinition(BeanDefinition<?> entityBeanDefinition) {
        this.entityBeanDefinition = entityBeanDefinition;
    }

    public void setDoInjection(boolean doInjection) {
        this.doInjection = doInjection;
    }

    public void setWorkflow(IWorkflow workflow) {
        this.workflow = Objects.requireNonNull(workflow, "Workflow cannot be null");
    }

    // Write-synchronization: config declared via the DSL, plus the core mutex manager and the injection
    // context wired by ApiBuilderBuild. All stay null when the domain declares no .synchronization(...).
    // The injection context resolves a bean-based lock (synchronization(IMutex)/(ISupplierBuilder)/
    // synchronizationBean); the mutex manager resolves a name-based lock.
    private DomainSyncDef synchronization;
    private IMutexManager mutexManager;
    private IInjectionContext injectionContext;

    public void setSynchronization(DomainSyncDef synchronization) {
        this.synchronization = synchronization;
    }

    public void setMutexManager(IMutexManager mutexManager) {
        this.mutexManager = mutexManager;
    }

    public void setInjectionContext(IInjectionContext injectionContext) {
        this.injectionContext = injectionContext;
    }

    /**
     * True when this domain declares synchronization (a non-blank name {@code lock} or a bean
     * {@code lockBean}); write ops then serialize on the resolved mutex.
     */
    public boolean hasSynchronization() {
        if (this.synchronization == null) {
            return false;
        }
        boolean hasLock = this.synchronization.lock() != null && !this.synchronization.lock().isBlank();
        boolean hasBean = this.synchronization.lockBean() != null && !this.synchronization.lockBean().isBlank();
        return hasLock || hasBean;
    }

    public Domain(DomainDefinition<E> domainDefinition, IEntityContext<E> entityContext,
            IDomainSecurityContext domainSecurityContext,
            List<IDtoContext<?>> dtoContexts,
            List<ISupplier<IInterface>> interfaces,
            List<ISupplier<IEventPublisher>> events
            ) {
        this.domainSecurityContext = Objects.requireNonNull(domainSecurityContext,
                "Domain security context cannot be null");
        this.entityContext = Objects.requireNonNull(entityContext, "Entity context cannot be null");
        this.domainDefinition = Objects.requireNonNull(domainDefinition, "Domain definition cannot be null");
        this.interfaces = Collections.unmodifiableList(new java.util.ArrayList<>(
                Objects.requireNonNull(interfaces, "Interfaces cannot be null")));
        this.events = Collections.unmodifiableList(new java.util.ArrayList<>(
                Objects.requireNonNull(events, "Events cannot be null")));
        this.dtoContexts = Collections.unmodifiableList(new java.util.ArrayList<>(
                Objects.requireNonNull(dtoContexts, "Dto contexts cannot be null")));
        
        Repository repo = new Repository(this.dtoContexts, entityContext.getEntityClass());
        repo.setDomain(this);
        this.repository = repo;
    }

    @Override
    public IReflection reflection() {
        return DefaultMapper.reflection();
    }

    @Override
    protected ILifecycle doInit() {
        log.info("Initializing domain context: {}", this.domainDefinition.domainName());

        // 1. Log workflow
        log.debug("Workflow configured for domain {}: {}", this.domainDefinition.domainName(),
                this.workflow != null ? this.workflow.getName() : "none");

        // 2. Hand each DAO its domain definition (no-op for DAOs that don't care).
        DomainInterfaceSupport.registerDomainOnDaos(this.dtoContexts, this.domainDefinition);

        // 3. Build interfaces, pass domain context (with access rules), and init them.
        forEachInterface(IInterface::handle, this);
        forEachInterface(IInterface::onInit);
        log.debug("Initialized {} interfaces for domain {}", this.interfaces.size(),
                this.domainDefinition.domainName());

        // 4. Bridge each .events(...) publisher onto the observable registry so business IEvents
        //    flow out through the same fan-out as telemetry.
        DomainInterfaceSupport.initializeEvents(this.events, this.observableRegistry,
                this.domainDefinition.domainName());

        return this;
    }

    private void forEachInterface(java.util.function.Consumer<IInterface> action) {
        DomainInterfaceSupport.forEach(this.interfaces, this.domainDefinition.domainName(), action);
    }

    private <T> void forEachInterface(java.util.function.BiConsumer<IInterface, T> action, T arg) {
        DomainInterfaceSupport.forEach(this.interfaces, this.domainDefinition.domainName(), action, arg);
    }

    @Override
    protected ILifecycle doStart() {
        log.info("Starting domain context: {}", this.domainDefinition.domainName());

        DomainStartupExecutor<E> startup = new DomainStartupExecutor<>(this);
        startup.executeStartupBinders();              // 1. startup binders
        startup.createStartupEntities();              // 2. createEntity(...) — best-effort
        startup.upsertStartupEntities();              // 3. upsertEntity(...) — fail-fast
        forEachInterface(IInterface::onStart);      // 4. start interfaces

        log.debug("Started {} interfaces for domain {}", this.interfaces.size(),
                this.domainDefinition.domainName());

        return this;
    }

    @Override
    protected ILifecycle doStop() {
        log.info("Stopping domain context: {}", this.domainDefinition.domainName());

        // Stop all interfaces
        forEachInterface(IInterface::onStop);
        log.debug("Stopped {} interfaces for domain {}", this.interfaces.size(),
                this.domainDefinition.domainName());

        return this;
    }

    @Override
    protected ILifecycle doFlush() {
        log.info("Flushing domain context: {}", this.domainDefinition.domainName());
        // Flush all interfaces
        forEachInterface(IInterface::onFlush);
        log.debug("Flushed {} interfaces for domain {}", this.interfaces.size(),
                this.domainDefinition.domainName());
        return this;
    }

    @Override
    public IDomainDefinition<E> getDomainDefinition() {
        return this.domainDefinition;
    }

    @Override
    public IRequestBuilder request() {
        return RequestBuilder.builder(this);
    }

    @Override
    public IOperationResponse invoke(IOperationRequest request) {
        return invoke(request, WorkflowExecutionOptions.none());
    }

    @Override
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
            justification = "Observability pattern: the original RuntimeException is rethrown unchanged "
                    + "after firing the api:operation Error event, to propagate the failure to the caller.")
    public IOperationResponse invoke(IOperationRequest request, WorkflowExecutionOptions options) {
        ensureStarted();
        long startNanos = System.nanoTime();

        // Fast path: when nothing subscribed to our registry AND nothing is on the process-global
        // firehose, skip the emit scope entirely and call doInvoke directly. Both must be empty —
        // event connectors (and other cross-cutting sinks) self-register on GlobalObservers, not on
        // this Domain's local registry, so checking only the local registry would silently drop
        // api:operation:* events for them. Production traffic that opted out of @Observer with no
        // firehose sink pays no overhead beyond the two hasObservers() short-circuits.
        if (!this.observableRegistry.hasObservers() && !GlobalObservers.hasObservers()) {
            OperationResponse response = doInvoke(request, options);
            return response.withProcessingTime(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
        }

        // Slow path: emit api:operation:<domain>:<op> Start/End/Error onto
        // the local registry via ObservabilityEmitter, so the bootstrap-wired
        // ObservabilityBinding's observers all see correlated events. The
        // executionId pinned here is also bound on the request so doInvoke
        // and the nested workflow share it.
        java.util.UUID executionUuid = UuidCreator.getTimeOrderedEpoch();
        request.arg(IOperationRequest.EXECUTION_UUID, executionUuid);

        OperationDefinition operation =
                ((java.util.Optional<OperationDefinition>)
                        request.arg(IOperationRequest.OPERATION)).orElse(null);
        String source = "api:operation:" + this.domainDefinition.domainName()
                + ":" + (operation != null ? operation.toString() : "<no-op>");

        java.util.Date inDate = new java.util.Date();
        try (var scope = ObservabilityEmitter.open(this.observableRegistry, executionUuid)) {
            scope.fireStart(source);
            try {
                OperationResponse response = doInvoke(request, options);
                java.time.Duration duration =
                        java.time.Duration.ofNanos(System.nanoTime() - startNanos);
                Integer code = response.getResponseCode() != null
                        ? response.getResponseCode().ordinal() : null;
                // Carry the rich business IEvent as the End-event payload so the
                // .events(...) publishers (bridged as observers) receive it.
                scope.fireEnd(source, code,
                        DomainEventBuilder.buildEvent(request, operation, inDate, response, null));
                return response.withProcessingTime(duration);
            } catch (RuntimeException e) {
                scope.fireError(source, e,
                        DomainEventBuilder.buildEvent(request, operation, inDate, null, e));
                throw e;
            }
        }
    }

    OperationResponse doInvoke(IOperationRequest request, WorkflowExecutionOptions options) {
        if (this.workflow == null) {
            log.warn("No workflow configured for domain {}", this.domainDefinition.domainName());
            return OperationResponse.notAvailable("No workflow configured for domain: " + this.domainDefinition.domainName());
        }

        try {
            // Only generate a UUID when the caller (or the observability path
            // above) didn't already set one — keeps the start/end events
            // correlatable and lets a transport set a request-id of its own.
            @SuppressWarnings("unchecked")
            java.util.Optional<java.util.UUID> existing =
                    (java.util.Optional<java.util.UUID>) request.arg(IOperationRequest.EXECUTION_UUID);
            if (existing.isEmpty()) {
                request.arg(IOperationRequest.EXECUTION_UUID, UuidCreator.getTimeOrderedEpoch());
            }
            request.arg(IOperationRequest.API_CONTEXT, this.apiContext);
            request.arg(IOperationRequest.DOMAIN_CONTEXT, this);
            request.arg(IOperationRequest.REPOSITORY, this.repository);

            OperationResponse callerError = resolveAndBindCaller(request);
            if (callerError != null) {
                return callerError;
            }
            bindScriptArgs(request);

            // Write ops (create/update/delete) on a domain declaring .synchronization(...) run inside
            // the core mutex; reads and unsynchronized domains run the pipeline directly.
            return DomainSynchronization.execute(this.mutexManager, this.injectionContext,
                    this.synchronization, request, this.domainDefinition.domainName(), () -> {
                        WorkflowResult result = this.workflow.execute(
                                WorkflowInput.of(request, buildWorkflowParams()),
                                effectiveOptions(request, options));
                        return mapWorkflowResult(result, request);
                    });
        } catch (Exception e) {
            String domainName = this.domainDefinition.domainName();
            // A failure raised inside the synchronization mutex is wrapped as MutexException; unwrap it
            // so the response carries the original cause, identical to the unsynchronized path.
            Throwable cause = (e instanceof MutexException && e.getCause() != null) ? e.getCause() : e;
            log.error("Error executing workflow for domain {}: {}", domainName, cause.getMessage(), cause);
            return OperationResponse.error(new ApiException(
                    "Workflow execution error on domain '" + domainName + "': " + cause.getMessage(), cause));
        }
    }

    /**
     * Resolves the effective caller and binds it as the {@code caller} arg. Returns a bad-request
     * {@link OperationResponse} when the caller is invalid (in multi-tenant mode, carries information
     * but no tenantId), else {@code null}.
     */
    private OperationResponse resolveAndBindCaller(IOperationRequest request) {
        ICaller caller = request.caller();
        if (DomainInvocationDiagnostics.isEmptyCaller(caller)) {
            // No caller information at all — materialize a best-effort anonymous one; let
            // VERIFY_AUTHORIZATION decide (anonymous ops pass, non-anonymous get a clean 401).
            caller = DomainInvocationDiagnostics.autoCreateCallerFromBody(request);
        } else if (caller.tenantId() == null && isMultiTenant()) {
            // Multi-tenant only: a caller carrying SOME information (super flags, ownerId, callerId)
            // but no tenantId is almost always a misuse (e.g. the deprecated no-arg
            // createSuperCaller()). Reject explicitly rather than risk silent under-isolation. In
            // NON-tenant mode an owner-scoped caller with no tenant is legitimate.
            return OperationResponse.badRequest(new ApiException(
                    "Caller is missing tenantId — super and owner flags require a "
                            + "tenantId binding (use Caller.createSuperCaller(superTenantId) "
                            + "or Caller.createTenantCaller(tenantId))"));
        }
        request.arg("caller", caller);
        return null;
    }

    /** Maps transport arg shapes to the script-side names the stage scripts expect. */
    private void bindScriptArgs(IOperationRequest request) {
        // Map "body" to "entity" for script compatibility
        request.arg(IOperationRequest.BODY).ifPresent(body -> request.arg("entity", body));

        // Map "entityUuid" to the single-entity lookup args ("identifier"/"type") that
        // READ_ONE/UPDATE_ONE/DELETE_ONE.gs (→ buildGetOneFilter) read. Without this, a by-uuid fetch
        // sees a null identifier, builds no uuid clause, and silently degrades to match-all. Honour an
        // explicit "identifier"/"type" if already set.
        request.arg(IOperationRequest.ENTITY_UUID).ifPresent(uuid -> {
            if (request.arg("identifier").isEmpty()) {
                request.arg("identifier", uuid);
            }
            if (request.arg("type").isEmpty()) {
                request.arg("type", "uuid");
            }
        });
    }

    private Map<String, Object> buildWorkflowParams() {
        Map<String, Object> workflowParams = new java.util.LinkedHashMap<>();
        workflowParams.put("$1", this.repository);
        workflowParams.put("$2", this);
        workflowParams.put("$3", this.apiContext);
        return workflowParams;
    }

    /**
     * Correlates observability: runs the workflow under the same EXECUTION_UUID already pinned on the
     * request (used for the api:operation:* events) so the workflow's stage and script events share
     * it. Carries over existing filtering — executionId does not engage hasFiltering(), so the
     * precompiled cache stays hot.
     */
    private WorkflowExecutionOptions effectiveOptions(IOperationRequest request,
            WorkflowExecutionOptions options) {
        java.util.UUID execId = (java.util.UUID) request.arg(IOperationRequest.EXECUTION_UUID).orElse(null);
        if (execId == null) {
            return options;
        }
        return WorkflowExecutionOptions.builder()
                .startFrom(options.startFrom().orElse(null))
                .stopAfter(options.stopAfter().orElse(null))
                .skipStages(options.skipStages())
                .executionId(execId)
                .build();
    }

    /** Maps a finished {@link WorkflowResult} to the operation response (success / abort / failure). */
    private OperationResponse mapWorkflowResult(WorkflowResult result, IOperationRequest request) {
        String opLabel = DomainInvocationDiagnostics.resolveOperationLabel(request);
        String domainName = this.domainDefinition.domainName();

        if (result.isSuccess()) {
            return mapSuccessCode(opLabel, result.output());
        }
        if (result.hasAborted()) {
            // The workflow surfaced a Throwable directly — propagate it. Fallback synthesizes an
            // ApiException when the engine aborted with no exception (defensive).
            Throwable cause = result.exception()
                    .orElseGet(() -> new ApiException(
                            DomainInvocationDiagnostics.nonBlank(result.exceptionMessage()).orElseGet(() ->
                                    "Operation '" + opLabel + "' on domain '" + domainName
                                            + "' aborted unexpectedly")));
            log.error("Workflow aborted for domain {} op {}: {}", domainName, opLabel,
                    cause.getMessage(), cause);
            return OperationResponse.error(cause);
        }
        // Non-zero code with NO exception attached — the `! -> CODE` pattern in stage scripts catches
        // the functional exception and resets the script's lastException. Recovery order:
        //   1. recordCaughtException stashed the original Throwable under LAST_EXCEPTION_ARG; verbatim.
        //   2. else replay the script-side check Java-side when the failing stage is identifiable.
        //   3. else synthesise a message-only ApiException from stage + code.
        Throwable recorded = (Throwable) request
                .arg(com.garganttua.api.core.expression.SecurityAuthorizationExpressions.LAST_EXCEPTION_ARG)
                .filter(Throwable.class::isInstance)
                .orElse(null);
        Throwable functional = recorded != null
                ? recorded
                : DomainInvocationDiagnostics.recoverFunctionalException(result, request, opLabel, domainName);
        log.warn("Workflow returned code {} for domain {} op {}: {}",
                result.code(), domainName, opLabel, functional.getMessage());
        return mapWorkflowCode(result.code(), functional);
    }

    // Invocation-diagnostics delegators (logic in DomainInvocationDiagnostics); kept here as the
    // stable, unit-tested entry points referenced as Domain.<method> by the test suite.

    static ICaller autoCreateCallerFromBody(IOperationRequest request) {
        return DomainInvocationDiagnostics.autoCreateCallerFromBody(request);
    }

    static boolean isEmptyCaller(ICaller caller) {
        return DomainInvocationDiagnostics.isEmptyCaller(caller);
    }

    static Throwable tryReplayValidator(String stage, Integer code, IOperationRequest request) {
        return DomainInvocationDiagnostics.tryReplayValidator(stage, code, request);
    }

    static String functionalMessage(String stage, Integer code, String opLabel, String domainName) {
        return DomainInvocationDiagnostics.functionalMessage(stage, code, opLabel, domainName);
    }

    static java.util.Optional<String> findFailingStage(WorkflowResult result) {
        return DomainInvocationDiagnostics.findFailingStage(result);
    }

    static String stageFunctionalHint(String stageKey, Integer code) {
        return DomainInvocationDiagnostics.stageFunctionalHint(stageKey, code);
    }

    static String defaultMessageForCode(Integer code, String opLabel, String domainName) {
        return DomainInvocationDiagnostics.defaultMessageForCode(code, opLabel, domainName);
    }

    /**
     * Maps a successful workflow outcome to the response code that reflects the
     * operation — create → CREATED, update → UPDATED, delete → DELETED — so the
     * distinction survives to the transport (e.g. a POST create answers 201, not a
     * flat 200). Reads / authenticate / use-cases / unknown stay OK.
     */
    private OperationResponse mapSuccessCode(String opLabel, Object output) {
        return switch (opLabel) {
            case "create" -> OperationResponse.created(output);
            case "update" -> OperationResponse.updated(output);
            case "deleteOne", "deleteAll" -> OperationResponse.deleted(output);
            default -> OperationResponse.ok(output);
        };
    }

    private OperationResponse mapWorkflowCode(Integer code, Throwable cause) {
        return switch (code) {
            case 400 -> OperationResponse.badRequest(cause);
            case 401 -> OperationResponse.unauthorized(cause);
            case 403 -> OperationResponse.forbidden(cause);
            case 404 -> OperationResponse.notFound(cause);
            case 406 -> OperationResponse.notAcceptable(cause);
            case 409 -> OperationResponse.conflict(cause);
            case 415 -> OperationResponse.unsupportedMediaType(cause);
            default -> OperationResponse.error(cause);
        };
    }

    @Override
    public IWorkflow getWorkflow() {
        return this.workflow;
    }

}
