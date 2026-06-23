package com.garganttua.api.core.api;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.domain.DomainBuilder;
import com.garganttua.api.core.security.AuthoritiesEndpointBuilder;
import com.garganttua.api.core.security.SecurityBuilder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.garganttua.api.core.api.ApiStartupBinderBuilder;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.BuildingStage;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IApiStartupBinderBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.security.IApiSecurityBuilder;
import com.garganttua.api.commons.dao.IDaoFactory;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.protocol.IProtocol;
import com.garganttua.api.commons.protocol.Protocol;
import com.garganttua.api.commons.security.authorization.AuthorizationProtocol;
import com.garganttua.api.commons.security.authorization.IAuthorizationProtocol;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.commons.serialization.Serializer;
import com.garganttua.core.bootstrap.annotations.Bootstrap;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.dsl.annotations.ConfigurableBuilder;
import com.garganttua.core.dsl.annotations.Scan;
import com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder;
import com.garganttua.core.dsl.dependency.DependencyPhase;
import com.garganttua.core.dsl.dependency.DependencySpec;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.core.workflow.dsl.IWorkflowsBuilder;
import com.garganttua.core.observability.Logger;

@Bootstrap
@Reflected
@ConfigurableBuilder("api")
@Scan(scan = "com.garganttua.api.core")
@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidDuplicateLiterals"})
public class ApiBuilder extends AbstractAutomaticDependentBuilder<IApiBuilder, IApi>
		implements IApiBuilder {
	private static final Logger log = Logger.getLogger(ApiBuilder.class);

	private ApiBuilder() {
		super(Set.of(
						DependencySpec.require(IClass.getClass(IInjectionContextBuilder.class), DependencyPhase.BUILD),
						DependencySpec.require(IClass.getClass(IExpressionContextBuilder.class), DependencyPhase.BUILD),
						DependencySpec.require(IClass.getClass(IReflectionBuilder.class), DependencyPhase.BUILD),
						// Optional: when present (bootstrap-discovered) each Domain attaches to the
						// binding so @Observer scans flow through; when absent, observability is off.
						DependencySpec.use(IClass.getClass(
								com.garganttua.core.observability.dsl.IObservabilityBuilder.class),
								DependencyPhase.BUILD),
						// requireConfigure: receive the IWorkflowsBuilder reference at CONFIGURATION
						// stage (before topo-sorted BUILD). The api opens per-domain workflow slots
						// and pushes all stages at CONFIG so WorkflowsBuilder.doBuild() at BUILD sees a
						// populated registry; doBuild() then just retrieves the cached built workflows.
						DependencySpec.requireConfigure(IClass.getClass(IWorkflowsBuilder.class))));
	}

	private final Set<String> packages = ConcurrentHashMap.newKeySet();

	// Framework packages auto-injected into the asset scan surface so built-in protocols/serializers
	// ship discoverable out of the box. Opt-out via includeFrameworkPackages(false).
	static final String[] FRAMEWORK_PACKAGES = {"com.garganttua.api", "com.garganttua.core"};

	private volatile boolean includeFrameworkPackages = true;
	volatile String superTenantId;
	volatile boolean superTenantAutoCreate = false;
	volatile boolean multiTenant = true;
	// Locked by default: super-tenants/owners seeded only by the startup scan; runtime promotion needs the lock.
	volatile boolean lockSuperTenantCreation = true;
	volatile boolean lockSuperOwnerCreation = true;

	final Map<IClass<?>, DomainBuilder<?>> domainBuilders = new ConcurrentHashMap<>();
	private volatile IDaoFactory defaultDaoFactory;
	volatile ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>> defaultInterface;
	volatile SecurityBuilder securityBuilder;
	final List<ApiStartupBinderBuilder> startupBinderBuilders = new CopyOnWriteArrayList<>();
	final List<ISerializer> serializers = new CopyOnWriteArrayList<>();
	final List<ISupplierBuilder<?, ? extends ISupplier<?>>> serializerBuilders = new CopyOnWriteArrayList<>();
	final List<IProtocol<?, ?>> protocols = new CopyOnWriteArrayList<>();
	final List<ISupplierBuilder<?, ? extends ISupplier<?>>> protocolBuilders = new CopyOnWriteArrayList<>();
	final List<IAuthorizationProtocol> authorizationProtocols = new CopyOnWriteArrayList<>();
	final List<ISupplierBuilder<?, ? extends ISupplier<?>>> authorizationProtocolBuilders = new CopyOnWriteArrayList<>();
	volatile IInjectionContextBuilder injectionContextBuilder;
	volatile IExpressionContextBuilder expressionContextBuilder;
	volatile com.garganttua.core.observability.dsl.IObservabilityBuilder observabilityBuilder;
	private volatile IWorkflowsBuilder workflowsBuilder;
	private volatile java.util.Map<String, com.garganttua.core.workflow.IWorkflow> builtWorkflows;
	volatile IInjectionContext injectionContext;
	volatile AuthoritiesEndpointBuilder authoritiesEndpointBuilder;
	volatile com.garganttua.core.workflow.WorkflowTimingConfig workflowTimingConfig =
			com.garganttua.core.workflow.WorkflowTimingConfig.disabled();
	/**
	 * Default entry point — returns a fresh {@code ApiBuilder}. The caller wires the reflection /
	 * injection / expression stack and orchestrates the build (typically through a Bootstrap driven
	 * from {@code main()}); the framework picks no implementation of its own. When no
	 * {@code IReflection} is installed, the constructor's {@code IClass.getClass(...)} dependency
	 * declarations throw an {@code IllegalStateException} which is re-thrown as an {@link ApiException}
	 * with concrete guidance.
	 */
	public static IApiBuilder builder() {
		try {
			return new ApiBuilder();
		} catch (IllegalStateException e) {
			if (e.getMessage() != null && e.getMessage().contains("No IReflection")) {
				throw new ApiException(NO_REFLECTION_GUIDANCE, e);
			}
			throw e;
		}
	}

	private static final String NO_REFLECTION_GUIDANCE =
			"No IReflection installed — ApiBuilder needs one to declare its dependencies. "
			+ "Install a reflection stack ONCE in your app's main() before calling ApiBuilder.builder(), e.g.:\n\n"
			+ "    IClass.setReflection(ReflectionBuilder.builder()\n"
			+ "        .withProvider(new RuntimeReflectionProvider())     // garganttua-runtime-reflection\n"
			+ "        .withScanner(new ReflectionsAnnotationScanner())   // garganttua-reflections\n"
			+ "        .build());\n\n"
			+ "Or rely on garganttua-core's ServiceLoader cold-start: put the provider/scanner jars on "
			+ "the classpath and a Bootstrap.builder() call anywhere in your bootstrap setup picks them up.";

	@Override
	public IApiBuilder superTenantId(String superTenantId) {
		if (!this.multiTenant) {
			throw new ApiException("Cannot set superTenantId — multi-tenancy is disabled (.multiTenant(false) was called or set as default). "
					+ "Either keep multi-tenancy enabled (do not call .multiTenant(false)) if you need a super-tenant, "
					+ "or drop the .superTenantId(...) call for a single-tenant app.");
		}
		this.superTenantId = Objects.requireNonNull(superTenantId, "Super tenant ID cannot be null");
		return this;
	}

	@Override
	public IApiStartupBinderBuilder startup(BuildingStage stage,
			ISupplierBuilder<?, ? extends ISupplier<?>> supplier) throws ApiException {
		ApiStartupBinderBuilder binder = new ApiStartupBinderBuilder(this, supplier);
		this.startupBinderBuilders.add(binder);
		return binder;
	}

	@Override
	public IApiStartupBinderBuilder startup(BuildingStage stage, Object object) throws ApiException {
		ApiStartupBinderBuilder binder = new ApiStartupBinderBuilder(this, object);
		this.startupBinderBuilders.add(binder);
		return binder;
	}

	@Override
	public <E> IDomainBuilder<E> domain(IClass<E> entityClass) throws ApiException {
		Objects.requireNonNull(entityClass, "Entity class cannot be null");

		return (IDomainBuilder<E>) this.domainBuilders.computeIfAbsent(entityClass,
				clazz -> new DomainBuilder<>(this, clazz));
	}

	@Override
	public IApiBuilder superTenantAutoCreate(boolean b) throws ApiException {
		if (!this.multiTenant) {
			throw new ApiException("Cannot set superTenantAutoCreate — multi-tenancy is disabled. "
					+ "Drop the .superTenantAutoCreate(...) call for single-tenant apps, or keep .multiTenant(true).");
		}
		this.superTenantAutoCreate = b;
		return this;
	}

	@Override
	public IApiBuilder lockSuperTenantCreation(boolean lock) {
		this.lockSuperTenantCreation = lock;
		return this;
	}

	@Override
	public IApiBuilder lockSuperOwnerCreation(boolean lock) {
		this.lockSuperOwnerCreation = lock;
		return this;
	}

	@Override
	public IApiBuilder multiTenant(boolean enabled) throws ApiException {
		if (!enabled && this.superTenantId != null) {
			throw new ApiException("Cannot call .multiTenant(false) after .superTenantId(...) has been set. "
					+ "A super-tenant only makes sense in multi-tenant mode — either remove the .superTenantId(...) call "
					+ "or keep multi-tenancy enabled. (DSL is order-sensitive: set .multiTenant(false) first if that is what you want.)");
		}
		if (!enabled && this.superTenantAutoCreate) {
			throw new ApiException("Cannot call .multiTenant(false) after .superTenantAutoCreate(true) has been set. "
					+ "Same reason as .superTenantId — remove the .superTenantAutoCreate(...) call or keep multi-tenancy enabled.");
		}
		this.multiTenant = enabled;
		return this;
	}

	@Override
	public IApiBuilder workflowTiming(com.garganttua.core.workflow.WorkflowTimingConfig config) throws ApiException {
		Objects.requireNonNull(config, "WorkflowTimingConfig cannot be null");
		this.workflowTimingConfig = config;
		return this;
	}

	@Override
	public synchronized IApiSecurityBuilder security() {
		if (this.securityBuilder == null) {
			this.securityBuilder = new SecurityBuilder(this.packages, this);
		}
		return this.securityBuilder;
	}

	@Override
	public IApiBuilder serializer(ISerializer serializer) throws ApiException {
		Objects.requireNonNull(serializer, "Serializer cannot be null");
		this.serializers.add(serializer);
		return this;
	}

	@Override
	public IApiBuilder serializer(ISupplierBuilder<?, ? extends ISupplier<?>> bean) throws ApiException {
		Objects.requireNonNull(bean, "Serializer supplier builder cannot be null");
		this.serializerBuilders.add(bean);
		return this;
	}

	@Override
	public IApiBuilder protocol(IProtocol<?, ?> protocol) throws ApiException {
		Objects.requireNonNull(protocol, "Protocol cannot be null");
		this.protocols.add(protocol);
		return this;
	}

	@Override
	public IApiBuilder protocol(ISupplierBuilder<?, ? extends ISupplier<?>> bean) throws ApiException {
		Objects.requireNonNull(bean, "Protocol supplier builder cannot be null");
		this.protocolBuilders.add(bean);
		return this;
	}

	@Override
	public IApiBuilder authorizationProtocol(IAuthorizationProtocol protocol) throws ApiException {
		Objects.requireNonNull(protocol, "Authorization protocol cannot be null");
		this.authorizationProtocols.add(protocol);
		return this;
	}

	@Override
	public IApiBuilder authorizationProtocol(ISupplierBuilder<?, ? extends ISupplier<?>> bean) throws ApiException {
		Objects.requireNonNull(bean, "Authorization protocol supplier builder cannot be null");
		this.authorizationProtocolBuilders.add(bean);
		return this;
	}

	@Override
	public com.garganttua.api.commons.context.dsl.IAuthoritiesEndpointBuilder exposeAuthorities() throws ApiException {
		if (this.authoritiesEndpointBuilder == null) {
			this.authoritiesEndpointBuilder = new AuthoritiesEndpointBuilder(this);
		}
		return this.authoritiesEndpointBuilder;
	}

	public String[] getPackages() {
		return this.packages.toArray(new String[0]);
	}

	public IApiBuilder withPackage(String packageName) {
		log.debug("Adding package: {}", packageName);
		this.packages.add(Objects.requireNonNull(packageName, "Package name cannot be null"));
		return this;
	}

	public IApiBuilder withPackages(String... packageNames) {
		log.debug("Adding {} packages", packageNames.length);
		Objects.requireNonNull(packageNames, "Package names cannot be null");
		for (String pkg : packageNames) {
			this.withPackage(pkg);
		}
		return this;
	}

	@Override
	public IApiBuilder includeFrameworkPackages(boolean include) {
		this.includeFrameworkPackages = include;
		return this;
	}

	@Override
	protected void doAutoDetectionWithDependency(Object dependency) throws ApiException {
		// Entity/domain auto-detection from the injection context is not yet implemented; the
		// annotation scanners in doAutoDetection() cover the current discovery surface.
		log.trace("doAutoDetectionWithDependency() dependency: {}", dependency);
	}

	@Override
	protected void doPreBuildWithDependency(Object dependency) {
		if (dependency instanceof IInjectionContext context) {
			this.injectionContext = context;
			log.debug("InjectionContext captured in pre-build phase");
		}
	}

	@Override
	protected void doPostBuildWithDependency(Object dependency) {
		if (dependency instanceof IInjectionContext context) {
			registerBuiltObjectInContext(context, this.built);
		}
	}

	private void registerBuiltObjectInContext(IInjectionContext context, IApi apiContext) {
		ApiBuilderRegistration.registerBuiltObjectInContext(this, context, apiContext);
	}

	/**
	 * Fires the CONFIGURATION stage before delegating to the framework {@code build()}. Bootstrap
	 * normally runs CONFIGURATION globally, but a caller driving ApiBuilder directly (integration
	 * tests / library users bypassing Bootstrap) never fires that hook, so the per-domain workflow
	 * stages would never be populated. {@code runConfigurationStage()} is idempotent — a no-op when
	 * Bootstrap already drove the configuration phase.
	 */
	@Override
	public IApi build() throws ApiException {
		try {
			this.runConfigurationStage();
		} catch (com.garganttua.core.dsl.DslException e) {
			throw new ApiException("Failed during CONFIGURATION stage: " + e.getMessage(), e);
		}
		return super.build();
	}

	@Override
	protected synchronized IApi doBuild() throws ApiException {
		return ApiBuilderBuild.assembleApi(this);
	}

	private volatile boolean autoDetectionRan = false;
	// Closeables opened by IApiAutoConfiguration; adopted by the built Api lifecycle.
	volatile List<AutoCloseable> autoConfigResources = List.of();

	@Override
	protected void doAutoDetection() throws ApiException {
		// Idempotent: invoked once from the CONFIGURATION hook (api domain + security state final
		// before workflow-stage assembly) and again by the automatic auto-detection sweep. The second
		// call must not re-run the scanners — they would re-trigger DSL side-effects on finalised
		// domain builders.
		if (this.autoDetectionRan) {
			log.debug("doAutoDetection skipped — already ran at CONFIGURATION stage");
			return;
		}
		// Self-configure BEFORE the scans: run every IApiAutoConfiguration (default DAO/interface,
		// anonymous access, top-level api.* config) so the neutral core runner boots a wired API.
		this.autoConfigResources = ApiAutoConfigurationRunner.apply(this);
		// Asset scan (serializers/protocols/authorization protocols) spans framework packages too;
		// entity/security scans stay user-package-only to avoid dragging in framework test fixtures.
		ApiBuilderAssetDetection.autoDetectAll(this, assetScanSurface());
		new com.garganttua.api.core.entity.EntityAnnotationScanner(this, this.packages).scan();
		new com.garganttua.api.core.security.SecurityAnnotationScanner(this, this.packages).scan();
		this.autoDetectionRan = true;
	}

	// Union of user-declared packages and the framework's own (when includeFrameworkPackages is on,
	// the default). Used only by asset auto-detection so built-in implementations ship discoverable.
	private Set<String> assetScanSurface() {
		Set<String> surface = new java.util.HashSet<>(this.packages);
		if (this.includeFrameworkPackages) {
			for (String pkg : FRAMEWORK_PACKAGES) {
				surface.add(pkg);
			}
		}
		return surface;
	}

	// package-private delegators for unit testing
	static IAuthorizationProtocol instantiateAuthorizationProtocol(IClass<?> clazz) {
		return ApiBuilderAssetDetection.instantiateAuthorizationProtocol(clazz);
	}

	static IProtocol<?, ?> instantiateProtocol(IClass<?> clazz) {
		return ApiBuilderAssetDetection.instantiateProtocol(clazz);
	}

	static ISerializer instantiateSerializer(IClass<?> clazz) {
		return ApiBuilderAssetDetection.instantiateSerializer(clazz);
	}

	@Override
	public IApiBuilder provide(IObservableBuilder<?, ?> dependency) throws ApiException {
		if (dependency instanceof IInjectionContextBuilder builder) {
			this.injectionContextBuilder = builder;
			log.debug("IInjectionContextBuilder captured via provide()");
		} else if (dependency instanceof IExpressionContextBuilder builder) {
			this.expressionContextBuilder = builder;
			ApiBuilderBuild.configureExpressionContext(builder);
			log.debug("IExpressionContextBuilder captured via provide()");
		} else if (dependency instanceof com.garganttua.core.observability.dsl.IObservabilityBuilder builder) {
			this.observabilityBuilder = builder;
			log.debug("IObservabilityBuilder captured via provide()");
		} else if (dependency instanceof IWorkflowsBuilder builder) {
			this.workflowsBuilder = builder;
			log.debug("IWorkflowsBuilder captured via provide()");
		}
		return super.provide(dependency);
	}

	public IWorkflowsBuilder getWorkflowsBuilder() {
		return this.workflowsBuilder;
	}

	public com.garganttua.core.workflow.WorkflowTimingConfig getWorkflowTimingConfig() {
		return this.workflowTimingConfig;
	}

	/**
	 * Returns the per-domain {@link com.garganttua.core.workflow.IWorkflow} map produced by
	 * {@link IWorkflowsBuilder#build()} (lazy, cached — the WorkflowsBuilder is built before us per
	 * topo). Each domain name maps to its built workflow.
	 *
	 * @throws ApiException when the WorkflowsBuilder couldn't build
	 */
	java.util.Map<String, com.garganttua.core.workflow.IWorkflow> getBuiltWorkflows() throws ApiException {
		if (this.builtWorkflows == null) {
			if (this.workflowsBuilder == null) {
				throw new ApiException("IWorkflowsBuilder not provided to ApiBuilder — "
						+ "Bootstrap should auto-discover it via WorkflowsBuilderFactory or "
						+ "the caller should provide() one explicitly before build().");
			}
			try {
				this.builtWorkflows = this.workflowsBuilder.build();
			} catch (com.garganttua.core.dsl.DslException e) {
				throw new ApiException("Failed to build IWorkflowsBuilder: " + e.getMessage(), e);
			}
		}
		return this.builtWorkflows;
	}

	@Override
	protected void doConfigureWithDependencyBuilder(
			com.garganttua.core.dsl.IObservableBuilder<?, ?> dependencyBuilder)
			throws com.garganttua.core.dsl.DslException {
		if (dependencyBuilder instanceof IWorkflowsBuilder builder) {
			this.workflowsBuilder = builder;
			log.debug("IWorkflowsBuilder captured at CONFIGURATION stage");
			ApiBuilderBuild.populateWorkflowStagesAtConfiguration(this, builder);
		}
	}

	IInjectionContextBuilder getInjectionContextBuilder() {
		return this.injectionContextBuilder;
	}

	IExpressionContextBuilder getExpressionContextBuilder() {
		return this.expressionContextBuilder;
	}

	public boolean isMultiTenant() {
		return this.multiTenant;
	}

	@Override
	public IApiBuilder packages(String... packageNames) throws ApiException {
		Objects.requireNonNull(packageNames, "packageNames cannot be null");
		for (String pkg : packageNames) {
			this.withPackage(Objects.requireNonNull(pkg, "package name cannot be null"));
		}
		return this;
	}

	@Override
	public IApiBuilder defaultDao(IDaoFactory factory) throws ApiException {
		this.defaultDaoFactory = Objects.requireNonNull(factory, "default DAO factory cannot be null");
		return this;
	}

	@Override
	public IApiBuilder defaultInterface(
			ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>> iface)
			throws ApiException {
		this.defaultInterface = Objects.requireNonNull(iface, "default interface cannot be null");
		return this;
	}

	/** Default DAO factory consulted by {@link DtoBuilder} when a dto sets no {@code .db(...)}; may be {@code null}. */
	public IDaoFactory getDefaultDaoFactory() {
		return this.defaultDaoFactory;
	}

}
