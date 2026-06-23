package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.dependency.IDependentBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEvents;

public interface IEventsBuilder extends IDependentBuilder<IEventsBuilder, IEvents> {

	IEventsBuilder asset(String assetId);

	IEventsBuilder withPackage(String pkg);

	IContextBuilder context(String tenantId, String clusterId);

	IEventsBuilder source(String type, String configuration);

	IEventsBuilder connector(String connectorName);

	IEventsBuilder connector(IClass<? extends IConnector> connectorClass);

	IEventsBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorClass);

}
