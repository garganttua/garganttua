package com.garganttua.events.expressions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.IProducer;
import com.garganttua.events.api.JourneyStep;
import com.garganttua.events.api.Message;
import com.garganttua.events.api.enums.DestinationPolicy;
import com.garganttua.events.api.enums.Direction;
import com.garganttua.events.api.exceptions.ConnectorException;
import com.garganttua.events.api.exceptions.FilterException;
import com.garganttua.events.api.exceptions.HandlingException;
import com.jayway.jsonpath.JsonPath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventExpressions {

	private static final Logger log = LoggerFactory.getLogger(EventExpressions.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	private EventExpressions() {}

	@Expression(name = "protocol_in", description = "Deserialize encapsulated message envelope")
	public static Exchange protocolIn(Exchange exchange, String assetId,
			String clusterId, String subscriptionId, String version) throws HandlingException {
		byte[] body = exchange.value();
		String bodyString = new String(body, StandardCharsets.UTF_8);

		Message message;
		try {
			message = mapper.readValue(bodyString, Message.class);
		} catch (IOException e) {
			throw new HandlingException(e);
		}

		String id;
		if (message.steps() == null || message.steps().isEmpty()) {
			id = UUID.randomUUID().toString();
		} else {
			JourneyStep previousStep = message.steps().get(message.steps().size() - 1);
			id = previousStep.uuid();
		}

		JourneyStep step = new JourneyStep(new Date(), assetId, subscriptionId,
				Direction.IN, version, id, clusterId);

		return exchange
				.withCorrelationId(message.correlationId())
				.withDataflowVersion(message.dataflowVersion())
				.withSteps(message.steps())
				.withTenantId(message.tenantId())
				.withValue(message.value())
				.withHeaders(message.headers())
				.withContentType(message.contentType())
				.withToUuid(message.toUuid())
				.withStep(step);
	}

	@Expression(name = "protocol_out", description = "Serialize into encapsulated message envelope")
	public static Exchange protocolOut(Exchange exchange, String assetId,
			String clusterId, String topicRef, String version, String dataflowUuid,
			String connectorName, String subscriptionId) throws HandlingException {

		Exchange updated = exchange
				.withTo(connectorName, topicRef, dataflowUuid)
				.withMessageId(UUID.randomUUID().toString());

		JourneyStep step = new JourneyStep(new Date(), assetId, subscriptionId,
				Direction.OUT, version, UUID.randomUUID().toString(), clusterId);
		updated = updated.withStep(step);

		Message message = Message.fromExchange(updated);
		ObjectMapper om = new ObjectMapper();
		om.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		byte[] bytes;
		try {
			bytes = om.writeValueAsBytes(message);
		} catch (IOException e) {
			throw new HandlingException(e);
		}

		return updated.withValue(bytes);
	}

	@Expression(name = "filter_in", description = "Inbound filter: version, origin/destination policies")
	public static Exchange filterIn(Exchange exchange, String destinationPolicy,
			String originPolicy, String assetId, String clusterId, String version) throws FilterException {

		// Check dataflow version
		if (exchange.dataflowVersion() == null || !exchange.dataflowVersion().equals(version)) {
			throw new FilterException("version mismatch: expected " + version + ", got " + exchange.dataflowVersion());
		}

		// Check destination policy
		if (destinationPolicy != null) {
			DestinationPolicy dp = DestinationPolicy.valueOf(destinationPolicy);
			switch (dp) {
				case ONLY_TO_ASSET:
					if (exchange.toUuid() == null || !exchange.toUuid().equals(assetId)) {
						throw new FilterException("assetId mismatch");
					}
					break;
				case ONLY_TO_CLUSTER:
					if (exchange.toUuid() == null || !exchange.toUuid().equals(clusterId)) {
						throw new FilterException("clusterId mismatch");
					}
					break;
				case TO_ANY:
				default:
					break;
			}
		}

		return exchange;
	}

	@Expression(name = "filter_out", description = "Outbound destination policy filter")
	public static Exchange filterOut(Exchange exchange, String destinationPolicy) {
		if (destinationPolicy != null) {
			DestinationPolicy dp = DestinationPolicy.valueOf(destinationPolicy);
			switch (dp) {
				case ONLY_TO_ASSET:
				case ONLY_TO_CLUSTER:
					// toUuid is already set by context, keep as-is
					break;
				case TO_ANY:
				default:
					return exchange.withToUuid(null);
			}
		}
		return exchange;
	}

	@Expression(name = "set_header", description = "Set a header on the exchange")
	public static Exchange setHeader(Exchange exchange, String key, String value) {
		return exchange.withHeader(key, value);
	}

	@Expression(name = "get_header", description = "Get a header value from the exchange")
	public static String getHeader(Exchange exchange, String key) {
		return exchange.headers() != null ? exchange.headers().get(key) : null;
	}

	@Expression(name = "json_path", description = "Extract value from payload using JSONPath")
	public static Object jsonPath(Exchange exchange, String path) {
		InputStream is = new ByteArrayInputStream(exchange.value());
		return JsonPath.parse(is).read(path);
	}

	@Expression(name = "log", description = "Log exchange at specified level")
	public static Exchange log(Exchange exchange, String level) {
		String msg = "[Exchange:{}][CorrelationId:{}] value size={}";
		Object[] args = {
			exchange.exchangeId(),
			exchange.correlationId(),
			exchange.value() != null ? exchange.value().length : 0
		};
		switch (level.toUpperCase()) {
			case "DEBUG": log.debug(msg, args); break;
			case "WARN":  log.warn(msg, args); break;
			case "ERROR": log.error(msg, args); break;
			default:      log.info(msg, args); break;
		}
		return exchange;
	}

	@Expression(name = "produce", description = "Publish exchange to output connector")
	public static Exchange produce(Exchange exchange, IProducer producer) throws HandlingException {
		try {
			producer.publish(exchange.value());
		} catch (ConnectorException e) {
			throw new HandlingException(e);
		}
		return exchange;
	}

	@Expression(name = "route_to_error", description = "Route exchange to error subscription")
	public static Exchange routeToError(Exchange exchange, IProducer producer) throws HandlingException {
		try {
			producer.publish(exchange.value());
		} catch (ConnectorException e) {
			throw new HandlingException(e);
		}
		return exchange;
	}

	@Expression(name = "not_null", description = "Check if a value is not null")
	public static boolean notNull(Object value) {
		return value != null;
	}
}
