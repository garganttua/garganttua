package com.garganttua.events.connectors.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.core.observability.Logger;

/**
 * Serialises an api business {@link IEvent} (the payload of an {@code api:operation:*}
 * {@link com.garganttua.core.observability.EndEvent}/{@code ErrorEvent}) to compact JSON bytes.
 *
 * <p>The {@code in}/{@code out} fields of an {@link IEvent} are arbitrary domain objects that may
 * not be Jackson-serialisable, so they are rendered via {@link Object#toString()} rather than
 * deep-serialised. Serialisation never throws: any failure falls back to a minimal JSON object.</p>
 */
public final class ApiEventCodec {

	private static final Logger LOG = Logger.getLogger(ApiEventCodec.class);

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Serialise the given business event to UTF-8 JSON bytes.
	 *
	 * @param event the business event to serialise; must not be {@code null}
	 * @return the JSON representation as UTF-8 bytes, never {@code null}
	 */
	public byte[] toBytes(IEvent event) {
		Map<String, Object> json = describe(event);
		try {
			return this.mapper.writeValueAsBytes(json);
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			LOG.warn("Failed to serialise api event: {}", e.getMessage());
			return "{\"type\":\"IEvent\"}".getBytes(StandardCharsets.UTF_8);
		}
	}

	private Map<String, Object> describe(IEvent event) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("operation", str(event.getOperation()));
		json.put("code", event.getCode() == null ? null : event.getCode().name());
		json.put("exceptionCode", event.getExceptionCode());
		json.put("exceptionMessage", event.getExceptionMessage());
		json.put("tenantId", event.getTenantId());
		json.put("ownerId", event.getOwnerId());
		json.put("userId", event.getUserId());
		json.put("inDate", str(event.getInDate()));
		json.put("outDate", str(event.getOutDate()));
		json.put("in", str(event.getIn()));
		json.put("out", str(event.getOut()));
		return json;
	}

	private String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
