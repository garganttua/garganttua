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

	/**
	 * Where a failed message goes, after how many attempts: the route runs it up to {@code attempts} times,
	 * waiting longer between each (from 200 ms, doubling, at most 5 s), before handing it to the error
	 * subscription — so that a transient failure does not send it there.
	 *
	 * @param toSubscription the error subscription
	 * @param cast           the cast applied to the failed exchange
	 * @param label          the label of the failure
	 * @param attempts       at least 1
	 * @return this builder
	 * @since 3.0.0-ALPHA34
	 */
	IRouteBuilder exceptions(String toSubscription, String cast, String label, int attempts);

	/**
	 * Runs each message of the route under a lock.
	 *
	 * @param lock       the lock — a plain name, or {@code Type::name} for a registered mutex type
	 * @param lockObject narrows it: a fixed key ({@code "reports"}), or — starting with {@code $} — a JSON path
	 *                   read on each message's payload ({@code "$.tenantId"}), so that messages of different keys
	 *                   do not wait for each other; a message where the path finds nothing takes the route's
	 *                   lock. {@code null}: one lock for the whole route
	 * @return this builder
	 */
	IRouteBuilder synchronization(String lock, String lockObject);
}
