package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.ILinkedBuilder;
import com.garganttua.events.api.context.RouteStageDef;

public interface IRouteStageBuilder extends ILinkedBuilder<IRouteBuilder, RouteStageDef> {

	/**
	 * Sets the processor for this route stage: an {@code @Expression} function call (e.g.
	 * {@code filter_in(@exchange, ...)}, {@code set_header(@exchange, "k", "v")}) that the engine
	 * wraps as {@code exchange <- <processor>} so the stage transforms the routed exchange.
	 *
	 * @param processor the processor expression
	 * @return this builder
	 */
	IRouteStageBuilder processor(String processor);

	IRouteStageBuilder when(String condition);

	IRouteStageBuilder catch_(String catchExpr);

	IRouteStageBuilder catchDownstream(String catchExpr);
}
