package com.garganttua.api.core.domain;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.api.core.dto.DtoBuilder;
import com.garganttua.api.core.entity.EntityBuilder;
import com.garganttua.api.core.security.DomainSecurityBuilder;
import com.garganttua.api.core.usecase.UseCaseBuilder;
import com.garganttua.core.reflection.annotations.Reflected;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.garganttua.api.core.domain.DomainStartupBinderBuilder;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.Pluralizer;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.context.BuildingStage;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IDomainStartupBinderBuilder;
import com.garganttua.api.commons.context.dsl.IDomainWorkflowBuilder;
import com.garganttua.api.commons.context.dsl.IDtoBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.context.dsl.IUseCaseBuilder;
import com.garganttua.api.commons.context.dsl.security.IDomainSecurityBuilder;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.definition.IUseCaseDefinition;
import com.garganttua.api.commons.definition.IWorkflowDefinition;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.BeanDefinition;
import com.garganttua.core.injection.IInjectableElementResolver;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.mapper.IMapper;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.core.workflow.IWorkflow;

@Reflected
@SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Validating builder constructor throws on invalid input by design; the class declares no finalizer, so the finalizer-attack premise does not apply.")
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidDuplicateLiterals", "PMD.ConstructorCallsOverridableMethod"})
public class DomainBuilder<E> extends AbstractDomainCharacteristicsBuilder<E> {

    // Reflection provider: whatever the user installed via IClass.setReflection(), resolved lazily.
    private static IReflectionProvider provider() {
        return IClass.getReflection();
    }

    private volatile String domainName;
    private volatile IClass<?> entityClass;

    private IMapper mapper = DefaultMapper.mapper();

    private final List<IDomainStartupBinderBuilder> startupBinderBuilders = new CopyOnWriteArrayList<>();
    final List<ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>>> interfaces = new CopyOnWriteArrayList<>();
    final List<ISupplierBuilder<?, ? extends ISupplier<?>>> events = new CopyOnWriteArrayList<>();

    volatile boolean publik = false;
    volatile boolean tenant = false;
    volatile boolean doInjection = false;
    private volatile IEntityBuilder<E> entityBuilder;
    final List<Object> createEntities = new CopyOnWriteArrayList<>();
    final List<Object> upsertEntities = new CopyOnWriteArrayList<>();
    volatile IDomainSecurityBuilder<E> securityBuilder;
    private final Map<IClass<?>, IDtoBuilder> dtos = new ConcurrentHashMap<>();
    private final Map<String, IUseCaseBuilder<?, ?, E>> useCases = new ConcurrentHashMap<>();
    private final Map<String, DomainWorkflowBuilder<E>> workflows = new ConcurrentHashMap<>();

    private volatile IInjectionContextBuilder injectionContextBuilder;
    private volatile IExpressionContextBuilder expressionContextBuilder;

    public void setDependencyBuilders(IInjectionContextBuilder injectionContextBuilder,
            IExpressionContextBuilder expressionContextBuilder) {
        this.injectionContextBuilder = injectionContextBuilder;
        this.expressionContextBuilder = expressionContextBuilder;
    }

    public DomainBuilder(IApiBuilder builder, String domainName)
            throws ApiException {
        super(builder);
        this.domainName = Objects.requireNonNull(domainName, "Domain name cannot be null");
        initDefaultCrudWorkflows();
    }

    public DomainBuilder(IApiBuilder builder, IClass<?> entityClass) throws ApiException {
        super(builder);
        this.entityClass = Objects.requireNonNull(entityClass, "Entity Class cannot be null");
        DomainBuilderValidation.validateReflectable(this.entityClass, provider());
        this.domainName = Pluralizer.toPlural(this.entityClass.getSimpleName().toLowerCase(java.util.Locale.ROOT));
        this.securityBuilder = new DomainSecurityBuilder<>(this, this.interfaces, this.entityClass);
        this.entityBuilder = this.entity(this.entityClass);
        initDefaultCrudWorkflows();
    }

