package com.garganttua.events.connectors.api;

import java.util.Locale;
import java.util.Map;

/**
 * How an {@code api} connector hands business events to its routes — read from its configuration.
 *
 * <table>
 * <caption>Configuration keys</caption>
 * <tr><th>Key</th><th>Default</th><th>Effect</th></tr>
 * <tr><td>{@code delivery}</td><td>{@code async}</td><td>{@code async}: the emitting thread only queues the
 * event; a thread of the connector runs the route. {@code sync}: no queue, the route runs while the operation
 * waits, and when it fails the OPERATION fails (503) — for a trail without which the data must not be
 * served.</td></tr>
 * <tr><td>{@code deliveryTimeout}</td><td>{@code 5000}</td><td>{@code sync} only: milliseconds the operation
 * waits for its route; beyond, it fails.</td></tr>
 * <tr><td>{@code overflow}</td><td>{@code drop}</td><td>{@code async} only, when the queue is full:
 * {@code drop} the event, or {@code block} the emitting thread until there is room.</td></tr>
 * <tr><td>{@code overflowTimeout}</td><td>{@code 1000}</td><td>{@code block} only: milliseconds the emitting
 * thread waits; beyond, the event is dropped.</td></tr>
 * <tr><td>{@code queueCapacity}</td><td>{@code 10000}</td><td>{@code async} only: events the queue holds.</td></tr>
 * </table>
 *
 * <p>A dropped event is counted ({@link ApiEventsConnector#droppedEvents()}) and still takes its number in the
 * sequence, so that downstream sees the gap.</p>
 *
 * @param delivery          how the route runs
 * @param deliveryTimeoutMs how long a {@code sync} operation waits for its route
 * @param overflow          what a full queue does
 * @param overflowTimeoutMs how long a {@code block}ed thread waits
 * @param queueCapacity     the queue's capacity
 */
public record ApiEventsSettings(Delivery delivery, long deliveryTimeoutMs, Overflow overflow,
		long overflowTimeoutMs, int queueCapacity) {

	/** How the route runs. */
	public enum Delivery {
		/** Queued, run by the connector's thread: the operation never waits. The default. */
		ASYNC,
		/** Run while the operation waits; its failure fails the operation. */
		SYNC
	}

	/** What a full queue does. */
	public enum Overflow {
		/** Drop the event, counted. The default: the operation never waits. */
		DROP,
		/** Wait for room, up to {@code overflowTimeout}; then drop, counted. */
		BLOCK
	}

	/** The connector's behaviour before these settings existed. */
	public static final ApiEventsSettings DEFAULTS = new ApiEventsSettings(Delivery.ASYNC, 5_000L, Overflow.DROP,
			1_000L, 10_000);

	/**
	 * Reads the settings from a connector configuration.
	 *
	 * @param configuration the connector's configuration
	 * @return the settings, defaults for absent keys
	 * @throws IllegalArgumentException when a value is not one of those documented
	 */
	public static ApiEventsSettings of(Map<String, String> configuration) {
		return new ApiEventsSettings(
				enumValue(configuration, "delivery", Delivery.class, DEFAULTS.delivery()),
				positive(configuration, "deliveryTimeout", DEFAULTS.deliveryTimeoutMs()),
				enumValue(configuration, "overflow", Overflow.class, DEFAULTS.overflow()),
				positive(configuration, "overflowTimeout", DEFAULTS.overflowTimeoutMs()),
				(int) positive(configuration, "queueCapacity", DEFAULTS.queueCapacity()));
	}

	private static <E extends Enum<E>> E enumValue(Map<String, String> configuration, String key, Class<E> type,
			E fallback) {
		String value = configuration.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("api connector: '" + key + "' is '" + value + "', expected one of "
					+ java.util.Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT), e);
		}
	}

	private static long positive(Map<String, String> configuration, String key, long fallback) {
		String value = configuration.get(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		long parsed;
		try {
			parsed = Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("api connector: '" + key + "' is '" + value + "', not a number", e);
		}
		if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("api connector: '" + key + "' must be positive, got " + parsed);
		}
		return parsed;
	}
}
