package com.garganttua.events.core.dsl;

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
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEngine;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.core.Engine;

public class EventsBuilder
		extends AbstractAutomaticDependentBuilder<IEventsBuilder, IEngine>
		implements IEventsBuilder {

	private static final Logger log = Logger.getLogger(EventsBuilder.class);

	private static final Set<DependencySpec> DEPENDENCIES = Set.of(
			DependencySpec.require(IClass.getClass(IInjectionContextBuilder.class)),
			DependencySpec.require(IClass.getClass(IExpressionContextBuilder.class)));

	private String assetId;
	private final List<String> packages = new ArrayList<>();
	private final List<ContextDef> contexts = new ArrayList<>();
	private final Map<String, IClass<? extends IConnector>> connectorRegistry = new HashMap<>();
	private final List<String> connectorNames = new ArrayList<>();
	private final List<IClass<? extends IConnector>> connectorClasses = new ArrayList<>();
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
		this.connectorClasses.add(connectorClass);
		log.debug("Connector registered by class: {}", connectorClass);
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

	@Override
	protected void doAutoDetection() throws DslException {
		log.info("Auto-detection scanning packages: {}", packages);
		// Connector auto-detection would scan packages for @Connector annotations
		// This is simplified - real implementation would use annotation scanning
	}

	@Override
	protected void doAutoDetectionWithDependency(Object dependency) throws DslException {
		// Auto-detection with dependencies
	}

	@Override
	protected void doPreBuildWithDependency(Object dependency) {
		// Dependency BUILDERS are captured in provide() — Engine needs the injection/
		// expression builders to wire route workflows. The built contexts delivered
		// here (BUILT-kind deps) are used post-build for bean registration.
	}

	@Override
	protected void doPostBuildWithDependency(Object dependency) {
		// Like ApiBuilder: once the IInjectionContext is built, register the built
		// IEngine as a named bean so it is discoverable by the rest of the bootstrap.
		if (dependency instanceof IInjectionContext context && this.built != null) {
			registerEngineBean(context, this.built);
		}
	}

	private void registerEngineBean(IInjectionContext context, IEngine engine) {
		BeanReference<IEngine> reference = new BeanReference<>(
				IClass.getClass(IEngine.class),
				Optional.of(BeanStrategy.singleton),
				Optional.of("events"),
				Set.of());
		context.addBean(Predefined.BeanProviders.garganttua.toString(), reference, engine);
		log.debug("IEngine registered as bean 'events' in the injection context");
	}

	@Override
	protected IEngine doBuild() throws DslException {
		// Tolerate an unconfigured engine so garganttua-events can be
		// bootstrap-auto-loaded (mirrors ApiBuilder, which builds an empty IApi):
		// with no assetId / no contexts the engine is built idle and stays a no-op
		// until a config file or the DSL populates it.
		String effectiveAssetId = (assetId == null || assetId.isEmpty()) ? "default" : assetId;

		return new Engine(effectiveAssetId, contexts, connectorRegistry,
				injectionContextBuilder, expressionContextBuilder);
	}

	@Override
	public IEventsBuilder provide(IObservableBuilder<?, ?> dependency) throws DslException {
		// Capture the dependency BUILDERS here (Engine wires them into route workflows);
		// their built contexts are delivered separately via the dependency callbacks.
		if (dependency instanceof IInjectionContextBuilder) {
			this.injectionContextBuilder = dependency;
		} else if (dependency instanceof IExpressionContextBuilder) {
			this.expressionContextBuilder = dependency;
		}
		return super.provide(dependency);
	}
}
