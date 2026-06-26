package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the deep JSON serialisation of the business {@code in}/{@code out} payloads by
 * {@link ApiEventCodec}: a POJO becomes a nested object (json_path navigable), {@code @JsonIgnore}
 * fields are excluded, a non-serialisable payload degrades to a text node rather than failing, and
 * {@code null} stays {@code null}.
 */
@DisplayName("ApiEventCodec deep payload serialisation")
class ApiEventCodecTest {

	/** Business payload with a sensitive field excluded via {@code @JsonIgnore}. */
	static final class Customer {
		public String email = "alice@example.com";
		public int age = 30;

		@JsonIgnore
		public String password = "secret";
	}

	/** Non-serialisable payload: getter throws, forcing the toString() fallback. */
	static final class Hostile {
		public String getBoom() {
			throw new IllegalStateException("nope");
		}

		@Override
		public String toString() {
			return "hostile-fallback";
		}
	}

	private final ApiEventCodec codec = new ApiEventCodec();
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("nests a POJO payload as a JSON object and excludes @JsonIgnore fields")
	void deepSerialisesPojo() throws Exception {
		FakeEvent event = new FakeEvent();
		event.setIn(new Customer());

		JsonNode root = mapper.readTree(codec.toBytes(event));

		assertTrue(root.get("in").isObject(), "in must be a nested object, not a string");
		assertEquals("alice@example.com", root.get("in").get("email").asText());
		assertEquals(30, root.get("in").get("age").asInt());
		assertTrue(root.get("in").get("password") == null, "@JsonIgnore field excluded");
	}

	@Test
	@DisplayName("falls back to a text node for a non-serialisable payload (never throws)")
	void fallsBackForNonSerialisable() throws Exception {
		FakeEvent event = new FakeEvent();
		event.setIn(new Hostile());

		JsonNode root = mapper.readTree(codec.toBytes(event));

		assertTrue(root.get("in").isTextual(), "non-serialisable payload degrades to text");
		assertEquals("hostile-fallback", root.get("in").asText());
	}

	@Test
	@DisplayName("null payload stays null")
	void nullStaysNull() throws Exception {
		FakeEvent event = new FakeEvent();

		JsonNode root = mapper.readTree(codec.toBytes(event));

		assertTrue(root.get("in").isNull(), "null in stays json null");
		assertTrue(root.get("out").isNull(), "null out stays json null");
	}

	@Test
	@DisplayName("metadata fields remain plain scalars alongside nested payloads")
	void metadataUnchanged() throws Exception {
		FakeEvent event = new FakeEvent();
		event.setTenantId("t1");
		event.setUserId("u1");
		event.setIn(new Customer());

		byte[] bytes = codec.toBytes(event);
		JsonNode root = mapper.readTree(bytes);

		assertEquals("t1", root.get("tenantId").asText());
		assertEquals("u1", root.get("userId").asText());
		// And the raw bytes carry "in" as an object, not a quoted string.
		String json = new String(bytes, StandardCharsets.UTF_8);
		assertTrue(json.contains("\"in\":{"), "in serialised as a nested object in the bytes");
	}
}
