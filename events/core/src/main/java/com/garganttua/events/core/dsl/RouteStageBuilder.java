package com.garganttua.events.core.dsl;

import com.garganttua.core.dsl.DslException;
import com.garganttua.events.api.context.RouteStageDef;
import com.garganttua.events.api.dsl.IRouteBuilder;
import com.garganttua.events.api.dsl.IRouteStageBuilder;

// Fluent DSL: the expression() builder method deliberately mirrors the field it sets; the method
// name is the public IRouteStageBuilder contract and cannot change.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class RouteStageBuilder implements IRouteStageBuilder {

	private IRouteBuilder parent;
	private final String name;
	private String expression;
	private String condition;
	private String catchExpression;
	private String catchDownstreamExpression;

	public RouteStageBuilder(String name) {
		this.name = name;
	}

	@Override
	public IRouteStageBuilder expression(String expr) {
		this.expression = expr;
		return this;
	}

	@Override
	public IRouteStageBuilder when(String condition) {
		this.condition = condition;
		return this;
	}

	@Override
	public IRouteStageBuilder catch_(String catchExpr) {
		this.catchExpression = catchExpr;
		return this;
	}

	@Override
	public IRouteStageBuilder catchDownstream(String catchExpr) {
		this.catchDownstreamExpression = catchExpr;
		return this;
	}

	@Override
	public IRouteBuilder up() {
		if (parent instanceof RouteBuilder rb) {
			rb.addStage(build());
		}
		return parent;
	}

	@Override
	public void setUp(IRouteBuilder up) {
		this.parent = up;
	}

	@Override
	public RouteStageDef build() throws DslException {
		return new RouteStageDef(name, expression, condition, catchExpression, catchDownstreamExpression);
	}
}
