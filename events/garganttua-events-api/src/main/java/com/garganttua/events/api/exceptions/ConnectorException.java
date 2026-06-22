package com.garganttua.events.api.exceptions;

public class ConnectorException extends EventsException {

	private static final long serialVersionUID = 1L;

	public ConnectorException(String message) {
		super(message);
	}

	public ConnectorException(Throwable cause) {
		super(cause);
	}

	public ConnectorException(String message, Throwable cause) {
		super(message, cause);
	}
}
