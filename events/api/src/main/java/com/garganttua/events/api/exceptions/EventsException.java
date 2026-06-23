package com.garganttua.events.api.exceptions;

public class EventsException extends Exception {

	private static final long serialVersionUID = 1L;

	public EventsException(String message) {
		super(message);
	}

	public EventsException(Throwable cause) {
		super(cause);
	}

	public EventsException(String message, Throwable cause) {
		super(message, cause);
	}
}
