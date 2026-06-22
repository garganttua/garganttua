package com.garganttua.events.connectors.bus;

public record BusMessage(String dataflowUuid, byte[] value) {

	public byte[] toBytes() {
		StringBuilder sb = new StringBuilder();
		sb.append(dataflowUuid);
		sb.append(":");
		sb.append(new String(value));
		return sb.toString().getBytes();
	}

	public static BusMessage fromBytes(byte[] bytes) {
		String s = new String(bytes);
		String[] splits = s.split(":", 2);
		return new BusMessage(splits[0], splits[1].getBytes());
	}
}
