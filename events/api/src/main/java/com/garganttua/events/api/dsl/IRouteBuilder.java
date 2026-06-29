package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.RouteDef;

public interface IRouteBuilder extends ILinkedBuilder<IContextBuilder, RouteDef> {

	/**
	 * Adds an output subscription the processed exchange is broadcast to. Additive: calling
	 * {@code to(a).to(b)} fans the route out to both {@code a} and {@code b}; a single {@code to(x)}
	 * yields a one-element destination list.
	 *
	 * @param subscriptionRef the output subscription id to add
	 * @return this builder, for chaining
	 */
	IRouteBuilder to(String subscriptionRef);

	IRouteStageBuilder stage(String name);

	IRouteBuilder exceptions(String toSubscription, String cast, String label);

	IRouteBuilder synchronization(String lock, String lockObject);
}
