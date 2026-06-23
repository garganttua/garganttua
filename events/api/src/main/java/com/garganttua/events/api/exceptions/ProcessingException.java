package com.garganttua.events.api.exceptions;

public class ProcessingException extends EventsException {

	private static final long serialVersionUID = 1L;

	public ProcessingException(String message) {
		super(message);
	}

	public ProcessingException(Throwable cause) {
		super(cause);
	}

	public ProcessingException(String message, Throwable cause) {
		super(message, cause);
	}
}
