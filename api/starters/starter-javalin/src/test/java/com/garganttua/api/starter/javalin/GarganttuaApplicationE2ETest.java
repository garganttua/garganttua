package com.garganttua.api.starter.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityPublic;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.usecase.injection.UseCaseInput;
import com.garganttua.api.starter.GarganttuaApplication;

/**
 * Proves the Spring-Boot-style experience end to end: annotate {@code @Entity}/
 * {@code @Dto}, drop an {@code application.yaml}, call
 * {@link GarganttuaApplication#run}, and a real Javalin server serves CRUD over
 * HTTP — no {@code ApiBuilder} DSL, no explicit {@code .db(...)} (the in-memory
 * default-DAO auto-config supplies it) and no explicit {@code .interfasse(...)}
 * (the Javalin default-interface auto-config attaches it).
 */
@DisplayName("GarganttuaApplication — end-to-end over real Javalin HTTP")
@TestInstance(Lifecycle.PER_CLASS)
class GarganttuaApplicationE2ETest {

	private static final String BASE = "http://localhost:7099/widgets";

	private IApi api;
	private final HttpClient http = HttpClient.newHttpClient();

	/** A bootstrap source whose package is irrelevant — api.packages drives the scan. */
	static final class TestApp {
	}

	@Entity
	@EntityPublic
	public static class Widget {
		@EntityId
		private String id;
		@EntityUuid
		private String uuid;
		private String name;

		public String getId() { return this.id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return this.uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getName() { return this.name; }
		public void setName(String name) { this.name = name; }
	}

	@Dto(entityClass = Widget.class)
	public static class WidgetDto {
		@DtoId
		private String id;
		@DtoUuid
		private String uuid;
		private String name;

		public String getId() { return this.id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return this.uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getName() { return this.name; }
		public void setName(String name) { this.name = name; }
	}

	/** The input and result of the {@code echo} use case. */
	public static class Echo {
		private String name;
		public Echo() { }
		public Echo(String name) { this.name = name; }
		public String getName() { return name; }
		public void setName(String name) { this.name = name; }
	}

	/** The service bound to the {@code echo} use case. */
	public static class EchoService {
		public Echo echo(@UseCaseInput Echo input) {
			return new Echo(input == null ? null : input.getName() + "!");
		}
	}

	@BeforeAll
	void boot() {
		this.api = GarganttuaApplication.run(TestApp.class);
	}

	@AfterAll
	void shutdown() {
		if (this.api != null) {
			this.api.onStop();
		}
	}

	@Nested
	@DisplayName("CRUD round-trip")
	class CrudRoundTrip {

		@Test
		@DisplayName("POST creates (201) then GET lists it back (200) with the concrete name")
		void postThenGet() throws Exception {
			HttpResponse<String> created = http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(BodyPublishers.ofString("{\"name\":\"alice\"}"))
					.build(), BodyHandlers.ofString());

			assertEquals(201, created.statusCode(),
					"create should map to HTTP 201 CREATED; body=" + created.body());
			assertTrue(created.body().contains("\"name\":\"alice\""),
					"created body should echo the name; was: " + created.body());

			HttpResponse<String> all = http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE))
					.header("Accept", "application/json")
					.GET()
					.build(), BodyHandlers.ofString());

