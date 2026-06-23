package com.garganttua.events.connectors.observability;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.ErrorEvent;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.StartEvent;

/**
 * Serialises an {@link ObservableEvent} to a compact JSON {@code byte[]} for the events pipeline.
 *
 * <p>The emitted object always carries {@code type}, {@code source}, {@code executionId} and
 * {@code timestamp}, plus type-specific fields ({@code code}/{@code durationMs} for
 * {@link EndEvent}, {@code error}/{@code durationMs} for {@link ErrorEvent}, {@code level}/
 * {@code message} for {@link LogEvent}) and the event {@code payload} rendered via
 * {@link Object#toString()} when present.</p>
 *
 * <p>Serialisation is defensive: it never throws. Any failure falls back to a minimal JSON object
 * describing the event type, so a single malformed payload can never break the pipeline.</p>
 */
public final class ObservableEventCodec {

	private static final Logger LOG = Logger.getLogger(ObservableEventCodec.class);

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Serialise the given event to UTF-8 JSON bytes.
	 *
	 * @param event the event to serialise; must not be {@code null}
	 * @return the JSON representation as UTF-8 bytes, never {@code null}
	 */
	public byte[] toBytes(ObservableEvent event) {
		Map<String, Object> json = describe(event);
		try {
			return this.mapper.writeValueAsBytes(json);
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			LOG.warn("Failed to serialise observable event of type {}: {}",
					event.getClass().getSimpleName(), e.getMessage());
			return minimal(event);
		}
	}

	private Map<String, Object> describe(ObservableEvent event) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("type", event.getClass().getSimpleName());
		json.put("source", event.source());
		json.put("executionId", String.valueOf(event.executionId()));
		json.put("timestamp", String.valueOf(event.timestamp()));
		addSpecifics(event, json);
		Object payload = event.payload();
		if (payload != null) {
			json.put("payload", String.valueOf(payload));
		}
		return json;
	}

	private void addSpecifics(ObservableEvent event, Map<String, Object> json) {
		switch (event) {
			case EndEvent end -> {
				json.put("code", end.code());
				putDurationMs(json, end.duration());
			}
			case ErrorEvent err -> {
				json.put("error", err.failure() == null ? null : String.valueOf(err.failure()));
				putDurationMs(json, err.duration());
			}
			case LogEvent log -> {
				json.put("level", log.level() == null ? null : log.level().name());
				json.put("message", log.message());
			}
			case StartEvent ignored -> {
				// start carries only the shared identity fields
			}
		}
	}

	private void putDurationMs(Map<String, Object> json, java.time.Duration duration) {
		if (duration != null) {
			json.put("durationMs", duration.toMillis());
		}
	}

	private byte[] minimal(ObservableEvent event) {
		String type = event.getClass().getSimpleName();
		return ("{\"type\":\"" + type + "\"}").getBytes(StandardCharsets.UTF_8);
	}
}
