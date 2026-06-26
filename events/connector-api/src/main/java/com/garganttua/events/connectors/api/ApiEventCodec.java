package com.garganttua.events.connectors.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.core.observability.Logger;

/**
 * Serialises an api business {@link IEvent} (the payload of an {@code api:operation:*}
 * {@link com.garganttua.core.observability.EndEvent}/{@code ErrorEvent}) to compact JSON bytes.
 *
 * <p>The {@code in}/{@code out} fields of an {@link IEvent} are arbitrary domain objects that are
 * deep-serialised to a nested JSON node via Jackson ({@link ObjectMapper#valueToTree(Object)}), so a
 * standard POJO/record/Lombok entity becomes a nested object and a dataflow transform stage can
 * navigate it (e.g. {@code json_path(@exchange, "$.in.email")}). Jackson annotations on the entity
 * (e.g. {@code @JsonIgnore}) therefore exclude sensitive fields for free — no connector-side
 * configuration is needed for v1. Each payload is serialised in isolation: if {@code valueToTree}
 * throws on a non-serialisable object, that single field falls back to its
 * {@link String#valueOf(Object)} text; {@code null} stays {@code null}. Serialisation never throws:
 * any remaining failure falls back to a minimal JSON object.</p>
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
		OperationDefinition operation = event.getOperation();
		json.put("operation", str(operation));
		// Self-describing routing fields so a dataflow transform stage can discriminate by domain,
		// business operation or use case without re-parsing the source string.
		json.put("domain", operation == null ? null : operation.domainName());
		json.put("businessOperation",
				operation == null || operation.getBusinessOperation() == null
						? null : operation.getBusinessOperation().name());
		json.put("useCase", operation == null ? null : operation.useCaseName());
		json.put("code", event.getCode() == null ? null : event.getCode().name());
		json.put("exceptionCode", event.getExceptionCode());
		json.put("exceptionMessage", event.getExceptionMessage());
		json.put("tenantId", event.getTenantId());
		json.put("ownerId", event.getOwnerId());
		json.put("userId", event.getUserId());
		json.put("inDate", str(event.getInDate()));
		json.put("outDate", str(event.getOutDate()));
		// Deep-serialise the business payloads so they nest as JSON objects (json_path navigable).
		json.put("in", node(event.getIn()));
		json.put("out", node(event.getOut()));
		return json;
	}

	/**
	 * Deep-serialise a business payload to a Jackson node, isolating any failure: a non-serialisable
	 * object degrades to its {@link String#valueOf(Object)} text node rather than aborting the codec.
	 */
	private JsonNode node(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return this.mapper.valueToTree(value);
		} catch (RuntimeException e) {
			LOG.debug("Falling back to toString() for non-serialisable payload: {}", e.getMessage());
			return this.mapper.getNodeFactory().textNode(String.valueOf(value));
		}
	}

	private String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
