package com.garganttua.events.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.events.api.enums.MediaType;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

// EI_EXPOSE: in-flight transport record — headers/steps/value are intentionally shared
// by reference (and may be null) through the routing pipeline; a defensive copy would
// change runtime behaviour and break the null contract relied on by the expression layer.
@SuppressFBWarnings("EI_EXPOSE_REP")
@Reflected(queryAllDeclaredConstructors = true, queryAllDeclaredMethods = true, allDeclaredFields = true)
public record Message(
		Map<String, String> headers,
		String correlationId,
		String messageId,
		List<JourneyStep> steps,
		String tenantId,
		byte[] value,
		String contentType,
		String toUuid,
		String dataflowVersion) {

	public static Message create(String tenantId, MediaType mediaType, String version, byte[] bytes) {
		return new Message(
				new HashMap<>(),
				UUID.randomUUID().toString(),
				UUID.randomUUID().toString(),
				new ArrayList<>(),
				tenantId,
				bytes,
				mediaType.toString(),
				null,
				version);
	}

	public static Message fromExchange(Exchange exchange) {
		return new Message(
				exchange.headers(),
				exchange.correlationId(),
				exchange.messageId(),
				exchange.steps(),
				exchange.tenantId(),
				exchange.value(),
				exchange.contentType(),
				exchange.toUuid(),
				exchange.dataflowVersion());
	}
}
