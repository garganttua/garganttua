package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.RouteStageDef;

public interface IRouteStageBuilder extends ILinkedBuilder<IRouteBuilder, RouteStageDef> {

	IRouteStageBuilder expression(String expr);

	IRouteStageBuilder when(String condition);

	IRouteStageBuilder catch_(String catchExpr);

	IRouteStageBuilder catchDownstream(String catchExpr);
}
