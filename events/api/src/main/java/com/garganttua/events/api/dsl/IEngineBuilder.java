package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.dependency.IDependentBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEngine;

public interface IEngineBuilder extends IDependentBuilder<IEngineBuilder, IEngine> {

	IEngineBuilder asset(String assetId);

	IEngineBuilder withPackage(String pkg);

	IContextBuilder context(String tenantId, String clusterId);

	IEngineBuilder source(String type, String configuration);

	IEngineBuilder connector(String connectorName);

	IEngineBuilder connector(IClass<? extends IConnector> connectorClass);

	IEngineBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorClass);

}
