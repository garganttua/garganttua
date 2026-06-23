package com.garganttua.api.commons.event;

@FunctionalInterface
public interface IEventPublisher{
	
	public void publishEvent(IEvent event);
		
}
