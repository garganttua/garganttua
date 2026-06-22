package com.garganttua.events.api.exceptions;

public class HandlingException extends EventsException {

	private static final long serialVersionUID = 1L;

	public HandlingException(String message) {
		super(message);
	}

	public HandlingException(Throwable cause) {
		super(cause);
	}

	public HandlingException(String message, Throwable cause) {
		super(message, cause);
	}
}