			assertEquals(200, all.statusCode(), "readAll should map to HTTP 200; body=" + all.body());
			assertTrue(all.body().contains("\"name\":\"alice\""),
					"readAll body should contain the created widget; was: " + all.body());
			// A uuid was generated server-side and persisted through the default DAO.
			assertTrue(all.body().contains("\"uuid\":\""),
					"readAll body should carry a generated uuid; was: " + all.body());
		}

		@Test
		@DisplayName("the API booted and exposes the auto-discovered 'widgets' domain")
		void domainDiscovered() {
			assertNotNull(api.getDomain("widgets").orElse(null),
					"the @Entity Widget should have produced a 'widgets' domain with no DSL");
		}

		@Test
		@DisplayName("GET /widgets?filter=name:eq:carol returns only carol — the filter flows from the query string over Javalin")
		void filteredReadAllOverHttp() throws Exception {
			post("carol");
			post("dave");

			HttpResponse<String> filtered = http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE + "?filter=name:eq:carol"))
					.header("Accept", "application/json")
					.GET()
					.build(), BodyHandlers.ofString());

			assertEquals(200, filtered.statusCode(), "a filtered readAll must be 200; body=" + filtered.body());
			assertTrue(filtered.body().contains("\"name\":\"carol\""),
					"carol must be returned; body=" + filtered.body());
			assertFalse(filtered.body().contains("\"name\":\"dave\""),
					"dave must be filtered OUT by name:eq:carol; body=" + filtered.body());
		}

		@Test
		@DisplayName("GET /widgets?filter=<json> also filters over HTTP (Mongo-like JSON shape)")
		void filteredReadAllJsonOverHttp() throws Exception {
			post("erin");
			post("frank");

			String json = java.net.URLEncoder.encode("{\"name\":\"erin\"}", java.nio.charset.StandardCharsets.UTF_8);
			HttpResponse<String> filtered = http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE + "?filter=" + json))
					.header("Accept", "application/json")
					.GET()
					.build(), BodyHandlers.ofString());

			assertEquals(200, filtered.statusCode(), "a JSON-filtered readAll must be 200; body=" + filtered.body());
			assertTrue(filtered.body().contains("\"name\":\"erin\""), "erin must be returned; body=" + filtered.body());
			assertFalse(filtered.body().contains("\"name\":\"frank\""),
					"frank must be filtered OUT by the JSON filter; body=" + filtered.body());
		}

		private void post(String name) throws Exception {
			http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
					.build(), BodyHandlers.ofString());
		}
	}

	/**
	 * A client that sends no {@code Accept} at all — Python's {@code urllib}, a probe. Spoken over a bare
	 * socket, so that no HTTP library adds the header on its own.
	 */
	@Nested
	@DisplayName("without an Accept header")
	class WithoutAccept {

		/** One HTTP/1.1 exchange, written by hand: no header but those given. */
		private RawResponse exchange(String method, String path, String body) throws Exception {
			byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			StringBuilder head = new StringBuilder()
					.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
					.append("Host: localhost:7099\r\n")
					.append("Connection: close\r\n");
			if (body != null) {
				head.append("Content-Type: application/json\r\n");
			}
			head.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
			try (Socket socket = new Socket("localhost", 7099)) {
				OutputStream out = socket.getOutputStream();
				out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
				out.write(payload);
				out.flush();
				String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				int split = raw.indexOf("\r\n\r\n");
				String headers = raw.substring(0, split);
				int status = Integer.parseInt(headers.substring(9, 12));
				return new RawResponse(status, headers.toLowerCase(Locale.ROOT), raw.substring(split + 4));
			}
		}

		@Test
		@DisplayName("a CRUD read answers JSON, not the entity's toString()")
		void crudReadIsJson() throws Exception {
			post("gina");

			RawResponse response = exchange("GET", "/widgets", null);

			assertEquals(200, response.status(), response.body());
			assertTrue(response.headers().contains("content-type: application/json"), response.headers());
			assertTrue(response.body().contains("\"name\":\"gina\""), response.body());
			assertFalse(response.body().contains("Widget@"), response.body());
		}

		@Test
		@DisplayName("a use case answers JSON, not its result's toString()")
		void useCaseIsJson() throws Exception {
			RawResponse response = exchange("POST", "/widgets/echo", "{\"name\":\"hugo\"}");

			assertEquals(200, response.status(), response.body());
			assertTrue(response.headers().contains("content-type: application/json"), response.headers());
			assertTrue(response.body().contains("\"name\":\"hugo!\""), response.body());
			assertFalse(response.body().contains("Echo@"), response.body());
		}

		private void post(String name) throws Exception {
			http.send(HttpRequest.newBuilder()
					.uri(URI.create(BASE))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(BodyPublishers.ofString("{\"name\":\"" + name + "\"}"))
					.build(), BodyHandlers.ofString());
		}
	}

	/** A short session refreshed over HTTP: the route, the header in and out, the rotation. */
	@Nested
	@DisplayName("refreshing a session over HTTP")
	class RefreshOverHttp {

		private HttpResponse<String> authenticate() throws Exception {
			return http.send(HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:7099/accounts/authenticate"))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(BodyPublishers.ofString("{\"login\":\"" + RefreshFixtures.LOGIN + "\",\"credentials\":\""
							+ RefreshFixtures.PASSWORD + "\"}"))
					.build(), BodyHandlers.ofString());
		}

		private HttpResponse<String> refresh(String token) throws Exception {
			return http.send(HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:7099/accounts/refresh"))
					.header("Authorization", "Bearer " + token)
					.header("Accept", "application/json")
					.POST(BodyPublishers.noBody())
					.build(), BodyHandlers.ofString());
		}

		private static String token(HttpResponse<String> response) {
			return response.headers().firstValue("X-Authorization").orElse(null);
		}

		@Test
		@DisplayName("POST /accounts/refresh trades the token of the Authorization header for a new one, once")
		void refreshAndRotate() throws Exception {
			RefreshFixtures.Account ana = new RefreshFixtures.Account();
			ana.setId(RefreshFixtures.LOGIN);
			ana.setUuid("account-ana");
			api.getDomain("accounts").orElseThrow().getRepository().save(ana);

			HttpResponse<String> login = authenticate();
			assertEquals(200, login.statusCode(), login.body());
			String first = token(login);
			assertNotNull(first, "the login hands the token in X-Authorization");

			HttpResponse<String> refreshed = refresh(first);
			assertEquals(200, refreshed.statusCode(), refreshed.body());
			String second = token(refreshed);
			assertNotNull(second, "the refresh hands the new token in X-Authorization");
			assertNotEquals(first, second);

			HttpResponse<String> replay = refresh(first);
			assertEquals(401, replay.statusCode(), "an exchanged token does not refresh twice: " + replay.body());
			assertEquals(401, refresh(second).statusCode(), "and its reuse revoked the whole session");
		}
	}

	/** A response read off the socket: status, headers (lower-cased), body. */
	private record RawResponse(int status, String headers, String body) {
	}
}
