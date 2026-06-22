package com.garganttua.events.core.dsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder;
import com.garganttua.core.dsl.dependency.DependencySpec;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEngine;
import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IEngineBuilder;
import com.garganttua.events.core.Engine;

public class EngineBuilder
		extends AbstractAutomaticDependentBuilder<IEngineBuilder, IEngine>
		implements IEngineBuilder {

	private static final Logger log = Logger.getLogger(EngineBuilder.class);

	private static final Set<DependencySpec> DEPENDENCIES = Set.of(
			DependencySpec.require(IClass.getClass(IInjectionContextBuilder.class)),
			DependencySpec.require(IClass.getClass(IExpressionContextBuilder.class)));

	private String assetId;
	private final List<String> packages = new ArrayList<>();
	private final List<ContextDef> contexts = new ArrayList<>();
	private final Map<String, IConnector> connectorRegistry = new HashMap<>();
	private final List<String> connectorNames = new ArrayList<>();
	private final List<IClass<? extends IConnector>> connectorClasses = new ArrayList<>();
	private final List<ISupplierBuilder<IConnector, ISupplier<IConnector>>> connectorSuppliers = new ArrayList<>();
	private IObservableBuilder<?, ?> injectionContextBuilder;
	private IObservableBuilder<?, ?> expressionContextBuilder;

	private EngineBuilder() {
		super(DEPENDENCIES);
	}

	public static IEngineBuilder builder() {
		return new EngineBuilder();
	}

	@Override
	public IEngineBuilder asset(String assetId) {
		this.assetId = assetId;
		return this;
	}

	@Override
	public IEngineBuilder withPackage(String pkg) {
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
	public IEngineBuilder source(String type, String configuration) {
		// Context source loading will be handled by JsonContextReader
		// For now, this is a placeholder for external source loading
		log.info("Context source registered: type={}, configuration={}", type, configuration);
		return this;
	}

	@Override
	public IEngineBuilder connector(String connectorName) {
		this.connectorNames.add(connectorName);
		log.debug("Connector registered by name: {}", connectorName);
		return this;
	}

	@Override
	public IEngineBuilder connector(IClass<? extends IConnector> connectorClass) {
		this.connectorClasses.add(connectorClass);
		log.debug("Connector registered by class: {}", connectorClass);
		return this;
	}

	@Override
	public IEngineBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorBuilder) {
		this.connectorSuppliers.add(connectorBuilder);
		log.debug("Connector registered by supplier builder");
		return this;
	}

	void addContext(ContextDef context) {
		this.contexts.add(context);
	}

	public void registerConnector(String type, String version, IConnector connector) {
		this.connectorRegistry.put(type + ":" + version, connector);
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
		if (dependency instanceof IInjectionContextBuilder) {
			this.injectionContextBuilder = (IObservableBuilder<?, ?>) dependency;
		} else if (dependency instanceof IExpressionContextBuilder) {
			this.expressionContextBuilder = (IObservableBuilder<?, ?>) dependency;
		}
	}

	@Override
	protected void doPostBuildWithDependency(Object dependency) {
		// No post-build processing needed
	}

	@Override
	protected IEngine doBuild() throws DslException {
		if (assetId == null || assetId.isEmpty()) {
			throw new DslException("assetId is required");
		}
		if (contexts.isEmpty()) {
			throw new DslException("At least one context is required");
		}

		return new Engine(assetId, contexts, connectorRegistry,
				injectionContextBuilder, expressionContextBuilder);
	}

	@SuppressWarnings("unchecked")
	@Override
	public IEngineBuilder provide(IObservableBuilder<?, ?> dependency) throws DslException {
		return super.provide(dependency);
	}
}
