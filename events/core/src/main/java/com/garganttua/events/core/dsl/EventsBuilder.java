package com.garganttua.events.core.dsl;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.dsl.annotations.ConfigurableBuilder;
import com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder;
import com.garganttua.core.dsl.dependency.DependencySpec;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.BeanStrategy;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.Predefined;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEvents;
import com.garganttua.events.api.connectors.annotations.Connector;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.core.Events;

// Class-level "unchecked": the @Connector IClass<?> -> IClass<? extends IConnector> casts are
// pervasive across the connector(...) overloads and bean-registration helpers; they are guarded by
// the @Connector marker check at registration time.
@SuppressWarnings("unchecked")
@ConfigurableBuilder("events")
public class EventsBuilder
		extends AbstractAutomaticDependentBuilder<IEventsBuilder, IEvents>
		implements IEventsBuilder {

	private static final Logger log = Logger.getLogger(EventsBuilder.class);

	// Depend on the bootstrap-provided IScriptsBuilder (not a bare expression builder): it carries
	// the full Workflows → Scripts → {Expression, Runtimes, ClassLoader} execution chain, so route
	// workflows can actually RUN their @Expression stages. A bare ExpressionContextBuilder lacks the
	// scripts/runtime layers, so stages compiled but never executed.
	//
	// ALSO depend on the IExpressionContextBuilder — the SAME shared instance ScriptsBuilder builds
	// its IExpressionContext from — so events can configure it like garganttua-api does: enable
	// auto-detect, register the framework function packages, and (via withPackage) propagate
	// application packages into the scan. Captured + configured in provide(), which the bootstrap
	// calls during dependency resolution BEFORE any builder.build(), so the configuration lands
	// before the expression context is scanned. Without it, an app's @Expression methods declared
	// via the events DSL never reach the scan and route stages fail with "Undefined function".
	private static final Set<DependencySpec> DEPENDENCIES = Set.of(
			DependencySpec.require(IClass.getClass(IInjectionContextBuilder.class)),
			DependencySpec.require(IClass.getClass(IExpressionContextBuilder.class)),
			DependencySpec.require(IClass.getClass(IScriptsBuilder.class)));

	/**
	 * Framework {@code @Expression} function packages registered on the shared expression context so
	 * core built-ins (literal wrappers, script ops, observability markers) resolve in route stages —
	 * mirrors {@code ApiBuilderBuild.configureExpressionContext} minus the api-specific package.
	 */
	private static final List<String> FRAMEWORK_FUNCTION_PACKAGES = List.of(
			"com.garganttua.core.expression.functions",
			"com.garganttua.core.script.functions",
			"com.garganttua.core.observability");

	/** The garganttua bean-provider scope connectors are registered under. */
	private static final String CONNECTOR_PROVIDER = Predefined.BeanProviders.garganttua.toString();
	/** Single-colon scheme detector ({@code provider:rest}, where {@code rest} is not another colon). */
	private static final java.util.regex.Pattern SINGLE_COLON_SCHEME =
			java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*:[^:].*");

	/**
	 * A pending connector bean-reference declared via {@link #connector(String)}: the resolved
	 * connector class plus the parsed provider/strategy/name honored at registration time.
	 *
	 * @param provider the optional target bean-provider name
	 * @param type     the resolved connector class
	 * @param strategy the optional bean strategy
	 * @param name     the optional bean name
	 */
	private record ConnectorReference(Optional<String> provider, IClass<? extends IConnector> type,
			Optional<BeanStrategy> strategy, Optional<String> name) {
	}

	// package-private so the @ConfigurableBuilder population test can assert it; written via asset(...).
	String assetId;
	private final List<String> packages = new ArrayList<>();
	private final List<ContextDef> contexts = new ArrayList<>();
	private final Map<String, IClass<? extends IConnector>> connectorRegistry = new HashMap<>();
	// Connector resolution flows through @Connector auto-detection (doAutoDetection) and the
	// connector(IClass) DSL path — both populate connectorRegistry keyed "type:version".
	// The lists below back the other connector(...) overloads; registerConnectorBeans consumes
	// all of them at post-build time, registering each as a bean in the injection context.
	private final List<ISupplierBuilder<IConnector, ISupplier<IConnector>>> connectorSuppliers = new ArrayList<>();
	private final List<IConnector> connectorInstances = new ArrayList<>();
	private final List<ConnectorReference> connectorReferences = new ArrayList<>();
	private IObservableBuilder<?, ?> injectionContextBuilder;
	private IObservableBuilder<?, ?> scriptsBuilder;
	// The shared expression context builder, captured in provide() and configured for api parity so
	// route stages resolve framework + application @Expression functions.
	private IExpressionContextBuilder expressionContextBuilder;

	private EventsBuilder() {
		super(DEPENDENCIES);
	}

	public static IEventsBuilder builder() {
		return new EventsBuilder();
	}

	@Override
	public IEventsBuilder asset(String assetId) {
		this.assetId = assetId;
		return this;
	}

	@Override
	public IEventsBuilder withPackage(String pkg) {
		this.packages.add(pkg);
		// Propagate the application package into the shared expression context so its @Expression
		// methods resolve in route stages (api parity). If the expression builder is not captured yet
		// (DSL order), provide() replays the accumulated packages when it arrives.
		if (this.expressionContextBuilder != null) {
			this.expressionContextBuilder.withPackage(pkg);
		}
		return this;
	}

	@Override
	public IContextBuilder context(String tenantId, String clusterId) {
		ContextBuilder builder = new ContextBuilder(tenantId, clusterId);
		builder.setUp(this);
		return builder;
	}

	@Override
	public IEventsBuilder source(String type, String configuration) {
		// Context source loading will be handled by JsonContextReader
		// For now, this is a placeholder for external source loading
		log.info("Context source registered: type={}, configuration={}", type, configuration);
		return this;
	}

	@Override
	public IEventsBuilder connector(String url) {
		String normalized = normalizeConnectorUrl(url);
		try {
			// Parse the bean-reference segments directly: BeanReference.parse() defers class
			// resolution and rejects a class-only reference, so we read provider/strategy/name
			// via the segment extractors and resolve the class ourselves.
			String classFqn = BeanReference.extractClass(normalized)
					.orElseThrow(() -> new DslException(
							"Connector URL " + url + " does not name a connector class"));
			Optional<String> provider = BeanReference.extractProvider(normalized);
			Optional<BeanStrategy> strategy = BeanReference.extractStrategy(normalized)
					.map(s -> BeanStrategy.valueOf(s.toLowerCase(java.util.Locale.ROOT)));
			Optional<String> name = BeanReference.extractName(normalized);
			IClass<? extends IConnector> connectorClass =
					(IClass<? extends IConnector>) (IClass<?>) IClass.forName(classFqn);
			this.connectorReferences.add(
					new ConnectorReference(provider, connectorClass, strategy, name));
			log.debug("Connector registered by URL: {} (class {})", url, classFqn);
		} catch (DslException e) {
			throw e;
		} catch (Exception e) {
			throw new DslException("Failed to resolve connector URL " + url + ": " + e.getMessage(), e);
		}
		return this;
	}

	/** Rewrites a single-colon scheme {@code provider:rest} to the core {@code provider::rest} form. */
	private static String normalizeConnectorUrl(String url) {
		if (url != null && SINGLE_COLON_SCHEME.matcher(url).matches()) {
			return url.replaceFirst(":", "::");
		}
		return url;
	}

	@Override
	public IEventsBuilder connector(IClass<? extends IConnector> connectorClass) {
		// Mirror auto-detection: read the @Connector marker and register under "type:version".
		Connector meta = connectorMeta(connectorClass, connectorClass.getName());
		registerConnector(meta.type(), meta.version(), connectorClass);
		log.debug("Connector registered by class: {} ({}:{})",
				connectorClass.getName(), meta.type(), meta.version());
		return this;
	}

	@Override
	public IEventsBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorBuilder) {
		this.connectorSuppliers.add(connectorBuilder);
		log.debug("Connector registered by supplier builder");
		return this;
	}

	@Override
	public IEventsBuilder connector(IConnector connector) {
		// Validate the @Connector marker eagerly so a misconfigured instance fails fast.
		connectorMeta(IClass.getClass(connector.getClass()), connector.getClass().getName());
		this.connectorInstances.add(connector);
		log.debug("Connector registered by instance: {}", connector.getClass().getName());
		return this;
	}

	/** Reads the {@code @Connector} marker off a connector class, throwing {@link DslException} if absent. */
	private static Connector connectorMeta(IClass<?> connectorClass, String label) {
		Connector meta = ((Class<?>) connectorClass.getType()).getAnnotation(Connector.class);
		if (meta == null) {
			throw new DslException("Connector class " + label
					+ " is not annotated with @Connector — cannot resolve its type/version");
		}
		return meta;
	}

	void addContext(ContextDef context) {
		this.contexts.add(context);
	}

	public void registerConnector(String type, String version, IClass<? extends IConnector> connectorClass) {
		this.connectorRegistry.put(type + ":" + version, connectorClass);
	}

	/** Package-private accessor for tests asserting auto/manual registration. */
	Map<String, IClass<? extends IConnector>> registeredConnectors() {
		return this.connectorRegistry;
	}

	/** Package-private accessor for tests asserting {@link #connector(IConnector)} population. */
	int connectorInstanceCount() {
		return this.connectorInstances.size();
	}

	/** Package-private accessor for tests asserting {@link #connector(String)} population. */
	int connectorReferenceCount() {
		return this.connectorReferences.size();
	}

	/** Package-private accessor for tests asserting the supplier overload population. */
	int connectorSupplierCount() {
		return this.connectorSuppliers.size();
	}

	/** Package-private accessor for tests asserting {@link #context(String, String)} population. */
	int contextCount() {
		return this.contexts.size();
	}

	@Override
	protected void doAutoDetection() throws DslException {
		// Mirrors InjectionAutoDetector / ApiBuilderAssetDetection: discover @Connector classes via
		// IReflection and register each under "type:version". A GLOBAL scan is used so that merely
		// having a connector JAR on the classpath auto-registers it ("batteries-included"), matching
		// garganttua-events being a bootstrap auto-loaded module.
		IReflection reflection;
		try {
			reflection = IClass.getReflection();
		} catch (Exception e) {
			log.warn("No IReflection available for @Connector auto-detection: {}", e.getMessage());
			return;
		}
		IClass<? extends Annotation> annotation =
				(IClass<? extends Annotation>) reflection.getClass(Connector.class);
		reflection.getClassesWithAnnotation(annotation)
				.forEach(clazz -> registerDiscovered(clazz));
	}

	private void registerDiscovered(IClass<?> clazz) {
		try {
			if (!IConnector.class.isAssignableFrom((Class<?>) clazz.getType())) {
				log.warn("Class {} annotated with @Connector but does not implement IConnector",
						clazz.getName());
				return;
			}
			Connector meta = ((Class<?>) clazz.getType()).getAnnotation(Connector.class);
			registerConnector(meta.type(), meta.version(), (IClass<? extends IConnector>) clazz);
			log.info("Auto-registered @Connector {} ({}:{})",
					clazz.getName(), meta.type(), meta.version());
		} catch (Exception e) {
			log.warn("Failed to auto-register @Connector {}: {}", clazz.getName(), e.getMessage());
		}
	}

	@Override
	protected void doAutoDetectionWithDependency(Object dependency) throws DslException {
		// Auto-detection with dependencies
	}

	@Override
	protected void doPreBuildWithDependency(Object dependency) {
		// Dependency BUILDERS are captured in provide() — Events needs the injection/
		// expression builders to wire route workflows. The built contexts delivered
		// here (BUILT-kind deps) are used post-build for bean registration.
	}

	@Override
	protected void doPostBuildWithDependency(Object dependency) {
		// Like ApiBuilder: once the IInjectionContext is built, register the built IEvents and
		// every declared connector as beans, then hand the context to the engine so it can
		// resolve connectors as beans at init time (with reflective fallback intact).
		if (dependency instanceof IInjectionContext context && this.built != null) {
			registerEventsBean(context, this.built);
			registerConnectorBeans(context);
			if (this.built instanceof Events events) {
				events.setInjectionContext(context);
			}
		}
	}

	private void registerEventsBean(IInjectionContext context, IEvents events) {
		BeanReference<IEvents> reference = new BeanReference<>(
				IClass.getClass(IEvents.class),
				Optional.of(BeanStrategy.singleton),
				Optional.of("events"),
				Set.of());
		context.addBean(CONNECTOR_PROVIDER, reference, events);
		log.debug("IEvents registered as bean 'events' in the injection context");
	}

	/**
	 * Registers every declared connector into the injection context as a {@code @Connector}-qualified
	 * bean named {@code connector:type:version}: auto-detected / by-class connectors as prototype
	 * by-class beans (fresh instance per resolution), direct instances and supplier-built connectors
	 * as singleton instance beans, and URL references as prototype by-class beans honoring any parsed
	 * provider / strategy / name.
	 *
	 * @param context the built injection context
	 */
	private void registerConnectorBeans(IInjectionContext context) {
		IClass<? extends Annotation> qualifier = IClass.getClass(Connector.class);
		connectorRegistry.values().forEach(clazz -> registerPrototypeConnector(context, clazz, qualifier));
		connectorReferences.forEach(reference -> registerReferenceConnector(context, reference, qualifier));
		connectorInstances.forEach(instance -> registerSingletonConnector(context, instance, qualifier));
		connectorSuppliers.forEach(supplier -> registerSuppliedConnector(context, supplier, qualifier));
	}

	private void registerPrototypeConnector(IInjectionContext context,
			IClass<? extends IConnector> clazz, IClass<? extends Annotation> qualifier) {
		Connector meta = ((Class<?>) clazz.getType()).getAnnotation(Connector.class);
		String name = connectorBeanName(meta.type(), meta.version());
		BeanReference<IConnector> reference = new BeanReference<>(
				(IClass<IConnector>) (IClass<?>) clazz,
				Optional.of(BeanStrategy.prototype), Optional.of(name), Set.of(qualifier));
		context.addBean(CONNECTOR_PROVIDER, reference);
		log.debug("Connector bean '{}' registered (prototype, class {})", name, clazz.getName());
	}

	private void registerReferenceConnector(IInjectionContext context,
			ConnectorReference reference, IClass<? extends Annotation> qualifier) {
		Connector meta = connectorMeta(reference.type(), reference.type().getName());
		String name = reference.name().orElse(connectorBeanName(meta.type(), meta.version()));
		BeanStrategy strategy = reference.strategy().orElse(BeanStrategy.prototype);
		String provider = reference.provider().orElse(CONNECTOR_PROVIDER);
		BeanReference<IConnector> beanRef = new BeanReference<>(
				(IClass<IConnector>) (IClass<?>) reference.type(),
				Optional.of(strategy), Optional.of(name), Set.of(qualifier));
		context.addBean(provider, beanRef);
		log.debug("Connector bean '{}' registered from URL (provider {}, class {})",
				name, provider, reference.type().getName());
	}

	private void registerSingletonConnector(IInjectionContext context,
			IConnector instance, IClass<? extends Annotation> qualifier) {
		Connector meta = connectorMeta(IClass.getClass(instance.getClass()), instance.getClass().getName());
		String name = connectorBeanName(meta.type(), meta.version());
		BeanReference<IConnector> reference = new BeanReference<>(
				IClass.getClass(IConnector.class),
				Optional.of(BeanStrategy.singleton), Optional.of(name), Set.of(qualifier));
		context.addBean(CONNECTOR_PROVIDER, reference, instance);
		log.debug("Connector bean '{}' registered (singleton instance, class {})",
				name, instance.getClass().getName());
	}

	private void registerSuppliedConnector(IInjectionContext context,
			ISupplierBuilder<IConnector, ISupplier<IConnector>> supplierBuilder,
			IClass<? extends Annotation> qualifier) {
		try {
			IConnector instance = supplierBuilder.build().supply()
					.orElseThrow(() -> new DslException("Connector supplier produced no instance"));
			registerSingletonConnector(context, instance, qualifier);
		} catch (DslException e) {
			throw e;
		} catch (Exception e) {
			throw new DslException("Failed to build connector from supplier: " + e.getMessage(), e);
		}
	}

	/** Builds the {@code connector:type:version} bean name shared by registration and resolution. */
	private static String connectorBeanName(String type, String version) {
		return "connector:" + type + ":" + version;
	}

	@Override
	protected IEvents doBuild() throws DslException {
		// Tolerate an unconfigured engine so garganttua-events can be
		// bootstrap-auto-loaded (mirrors ApiBuilder, which builds an empty IApi):
		// with no assetId / no contexts the engine is built idle and stays a no-op
		// until a config file or the DSL populates it.
		String effectiveAssetId = (assetId == null || assetId.isEmpty()) ? "default" : assetId;

		return new Events(effectiveAssetId, contexts, connectorRegistry,
				injectionContextBuilder, scriptsBuilder);
	}

	@Override
	public IEventsBuilder provide(IObservableBuilder<?, ?> dependency) throws DslException {
		// Capture the dependency BUILDERS here (Events wires them into route workflows);
		// their built contexts are delivered separately via the dependency callbacks.
		if (dependency instanceof IInjectionContextBuilder) {
			this.injectionContextBuilder = dependency;
		} else if (dependency instanceof IExpressionContextBuilder expressionBuilder) {
			this.expressionContextBuilder = expressionBuilder;
			configureExpressionContext(expressionBuilder);
		} else if (dependency instanceof IScriptsBuilder) {
			this.scriptsBuilder = dependency;
		}
		return super.provide(dependency);
	}

	/**
	 * Configures the captured shared expression context for api parity: enables auto-detect, registers
	 * the framework {@code @Expression} function packages, and replays every application package
	 * already declared via {@link #withPackage(String)} so route stages resolve both framework
	 * built-ins and application functions. Idempotent — {@code autoDetect} is guarded and packages go
	 * into a set — so configuring the same shared builder from both events and api is safe.
	 *
	 * @param builder the shared expression context builder to configure
	 */
	private void configureExpressionContext(IExpressionContextBuilder builder) {
		if (!builder.isAutoDetected()) {
			builder.autoDetect(true);
		}
		FRAMEWORK_FUNCTION_PACKAGES.forEach(builder::withPackage);
		this.packages.forEach(builder::withPackage);
	}
}
