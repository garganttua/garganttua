package com.garganttua.events.core.dsl;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.context.RouteDef;
import com.garganttua.events.api.context.RouteExceptionsDef;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.context.RouteSyncDef;
import com.garganttua.events.api.dsl.IContextBuilder;
import com.garganttua.events.api.dsl.IRouteBuilder;
import com.garganttua.events.api.dsl.IRouteStageBuilder;

// Fluent DSL: builder method names (to(), exceptions(), synchronization()) deliberately mirror the
// fields they set; the method names are the public IRouteBuilder contract and cannot change.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class RouteBuilder implements IRouteBuilder {

	private IContextBuilder parent;
	private final String uuid;
	private final String from;
	private final List<String> tos = new ArrayList<>();
	private final List<RouteStageDef> stages = new ArrayList<>();
	private RouteExceptionsDef exceptions;
	private RouteSyncDef synchronization;

	public RouteBuilder(String uuid, String from) {
		this.uuid = uuid;
		this.from = from;
	}

	@Override
	public IRouteBuilder to(String subscriptionRef) {
		this.tos.add(subscriptionRef);
		return this;
	}

	@Override
	public IRouteStageBuilder stage(String name) {
		RouteStageBuilder stageBuilder = new RouteStageBuilder(name);
		stageBuilder.setUp(this);
		return stageBuilder;
	}

	@Override
	public IRouteBuilder exceptions(String toSubscription, String cast, String label) {
		this.exceptions = new RouteExceptionsDef(toSubscription, cast, label);
		return this;
	}

	@Override
	public IRouteBuilder synchronization(String lock, String lockObject) {
		this.synchronization = new RouteSyncDef(lock, lockObject);
		return this;
	}

	@Override
	public IRouteBuilder synchronization(IMutex mutex, String lockObject) {
		String beanName = requireEventsBuilder("synchronization(IMutex)").registerRouteMutex(uuid, mutex);
		this.synchronization = new RouteSyncDef(null, lockObject, beanName);
		return this;
	}

	@Override
	public IRouteBuilder synchronization(ISupplierBuilder<IMutex, ISupplier<IMutex>> mutexBuilder,
			String lockObject) {
		String beanName = requireEventsBuilder("synchronization(ISupplierBuilder)")
				.registerRouteMutex(uuid, mutexBuilder);
		this.synchronization = new RouteSyncDef(null, lockObject, beanName);
		return this;
	}

	@Override
	public IRouteBuilder synchronizationBean(String beanReference, String lockObject) {
		this.synchronization = new RouteSyncDef(null, lockObject, beanReference);
		return this;
	}

	/**
	 * The enclosing {@link EventsBuilder}, reached through the context-builder parent, needed to
	 * register a route-supplied mutex as a bean. Fails when the route was not built through the events
	 * DSL chain (where such registration has no home).
	 */
	private EventsBuilder requireEventsBuilder(String form) {
		if (parent instanceof ContextBuilder cb) {
			EventsBuilder eventsBuilder = cb.eventsBuilder();
			if (eventsBuilder != null) {
				return eventsBuilder;
			}
		}
		throw new DslException("route " + uuid + ": " + form + " requires the route to be built through "
				+ "EventsBuilder.context(...).route(...) so the mutex can be registered as a bean");
	}

	void addStage(RouteStageDef stage) {
		this.stages.add(stage);
	}

	@Override
	public IContextBuilder up() {
		if (parent instanceof ContextBuilder cb) {
			cb.addRoute(build());
		}
		return parent;
	}

	@Override
	public void setUp(IContextBuilder up) {
		this.parent = up;
	}

	@Override
	public RouteDef build() throws DslException {
		return new RouteDef(uuid, from, tos, stages, exceptions, synchronization);
	}
}
