package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
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
	 * Synchronizes the route on a mutex resolved by name through the core {@code IMutexManager}. A
	 * plain {@code lock} uses the default in-JVM {@code InterruptibleLeaseMutex}; a qualified
	 * {@code Type::name} selects a registered {@code @MutexFactory}. A non-blank {@code lockObject}
	 * narrows the key.
	 *
	 * @param lock       the mutex name (plain or {@code Type::name})
	 * @param lockObject optional key-narrowing suffix, may be {@code null}
	 * @return this builder, for chaining
	 */
	IRouteBuilder synchronization(String lock, String lockObject);

	/**
	 * Synchronizes the route on a fixed {@link IMutex} instance: the mutex is registered as a bean and
	 * the per-message workflow runs inside it. {@code lockObject} is accepted for API symmetry but does
	 * not apply — a concrete mutex is a single instance and cannot be sub-keyed. Requires the route to
	 * be built through {@code EventsBuilder.context(...).route(...)} so the bean can be registered.
	 *
	 * @param mutex      the mutex instance to serialize this route on
	 * @param lockObject ignored for a directly-provided mutex, may be {@code null}
	 * @return this builder, for chaining
	 */
	IRouteBuilder synchronization(IMutex mutex, String lockObject);

	/**
	 * Synchronizes the route on the {@link IMutex} produced by the given supplier builder: the supplier
	 * is built once at engine wiring time, its mutex registered as a bean, and the per-message workflow
	 * runs inside it. {@code lockObject} is accepted for API symmetry but does not apply. Requires the
	 * route to be built through {@code EventsBuilder.context(...).route(...)}.
	 *
	 * @param mutexBuilder the supplier builder producing the mutex
	 * @param lockObject   ignored for a directly-provided mutex, may be {@code null}
	 * @return this builder, for chaining
	 */
	IRouteBuilder synchronization(ISupplierBuilder<IMutex, ISupplier<IMutex>> mutexBuilder, String lockObject);

	/**
	 * Synchronizes the route on an {@link IMutex} bean resolved from the injection context by the given
	 * garganttua bean reference (format {@code [provider::][class][!strategy][#name][@qualifier]}; a
	 * bare token is treated as the bean name). The referenced bean must be registered elsewhere.
	 * {@code lockObject} is accepted for API symmetry but does not apply.
	 *
	 * @param beanReference the bean reference identifying the mutex bean
	 * @param lockObject    ignored for a directly-provided mutex, may be {@code null}
	 * @return this builder, for chaining
	 */
	IRouteBuilder synchronizationBean(String beanReference, String lockObject);
}
