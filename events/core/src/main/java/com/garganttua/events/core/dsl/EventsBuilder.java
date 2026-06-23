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
import com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder;
import com.garganttua.core.dsl.dependency.DependencySpec;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
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

public class EventsBuilder
		extends AbstractAutomaticDependentBuilder<IEventsBuilder, IEvents>
		implements IEventsBuilder {

	private static final Logger log = Logger.getLogger(EventsBuilder.class);

	private static final Set<DependencySpec> DEPENDENCIES = Set.of(
			DependencySpec.require(IClass.getClass(IInjectionContextBuilder.class)),
			DependencySpec.require(IClass.getClass(IExpressionContextBuilder.class)));

	private String assetId;
	private final List<String> packages = new ArrayList<>();
	private final List<ContextDef> contexts = new ArrayList<>();
	private final Map<String, IClass<? extends IConnector>> connectorRegistry = new HashMap<>();
	// Connector resolution flows through @Connector auto-detection (doAutoDetection) and the
	// connector(IClass) DSL path — both populate connectorRegistry keyed "type:version".
	// connectorNames / connectorSuppliers back the public IEventsBuilder overloads that have no
	// resolution mechanism yet (honest stubs pending a connector-resolution design decision).
	private final List<String> connectorNames = new ArrayList<>();
	private final List<ISupplierBuilder<IConnector, ISupplier<IConnector>>> connectorSuppliers = new ArrayList<>();
	private IObservableBuilder<?, ?> injectionContextBuilder;
	private IObservableBuilder<?, ?> expressionContextBuilder;

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
	public IEventsBuilder connector(String connectorName) {
		this.connectorNames.add(connectorName);
		log.debug("Connector registered by name: {}", connectorName);
		return this;
	}

	@Override
	public IEventsBuilder connector(IClass<? extends IConnector> connectorClass) {
		// Mirror auto-detection: read the @Connector marker and register under "type:version".
		Connector meta = ((Class<?>) connectorClass.getType()).getAnnotation(Connector.class);
		if (meta == null) {
			throw new DslException("Connector class " + connectorClass.getName()
					+ " is not annotated with @Connector — cannot resolve its type/version");
		}
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

	@Override
	@SuppressWarnings("unchecked")
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

	@SuppressWarnings("unchecked")
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
		// Like ApiBuilder: once the IInjectionContext is built, register the built
		// IEvents as a named bean so it is discoverable by the rest of the bootstrap.
		if (dependency instanceof IInjectionContext context && this.built != null) {
			registerEventsBean(context, this.built);
		}
	}

	private void registerEventsBean(IInjectionContext context, IEvents events) {
		BeanReference<IEvents> reference = new BeanReference<>(
				IClass.getClass(IEvents.class),
				Optional.of(BeanStrategy.singleton),
				Optional.of("events"),
				Set.of());
		context.addBean(Predefined.BeanProviders.garganttua.toString(), reference, events);
		log.debug("IEvents registered as bean 'events' in the injection context");
	}

	@Override
	protected IEvents doBuild() throws DslException {
		// Tolerate an unconfigured engine so garganttua-events can be
		// bootstrap-auto-loaded (mirrors ApiBuilder, which builds an empty IApi):
		// with no assetId / no contexts the engine is built idle and stays a no-op
		// until a config file or the DSL populates it.
		String effectiveAssetId = (assetId == null || assetId.isEmpty()) ? "default" : assetId;

		return new Events(effectiveAssetId, contexts, connectorRegistry,
				injectionContextBuilder, expressionContextBuilder);
	}

	@Override
	public IEventsBuilder provide(IObservableBuilder<?, ?> dependency) throws DslException {
		// Capture the dependency BUILDERS here (Events wires them into route workflows);
		// their built contexts are delivered separately via the dependency callbacks.
		if (dependency instanceof IInjectionContextBuilder) {
			this.injectionContextBuilder = dependency;
		} else if (dependency instanceof IExpressionContextBuilder) {
			this.expressionContextBuilder = dependency;
		}
		return super.provide(dependency);
	}
}
