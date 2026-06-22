package com.garganttua.events.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record Exchange(
		String exchangeId,
		String correlationId,
		String messageId,
		String tenantId,
		byte[] value,
		Map<String, String> headers,
		String contentType,
		List<JourneyStep> steps,
		String toUuid,
		String dataflowVersion,
		String fromConnector,
		String fromTopic,
		String fromDataflowUuid,
		String toConnector,
		String toTopic,
		String toDataflowUuid,
		@JsonIgnore Map<String, Object> properties) {

	public static Exchange create(String fromConnector, String fromTopic, String fromDataflowUuid, byte[] value) {
		return new Exchange(
				UUID.randomUUID().toString(),
				null, null, null,
				value,
				new HashMap<>(),
				null,
				new ArrayList<>(),
				null, null,
				fromConnector, fromTopic, fromDataflowUuid,
				null, null, null,
				new HashMap<>());
	}

	public Exchange withValue(byte[] v) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, v, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withHeader(String key, String val) {
		Map<String, String> newHeaders = new HashMap<>(headers);
		newHeaders.put(key, val);
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, newHeaders, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withCorrelationId(String corrId) {
		return new Exchange(exchangeId, corrId, messageId, tenantId, value, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withMessageId(String msgId) {
		return new Exchange(exchangeId, correlationId, msgId, tenantId, value, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withTenantId(String tid) {
		return new Exchange(exchangeId, correlationId, messageId, tid, value, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withContentType(String ct) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, ct,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withSteps(List<JourneyStep> newSteps) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				newSteps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withStep(JourneyStep step) {
		List<JourneyStep> newSteps = new ArrayList<>(steps);
		newSteps.add(step);
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				newSteps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withToUuid(String uuid) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				steps, uuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withDataflowVersion(String version) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				steps, toUuid, version, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withHeaders(Map<String, String> h) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, h, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, properties);
	}

	public Exchange withTo(String connector, String topic, String dataflowUuid) {
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				connector, topic, dataflowUuid, properties);
	}

	public Exchange withProperty(String key, Object val) {
		Map<String, Object> newProps = new HashMap<>(properties);
		newProps.put(key, val);
		return new Exchange(exchangeId, correlationId, messageId, tenantId, value, headers, contentType,
				steps, toUuid, dataflowVersion, fromConnector, fromTopic, fromDataflowUuid,
				toConnector, toTopic, toDataflowUuid, newProps);
	}
}