    @Override
    public IDomainStartupBinderBuilder startup(BuildingStage stage, ISupplierBuilder<?, ? extends ISupplier<?>> supplier)
            throws ApiException {
        DomainStartupBinderBuilder binder = new DomainStartupBinderBuilder(this, supplier);
        this.startupBinderBuilders.add(binder);
        return binder;
    }

    @Override
    public IDomainBuilder<E> interfasse(ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>> bean) throws ApiException {
        this.interfaces.add(bean);
        return this;
    }

    @Override
    public IDomainBuilder<E> events(ISupplierBuilder<?, ? extends ISupplier<?>> bean) throws ApiException {
        IClass<?> suppliedClass = bean.getSuppliedClass();
        if (!IClass.getClass(IEventPublisher.class).isAssignableFrom(suppliedClass)) {
            throw new ApiException(
                    "Bean " + suppliedClass.getName() + " does not implement IEventPublisher");
        }
        this.events.add(bean);
        return this;
    }

    @Override
    public IDomainBuilder<E> events(IEventPublisher eventPublisher) throws ApiException {
        this.events.add(FixedSupplierBuilder.of(
                Objects.requireNonNull(eventPublisher, "EventPublisher cannot be null")));
        return this;
    }

    @Override
    public IDomainBuilder<E> tenant(boolean b) throws ApiException {
        if (b && this.up() instanceof ApiBuilder acb && !acb.isMultiTenant()) {
            throw new ApiException("Cannot mark domain '" + this.domainName + "' as the tenant — "
                    + "multi-tenancy is disabled on the parent API. Either drop the .tenant(true) call "
                    + "(single-tenant apps do not need a tenant entity), or remove the .multiTenant(false) "
                    + "call on the apiBuilder to re-enable multi-tenancy.");
        }
        this.tenant = b;
        return this;
    }

    @Override
    public IDomainBuilder<E> publik() {
        this.publik = true;
        return this;
    }

    /**
     * Package-private accessor used by {@link EntityBuilder} and
     * {@link DtoBuilder} to skip the {@code tenantId} requirement when the
     * domain entity is itself the tenant (its own uuid plays the role of
     * tenantId — see {@code FilterContext.buildTenantFilter} for the
     * downstream filter rewrite).
     */
    public boolean isTenantDomain() {
        return this.tenant;
    }

    @Override
    public IDomainBuilder<E> doInjection(boolean enabled) {
        this.doInjection = enabled;
        return this;
    }

    public IEntityBuilder<E> entity(IClass<?> entityClass) throws ApiException {
        Objects.requireNonNull(entityClass, "Entity class cannot be null");

        if (this.entityBuilder != null && Objects.equals(entityClass, this.entityClass)) {
            throw new ApiException(
                    "Entity Class is already set with class " + entityClass.getSimpleName());
        }

        this.entityBuilder = new EntityBuilder<>(entityClass, this);
        this.entityClass = entityClass;

        DomainBuilderValidation.validateReflectable(this.entityClass, provider());

        this.securityBuilder = new DomainSecurityBuilder<>(this, this.interfaces, this.entityClass);

        return this.entityBuilder;
    }

    @Override
    public IDomainSecurityBuilder<E> security() throws ApiException {
        // securityBuilder is wired in the IClass-based constructor; null means
        // this domain was created from a name only.
        requireEntityClassSet("security");
        return this.securityBuilder;
    }

    @Override
    public <D> IDtoBuilder<E, D> dto(IClass<D> dtoClass) throws ApiException {
        requireEntityClassSet("dto");

        IDtoBuilder<E, D> dtoBuilder = (IDtoBuilder<E, D>) this.dtos.computeIfAbsent(dtoClass, clazz -> {
            return new DtoBuilder<>(clazz, this);
        });
        DomainBuilderBuildSupport.recordDtoMapping(this.mapper, this.entityClass, dtoClass);
        return dtoBuilder;
    }

