package com.garganttua.events.connectors.api;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.event.RequestOrigin;
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
		return write(describe(event));
	}

	/**
	 * Serialise a business event with its place in its consumer's sequence: {@code bootId} names the
	 * consumer's run, {@code seq} counts, from 1 and without gap, the events it took — so a consumer
	 * downstream sees a lost event as a jump. The event's identity and source node are the journey's
	 * ({@code JourneyStep}): the envelope carries them.
	 *
	 * @param event  the business event
	 * @param bootId the run of the consumer that took it
	 * @param seq    its number in that run
	 * @return the JSON representation as UTF-8 bytes, never {@code null}
	 */
	public byte[] toBytes(IEvent event, String bootId, long seq) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("bootId", bootId);
		json.put("seq", seq);
		json.putAll(describe(event));
		return write(json);
	}

	/**
	 * The last message of a run, sent on a clean stop: a run whose end never arrives ended abruptly, and
	 * whatever its queue still held is lost from {@code lastSeq + 1} on.
	 *
	 * @param bootId  the run
	 * @param lastSeq the number of the last event it took, 0 when none
	 * @return the JSON representation as UTF-8 bytes
	 */
	public byte[] endOfSequence(String bootId, long lastSeq) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("type", "endOfSequence");
		json.put("bootId", bootId);
		json.put("lastSeq", lastSeq);
		return write(json);
	}

	private byte[] write(Map<String, Object> json) {
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
		// The addressed entity (path {uuid}) — null for create / readAll / deleteAll.
		json.put("entityUuid", event.getEntityUuid());
		json.put("ownerId", event.getOwnerId());
		json.put("userId", event.getUserId());
		// Where the operation came from: client address, agent, verified session (never the token).
		RequestOrigin origin = event.getOrigin();
		json.put("origin", origin == null ? null : origin(origin));
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

	private static Map<String, Object> origin(RequestOrigin origin) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("address", origin.address());
		json.put("userAgent", origin.userAgent());
		json.put("sessionId", origin.sessionId());
		return json;
	}

	private String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
