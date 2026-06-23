package com.garganttua.events.core.dsl;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.core.dsl.DslException;
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
	private String to;
	private final List<RouteStageDef> stages = new ArrayList<>();
	private RouteExceptionsDef exceptions;
	private RouteSyncDef synchronization;

	public RouteBuilder(String uuid, String from) {
		this.uuid = uuid;
		this.from = from;
	}

	@Override
	public IRouteBuilder to(String subscriptionRef) {
		this.to = subscriptionRef;
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
		return new RouteDef(uuid, from, to, stages, exceptions, synchronization);
	}
}