    @Override
    public <I, O> IUseCaseBuilder<I, O, E> useCase(String useCaseName, IClass<I> inputType, IClass<O> outputType) {
        Objects.requireNonNull(useCaseName, "Use case name cannot be null");

        return (IUseCaseBuilder<I, O, E>) this.useCases.computeIfAbsent(useCaseName,
                name -> new UseCaseBuilder<I, O, E>(name, this, inputType, outputType));
    }

    private IInjectableElementResolver buildUseCaseResolverRegistry() throws ApiException {
        return DomainUseCaseSupport.buildResolverRegistry(this.injectionContextBuilder);
    }

    @Override
    public IDomainWorkflowBuilder<E> workflow(String workflowName) {
        Objects.requireNonNull(workflowName, "Workflow name cannot be null");
        return this.workflows.computeIfAbsent(workflowName,
                name -> new DomainWorkflowBuilder<>(name, this));
    }

    @Override
    public IDomainBuilder<E> create(Object entity) {
        this.createEntities.add(entity);
        return this;
    }

    @Override
    public IDomainBuilder<E> upsert(Object entity) {
        this.upsertEntities.add(entity);
        return this;
    }

    @Override
    public IDomainBuilder<E> creation(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.create.getLabel(), enabled);
        return this;
    }

    @Override
    public IDomainBuilder<E> readAll(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.readAll.getLabel(), enabled);
        return this;
    }

    @Override
    public IDomainBuilder<E> readOne(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.readOne.getLabel(), enabled);
        return this;
    }

    @Override
    public IDomainBuilder<E> update(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.update.getLabel(), enabled);
        return this;
    }

    @Override
    public IDomainBuilder<E> deleteOne(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.deleteOne.getLabel(), enabled);
        return this;
    }

    @Override
    public IDomainBuilder<E> deleteAll(boolean enabled) {
        toggleCrudWorkflow(BusinessOperation.deleteAll.getLabel(), enabled);
        return this;
    }

    private void toggleCrudWorkflow(String label, boolean enabled) {
        if (!enabled) {
            this.workflows.remove(label);
        }
    }

    /**
     * Throws with a concrete recipe when a domain-level method is called before
     * {@code .entity(...)} has produced an entity builder. The stack trace
     * surfaces the offending method name, the message surfaces the fix.
     */
    @Override
    protected IClass<?> characteristicEntityClass() {
        return this.entityClass;
    }

    @Override
    protected void requireCharacteristicEntityDeclared() throws ApiException {
        requireEntityDeclared();
    }

    private void requireEntityDeclared() throws ApiException {
        DomainBuilderValidation.requireEntityDeclared(this.entityBuilder, this.entityClass, this.domainName);
    }

    /**
     * Throws with a concrete recipe when a method needs the entity class to be
     * known but the domain was created from a name only (no {@code IClass}).
     */
    private void requireEntityClassSet(String calledMethod) throws ApiException {
        DomainBuilderValidation.requireEntityClassSet(this.entityClass, this.domainName, calledMethod);
    }

    @Override
    public IClass<E> getEntityClass() throws ApiException {
        requireEntityClassSet("getEntityClass");
        return (IClass<E>) this.entityClass;
    }

    @Override
    protected synchronized IDomain<E> doBuild() throws ApiException {

        this.throwExceptionIfNoDto();
        this.validateSecurityRoles();
        this.validateSuperFields();

        List<IDtoContext<?>> dtoContexts = new ArrayList<>();
        List<IDtoDefinition<E>> dtoDefinitions = new ArrayList<>();
        DomainBuilderBuildSupport.<E>buildDtoContexts(this.dtos, dtoContexts, dtoDefinitions);

        // Build the @Resolver registry once (shared by entity free-hooks and the use cases below) and
        // hand it to the entity builder so its free lifecycle-hook parameters auto-wire at build.
        IInjectableElementResolver resolverRegistry = buildUseCaseResolverRegistry();
        if (this.entityBuilder instanceof com.garganttua.api.core.entity.EntityBuilder<E> eb) {
            eb.setResolverRegistry(resolverRegistry);
        }

        IEntityContext<E> entityContext = this.entityBuilder.build();
        BeanDefinition<?> entityBeanDefinition = DomainBuilderBuildSupport.buildEntityBeanDefinition(
                this.injectionContextBuilder, this.entityClass);
        List<IMethodBinder<Void>> startupBinders = DomainBuilderBuildSupport.buildStartupBinders(
                this.startupBinderBuilders);

        // Build the full use case + workflow definitions (extracted for size).
        Map<String, IUseCaseDefinition> useCaseDefinitions = buildUseCaseDefinitions(resolverRegistry);
        Map<String, IWorkflowDefinition> workflowDefinitions = buildWorkflowDefinitions();

        IWorkflow builtWorkflow = buildDomainWorkflow();

        DomainDefinition<E> domainDefinition = DomainBuilderBuildSupport.assembleDomainDefinition(
                this, DomainBuilderBuildSupport.entityDefinitionOf(entityContext),
                dtoDefinitions, startupBinders, useCaseDefinitions, workflowDefinitions);

        return DomainBuilderBuildSupport.assembleDomain(this, domainDefinition,
                entityContext, dtoContexts, builtWorkflow, entityBeanDefinition);
    }

    /** Builds the runtime security context, falling back to an empty one when none was declared. */
    com.garganttua.api.commons.security.IDomainSecurityContext buildSecurityContext()
            throws ApiException {
        return this.securityBuilder != null
                ? this.securityBuilder.build()
                : new DomainSecurityBuilder<>(this, this.interfaces, this.entityClass).build();
    }

    /** Builds the use-case definitions, auto-wiring each bound method's annotated parameters. */
    @SuppressWarnings("unchecked")
    private Map<String, IUseCaseDefinition> buildUseCaseDefinitions(IInjectableElementResolver resolverRegistry)
            throws ApiException {
        return DomainDefinitionsAssembler.buildUseCaseDefinitions(this.useCases, this.domainName, resolverRegistry);
    }

    private Map<String, IWorkflowDefinition> buildWorkflowDefinitions() {
        return DomainDefinitionsAssembler.buildWorkflowDefinitions(this.workflows);
    }

    /**
     * Populates this domain's workflow stages (idempotent fallback) and returns the built workflow.
     * Goes through the per-name child builder rather than the memoised shared map so workflows
     * registered after the first lookup (e.g. auto-detected domains) are included.
     */
    private IWorkflow buildDomainWorkflow() throws ApiException {
        boolean multiTenancyEnabled = this.up() instanceof ApiBuilder acb && acb.isMultiTenant();
        ApiBuilder parent = DomainWorkflowSupport.requireApiBuilderParent(this.up(), this.domainName);
        com.garganttua.core.workflow.dsl.IWorkflowsBuilder wb =
                DomainWorkflowSupport.requireWorkflowsBuilder(parent);
        this.populateWorkflowStages(wb, this.injectionContextBuilder,
                this.expressionContextBuilder, multiTenancyEnabled,
                parent.getWorkflowTimingConfig());
        return DomainWorkflowSupport.buildNamedWorkflow(wb, this.domainName);
    }

    private void initDefaultCrudWorkflows() {
        for (String label : DomainWorkflowSupport.CRUD_OPERATION_LABELS) {
            registerCrudMetadata(label);
        }
    }

    private void registerCrudMetadata(String name) {
        DomainWorkflowBuilder<E> wb = new DomainWorkflowBuilder<>(name, this);
        wb.setCustom(false);
        this.workflows.put(name, wb);
    }

    public String getDomainName() {
        return this.domainName;
    }

    /** True when at least one interface was declared on this domain via {@code .interfasse(...)}. */
    public boolean hasInterfaces() {
        return !this.interfaces.isEmpty();
    }

    private volatile boolean workflowStagesPopulated = false;

    /**
     * Populates this domain's workflow stages into the shared
     * {@link com.garganttua.core.workflow.dsl.IWorkflowsBuilder}. Called from
     * {@link ApiBuilder#doConfigureWithDependencyBuilder} at the Bootstrap
     * CONFIGURATION stage so that {@code WorkflowsBuilder.doBuild()} at
     * BUILD stage sees a filled registry (topo orders WorkflowsBuilder before
     * ApiBuilder, so anything we'd push at BUILD time arrives too late). Also
     * called as a fallback from {@link #build()} when a caller drives
     * DomainBuilder directly (typical of unit tests), bypassing both
     * Bootstrap and ApiBuilder.build()'s configuration-stage trigger.
     *
     * <p>Idempotent — subsequent calls are no-ops so the
     * {@code workflowsBuilder.workflow(name)} child doesn't accumulate
     * duplicated stages.
     */
    public void populateWorkflowStages(
            com.garganttua.core.workflow.dsl.IWorkflowsBuilder workflowsBuilder,
            com.garganttua.core.injection.context.dsl.IInjectionContextBuilder injectionContextBuilder,
            com.garganttua.core.expression.dsl.IExpressionContextBuilder expressionContextBuilder,
            boolean multiTenancyEnabled,
            com.garganttua.core.workflow.WorkflowTimingConfig workflowTimingConfig) throws ApiException {
        if (this.workflowStagesPopulated) {
            return;
        }
        // Auto-register security-driven workflows BEFORE assembling stages —
        // these used to live inside build() but stage assembly now happens at
        // CONFIGURATION (well before build()). Without this prelude, the
        // assembler iterates an incomplete workflows map and the authenticate
        // operation's request returns 405.
        DomainWorkflowSupport.autoRegisterSecurityWorkflows(
                this.securityBuilder, this.workflows, this::registerCrudMetadata);

        DomainWorkflowSupport.<E>assembleStages(this.domainName, this.securityBuilder, this.workflows,
                this.useCases.keySet(), this.owner, this.owned, multiTenancyEnabled,
                injectionContextBuilder, workflowsBuilder, workflowTimingConfig);
        this.workflowStagesPopulated = true;
    }

    private void throwExceptionIfNoDto() throws ApiException {
        DomainBuilderValidation.requireDto(!this.dtos.isEmpty(), this.domainName, this.entityClass);
    }

    private void validateSecurityRoles() throws ApiException {
        DomainBuilderValidation.validateSecurityRoles(this.securityBuilder, this.domainName, this.owned, this.owner);
    }

    private void validateSuperFields() throws ApiException {
        DomainBuilderValidation.validateSuperFields(this.domainName, this.tenant, this.superTenant,
                this.owner, this.superOwner);
    }

    @Override
    protected void doAutoDetection() {

    }

    @Override
    public synchronized IEntityBuilder<E> entity() throws ApiException {
        requireEntityClassSet("entity");

        if( this.entityBuilder == null)
            this.entityBuilder = new EntityBuilder<>(entityClass, this);

        return this.entityBuilder;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public IDomainBuilder<E> interfasse(IClass<? extends IInterface> interfasse) throws ApiException {
        Objects.requireNonNull(interfasse, "Interface class cannot be null");
        // Explicit attachment of a (catalogued) @Interface type to this domain: instantiate it via
        // its no-arg constructor and add it to the domain's interface suppliers. A pre-built /
        // configured instance goes through the other overload, interfasse(ISupplierBuilder).
        IInterface instance = DomainBuilderBuildSupport.instantiateInterface(interfasse);
        this.interfaces.add(new FixedSupplierBuilder(instance, interfasse));
        return this;
    }

    @Override
    public IEntityBuilder<E> name(String name) throws ApiException {
        Objects.requireNonNull(name, "Name cannot be null");
        this.domainName = name;
        return this.entity();
    }

}
