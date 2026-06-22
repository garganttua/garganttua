package com.garganttua.events.api;

import java.util.Map;

import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.events.api.exceptions.EventsException;

public interface IDistributedLock extends ILifecycle {

	String getName();

	void configure(Map<String, String> configuration);

	void lock(String lockObject) throws EventsException;

	void unlock(String lockObject) throws EventsException;
}
