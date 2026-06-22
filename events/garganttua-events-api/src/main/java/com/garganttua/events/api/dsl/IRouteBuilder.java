package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.RouteDef;

public interface IRouteBuilder extends ILinkedBuilder<IContextBuilder, RouteDef> {

	IRouteBuilder to(String subscriptionRef);

	IRouteStageBuilder stage(String name);

	IRouteBuilder exceptions(String toSubscription, String cast, String label);

	IRouteBuilder synchronization(String lock, String lockObject);
}
