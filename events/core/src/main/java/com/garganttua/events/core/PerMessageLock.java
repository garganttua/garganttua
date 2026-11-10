package com.garganttua.events.core;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.mutex.IMutexManager;
import com.garganttua.core.mutex.MutexException;
import com.garganttua.core.mutex.MutexName;
import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.Exchange;
import com.garganttua.events.api.Message;
import com.jayway.jsonpath.JsonPath;

/**
 * The lock of a route whose {@code lockObject} is a JSON path ({@code $.tenantId}): resolved for each
 * message, {@code <lock>:<value>}, so that messages of different keys do not wait for each other.
 *
 * <p>
 * The path is read on the message's payload — inside the envelope when the dataflow is encapsulated, since
 * the lock is taken before the route opens it. A path that finds nothing (absent, null, unreadable payload)
 * falls back to the route's lock without suffix: such messages serialise together, as they did before.
 * </p>
 *
 * <p>
 * The mutex manager keeps one mutex per name: a key with few values (a tenant, a team) is what this is for,
 * not a value unique to each message.
 * </p>
 */
final class PerMessageLock {

	private static final Logger LOG = Logger.getLogger(PerMessageLock.class);
	private static final ObjectMapper ENVELOPE = new ObjectMapper();

	private final IMutexManager mutexManager;
	private final MutexName route;
	private final String path;
	private final boolean encapsulated;

	/**
	 * @param mutexManager the manager the mutexes come from
	 * @param route        the route's own lock — the fallback, and the prefix of every per-message lock
	 * @param path         the JSON path, starting with {@code $}
	 * @param encapsulated whether the inbound dataflow wraps its payload in an envelope
	 */
	PerMessageLock(IMutexManager mutexManager, MutexName route, String path, boolean encapsulated) {
		this.mutexManager = mutexManager;
		this.route = route;
		this.path = path;
		this.encapsulated = encapsulated;
	}

	/** Whether a {@code lockObject} names a path to evaluate on each message rather than a fixed key. */
	static boolean isPath(String lockObject) {
		return lockObject != null && lockObject.trim().startsWith("$");
	}

	/**
	 * The mutex of one message.
	 *
	 * @param exchange the inbound exchange, as the consumer delivered it
	 * @return {@code <lock>:<value>}, or the route's lock when the path finds nothing
	 * @throws MutexException when the mutex cannot be obtained
	 */
	IMutex mutexFor(Exchange exchange) throws MutexException {
		String key = key(exchange);
		MutexName name = key == null ? this.route : new MutexName(this.route.type(), this.route.name() + ":" + key);
		return this.mutexManager.mutex(name);
	}

	private String key(Exchange exchange) {
		try {
			byte[] payload = exchange.value();
			if (this.encapsulated) {
				payload = ENVELOPE.readValue(payload, Message.class).value();
			}
			// The same reading as the json_path expression of route stages.
			Object value = JsonPath.parse(new java.io.ByteArrayInputStream(payload)).read(this.path);
			return value == null ? null : value.toString();
		} catch (IOException | RuntimeException e) {
			LOG.debug("Lock path {} found nothing in a message ({}): the route's own lock applies", this.path,
					e.getMessage());
			return null;
		}
	}
}
