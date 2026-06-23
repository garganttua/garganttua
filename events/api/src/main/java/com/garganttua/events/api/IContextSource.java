package com.garganttua.events.api;

import java.util.List;

import com.garganttua.events.api.context.ContextDef;
import com.garganttua.events.api.exceptions.EventsException;

@FunctionalInterface
public interface IContextSource {

	List<ContextDef> load(String configuration) throws EventsException;
}
