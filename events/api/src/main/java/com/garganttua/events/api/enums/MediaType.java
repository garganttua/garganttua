package com.garganttua.events.api.enums;

public enum MediaType {
	APPLICATION_JSON("application/json"),
	TEXT_PLAIN("text/plain");

	private final String value;

	MediaType(String value) {
		this.value = value;
	}

	@Override
	public String toString() {
		return this.value;
	}
}
