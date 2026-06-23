package com.garganttua.events.api.exceptions;

public class FilterException extends HandlingException {

	private static final long serialVersionUID = 1L;

	public FilterException(String message) {
		super(message);
	}

	public FilterException(Throwable cause) {
		super(cause);
	}

	public FilterException(String message, Throwable cause) {
		super(message, cause);
	}
}
