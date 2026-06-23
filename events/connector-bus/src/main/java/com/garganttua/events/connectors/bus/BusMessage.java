package com.garganttua.events.connectors.bus;

import java.nio.charset.StandardCharsets;

/**
 * Wire envelope for a bus message: a dataflow UUID paired with its raw payload.
 * Serialises to {@code dataflowUuid:payload} bytes (UTF-8).
 */
public record BusMessage(String dataflowUuid, byte[] value) {

	/** Defensive copy so the record stays immutable against the caller's array. */
	public BusMessage {
		if (value != null) {
			value = value.clone();
		}
	}

	/** Defensive copy so callers cannot mutate the record's internal array. */
	@Override
	public byte[] value() {
		return this.value == null ? null : this.value.clone();
	}

	public byte[] toBytes() {
		StringBuilder sb = new StringBuilder();
		sb.append(dataflowUuid);
		sb.append(":");
		sb.append(new String(this.value, StandardCharsets.UTF_8));
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	public static BusMessage fromBytes(byte[] bytes) {
		String s = new String(bytes, StandardCharsets.UTF_8);
		String[] splits = s.split(":", 2);
		return new BusMessage(splits[0], splits[1].getBytes(StandardCharsets.UTF_8));
	}
}
