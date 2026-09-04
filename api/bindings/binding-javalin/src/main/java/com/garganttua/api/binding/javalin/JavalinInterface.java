package com.garganttua.api.binding.javalin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.endpoint.Interface;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.commons.service.ArgKey;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.WrittenFields;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.expression.SerializationExpressions;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleStatus;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * A Javalin-backed {@link IInterface} — the HTTP transport entry point for a domain.
 * <p>
 * It owns a Javalin server and, on {@link #handle(IDomain)}, wires the standard CRUD
 * route table for the domain it is attached to:
 * <pre>
 *   POST    /{domain}            → createOne
 *   GET     /{domain}            → readAll
 *   GET     /{domain}/{uuid}     → readOne
 *   PATCH   /{domain}/{uuid}     → updateOne  (partial — an absent field is left alone)
 *   PUT     /{domain}/{uuid}     → updateOne  (full — an absent field follows its declared null policy)
 *   DELETE  /{domain}/{uuid}     → deleteOne
 *   DELETE  /{domain}            → deleteAll
 * </pre>
 * <b>Prefer PATCH for updates.</b> Both verbs reach the same {@code updateOne} operation, but
 * PATCH flags the body as partial ({@link IOperationRequest#PARTIAL_UPDATE}), so a field the
 * client did not send keeps its stored value. PUT applies the declared per-field policy instead,
 * where a null erases unless the field opted into {@code ignoreNull}. Neither verb changes which
 * fields a caller may write — the field-level authority gate is identical.
 * Each handler hands the live Javalin {@link Context} to the pipeline as
 * {@code rawRequest} and invokes the domain. Transport extraction and response
 * writing are delegated to the companion {@link JavalinProtocol} (Mode A): this
 * interface decides <em>which</em> operation (routing, and the {@code uuid} path
 * parameter), the protocol adapts the <em>how</em> (body, caller, headers, response).
 * <p>
 * Register the protocol once on the API and attach this interface to the domain:
 * <pre>{@code
 *   ApiBuilder.builder()
 *       .protocol(new JavalinProtocol())
 *       .domain(User.class)
 *           .interfasse(new JavalinInterface(7000))
 *           .entity()...
 *       .up()
 *       .build();
 * }</pre>
 *
 * <h2>Scope</h2>
 * Per the per-domain interface model, one {@code JavalinInterface} owns one Javalin
 * server. Two domains each carrying their own instance must use distinct ports.
 * The lifecycle guards ({@link #onStart}/{@link #onStop}) are idempotent, so a single
 * instance shared across domains via a supplier starts/stops its server exactly once
 * while registering every domain's routes.
 * <p>
 * <b>External server.</b> Pass a {@link Javalin} via {@link #JavalinInterface(Javalin)}
 * to attach to a caller-provided server (a shared one, or a Spring Boot-managed one):
 * the interface registers its routes on it but never starts or stops it — the owner
 * keeps full control of the lifecycle. Several domains can share one server this way.
 */
@Interface
// AvoidFieldNameMatchingMethodName: the fluent/lifecycle accessor idiom used throughout — a field x
// paired with a public accessor x() (ownsServer, app, status, mountPath). Declared once for the
// class rather than on each field, which would repeat the same literal four times.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class JavalinInterface implements IInterface {

	/** Default HTTP port when none is supplied. */
	public static final int DEFAULT_PORT = 7000;

	/**
	 * Response header carrying the minted authorization (the encoded token) after a
	 * successful {@code authenticate} / {@code refreshAuthorization}. The body is then a
	 * minimal {@code ok} — the token travels in the header, never the body.
	 */
	public static final String AUTHORIZATION_RESPONSE_HEADER = "X-Authorization";

	/** The request arg under which the pipeline publishes the encoded token (set by CREATE/REFRESH_AUTHORIZATION). */
	private static final ArgKey<Object> ENCODED_AUTHORIZATION =
			ArgKey.of("encodedAuthorization", IClass.getClass(Object.class));

	/**
	 * The request arg under which the pipeline publishes the sanitized {@code IAuthentication}
	 * (the login security context — tenant/owner/super/authorities, never credentials/principal)
	 * after authenticate / refreshAuthorization. Rendered as the response body.
	 */
	private static final ArgKey<Object> AUTHENTICATION =
			ArgKey.of("authentication", IClass.getClass(Object.class));

	private static final Logger LOGGER = Logger.getLogger(JavalinInterface.class);

	private final int port;
	/**
	 * Where this connector mounts everything it registers: {@code ""} (the root, the historical and
	 * default behaviour) or a normalised prefix such as {@code "/api"}. It belongs to the CONNECTOR,
	 * not to the domains — a domain knows nothing of its HTTP mounting, and its name stays the name
	 * of its collection.
	 */
	private final String mountPath;
	/** Whether this interface owns (creates + starts + stops) its Javalin server. */
	private final boolean ownsServer;
	private Javalin app;
	private boolean started;
	private LifecycleStatus status = LifecycleStatus.NEW;

	/** Binds an owned server to {@link #DEFAULT_PORT}. Required no-arg form for {@code .interfasse(IClass)}. */
	public JavalinInterface() {
		this(DEFAULT_PORT);
	}

	/** Owns a Javalin server bound to {@code port}; this interface starts and stops it. */
	public JavalinInterface(int port) {
		this(port, null);
	}

	/**
	 * Owns a Javalin server bound to {@code port} and mounts every route it registers under
	 * {@code mountPath}.
	 *
	 * @param port      the port to bind
	 * @param mountPath the common prefix; {@code null} or blank keeps the historical root mounting
	 */
	public JavalinInterface(int port, String mountPath) {
		this.port = port;
		this.ownsServer = true;
		this.mountPath = normaliseMountPath(mountPath);
	}

	/**
	 * Attaches to a caller-provided Javalin server (e.g. a shared server or a
	 * Spring Boot-managed one). The interface registers its routes on it but does
	 * <strong>not</strong> start or stop it — the owner manages the lifecycle.
	 * Multiple domains can pass the same instance to share one server.
	 */
	public JavalinInterface(Javalin app) {
		this(app, null);
	}

	/**
	 * Attaches to a caller-provided Javalin server and mounts every route it registers under
	 * {@code mountPath} — the form for an application that SHARES its server with the framework and
	 * wants one HTTP namespace rather than two: the generated CRUD under the prefix, alongside the
	 * routes the application mounts itself (a multipart upload, a binary stream) under the same one.
	 *
	 * <pre>{@code
	 * new JavalinInterface(sharedApp, "/api");  // /api/invoices, /api/invoices/{uuid}, …
	 * new JavalinInterface(sharedApp);          // unchanged: the root
	 * }</pre>
	 *
	 * @param app       the server to register on; its lifecycle stays the caller's
	 * @param mountPath the common prefix; {@code null} or blank keeps the historical root mounting.
	 *                  {@code "api"}, {@code "/api"} and {@code "/api/"} all mount at {@code /api}.
	 */
	public JavalinInterface(Javalin app, String mountPath) {
		this.app = Objects.requireNonNull(app, "Javalin app cannot be null");
		this.ownsServer = false;
		this.port = -1;
		this.mountPath = normaliseMountPath(mountPath);
	}

	/**
	 * Normalises a declared mount path to either {@code ""} or {@code "/segment[/segment…]"}:
	 * a missing leading slash is added, trailing slashes are dropped, and {@code null} / blank /
	 * {@code "/"} all mean "the root", so that {@code "api"}, {@code "/api"} and {@code "/api/"}
	 * cannot produce three different mountings — nor {@code /api//invoices}.
	 */
	private static String normaliseMountPath(String mountPath) {
		if (mountPath == null || mountPath.isBlank()) {
			return "";
		}
		String trimmed = mountPath.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		if (trimmed.isEmpty()) {
			return "";
		}
		return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
	}

	/** {@return the normalised prefix every route of this connector is mounted under} Empty for the root. */
	public String mountPath() {
		return this.mountPath;
	}

	/** The bound port for an owned server, or {@code -1} when the server is provided externally. */
	public int getPort() {
		return this.port;
	}

	/** {@code true} when this interface owns (and manages the lifecycle of) its Javalin server. */
	public boolean ownsServer() {
		return this.ownsServer;
	}

	/** Whether the owned server is currently bound. Always {@code false} in external-server mode. */
	public boolean isStarted() {
		return this.started;
	}

	/** Returns the Javalin server, lazily creating an owned one (no port binding until {@link #onStart}). */
	private Javalin app() {
		if (this.app == null) {
			this.app = Javalin.create();
		}
		return this.app;
	}

	@Override
	public void handle(IDomain<?> domain) {
		String base = this.mountPath + "/" + domain.getDomainName();
		String one = base + "/{uuid}";
		Javalin server = app();
		List<String> mounted = new ArrayList<>();

		// Resolve each route's operation from the domain's CONFIGURED operations so the
		// access/authority the request carries matches what the domain declared (e.g.
		// readAllAccess(anonymous)). Hardcoding *WithStandardSecurity would send
		// Access.authenticated/authority=true regardless, and the verify stages would reject an
		// anonymous HTTP caller — silently skipping the business stage. A route is
		// registered only when its operation is actually enabled on the domain.
		List<OperationDefinition> configured = domain.getDomainDefinition().operations();

		// Use cases first: each declared use case is a routable operation carrying its own verb
		// (read→GET, create→POST, update→PATCH+PUT, delete→DELETE), its own path (defaulting to
		// /{domain}/{name}) and a {uuid} segment when scoped to a single entity. They are
		// registered BEFORE the CRUD table so a literal use-case path (e.g. /users/greet) wins
		// over readOne's /users/{uuid} — Javalin resolves a collision by registration order, so
		// the literal route must come first or readOne would swallow it as uuid="greet". The
		// dispatched operation is the domain's own, carrying the access/authority the use case
		// declared via .security().
		for (OperationDefinition op : configured) {
			if (op.getBusinessOperation() != BusinessOperation.useCase) {
				continue;
			}
			String path = this.mountPath + toJavalinPath(op);
			boolean hasUuid = path.contains("{uuid}");
			for (HttpVerb verb : verbsOf(op.technicalOperation())) {
				boolean partial = verb == HttpVerb.PATCH;
				Handler handler = ctx -> dispatch(domain, op, ctx, hasUuid ? ctx.pathParam("uuid") : null, partial);
				register(server, verb, path, handler);
				mounted.add(verb + " " + path);
			}
		}

		record(mounted, route(server, HttpVerb.POST,   base, domain, configured, BusinessOperation.create,    false));
		record(mounted, route(server, HttpVerb.GET,    base, domain, configured, BusinessOperation.readAll,   false));
		record(mounted, route(server, HttpVerb.GET,    one,  domain, configured, BusinessOperation.readOne,   true));
		// Update is reachable under both verbs; only PATCH flags the body as partial.
		record(mounted, route(server, HttpVerb.PATCH,  one,  domain, configured, BusinessOperation.update,    true));
		record(mounted, route(server, HttpVerb.PUT,    one,  domain, configured, BusinessOperation.update,    true));
		record(mounted, route(server, HttpVerb.DELETE, one,  domain, configured, BusinessOperation.deleteOne, true));
		record(mounted, route(server, HttpVerb.DELETE, base, domain, configured, BusinessOperation.deleteAll, false));

		// Authentication entry point (anonymous): the credentials travel in the body as
		// an AuthenticationRequest. Registered only when the domain has an authenticator
		// (its authenticate operation is then present in the configured operations).
		record(mounted, route(server, HttpVerb.POST, base + "/authenticate", domain, configured,
				BusinessOperation.authenticate, false));

		// Name what is ACTUALLY mounted, not what was declared: with a mount path in play, the two
		// differ, and this line is how an operator checks which of the two is online.
		LOGGER.info("Domain '{}' mounted on {}: {}", domain.getDomainName(),
				this.mountPath.isEmpty() ? "/" : this.mountPath, mounted);
	}

	/**
	 * The HTTP verbs a use case's technical operation maps to. An {@code update} use case is
	 * reachable under both PATCH and PUT — PATCH first, since it is the preferred verb — exactly
	 * like the CRUD update route.
	 */
	private static List<HttpVerb> verbsOf(com.garganttua.api.commons.operation.TechnicalOperation op) {
		if (op == null) {
			return List.of(HttpVerb.GET);
		}
		return switch (op) {
			case create -> List.of(HttpVerb.POST);
			case update -> List.of(HttpVerb.PATCH, HttpVerb.PUT);
			case delete -> List.of(HttpVerb.DELETE);
			case read -> List.of(HttpVerb.GET);
		};
	}

	/**
	 * The Javalin route path for an operation, RELATIVE to this connector's mount path: its declared
	 * {@link OperationDefinition#getPath()} with the framework's {@code ${uuid}} placeholder
	 * rewritten to Javalin's {@code {uuid}}. The caller prepends {@code mountPath}.
	 *
	 * <p>
	 * This covers a use case declared with {@code completePath(...)} as well: "complete" is
	 * complete <em>within the mounting</em>, not absolute. A connector that let some of its own
	 * routes escape its prefix would hand the application back the two HTTP namespaces the mount
	 * path exists to remove.
	 * </p>
	 */
	private static String toJavalinPath(OperationDefinition op) {
		String path = op.getPath() != null ? op.getPath().path() : null;
		if (path == null || path.isBlank()) {
			return "/";
		}
		return path.replace("${uuid}", "{uuid}");
	}

	private enum HttpVerb { GET, POST, PATCH, PUT, DELETE }

	/**
	 * Registers one route, but only when the domain actually exposes {@code bo} (the
	 * matching {@link OperationDefinition} is present in its configured operations).
	 * The dispatched operation is the domain's own — carrying its declared
	 * access/authority — never a synthesized standard-security one.
	 */
	private String route(Javalin server, HttpVerb verb, String path, IDomain<?> domain,
			List<OperationDefinition> configured, BusinessOperation bo, boolean hasUuid) {
		OperationDefinition operation = findOperation(configured, bo);
		if (operation == null) {
			return null; // operation not enabled on this domain — no route
		}
		boolean partial = verb == HttpVerb.PATCH;
		Handler handler = ctx -> dispatch(domain, operation, ctx, hasUuid ? ctx.pathParam("uuid") : null, partial);
		register(server, verb, path, handler);
		return verb + " " + path;
	}

	/** Binds a handler to a Javalin route for the given verb. */
	/** Records a mounted route, skipping the operations the domain does not expose. */
	private static void record(List<String> mounted, String route) {
		if (route != null) {
			mounted.add(route);
		}
	}

	private void register(Javalin server, HttpVerb verb, String path, Handler handler) {
		switch (verb) {
			case GET -> server.get(path, handler);
			case POST -> server.post(path, handler);
			case PATCH -> server.patch(path, handler);
			case PUT -> server.put(path, handler);
			case DELETE -> server.delete(path, handler);
		}
	}

	private static OperationDefinition findOperation(List<OperationDefinition> operations, BusinessOperation bo) {
		if (operations == null) {
			return null;
		}
		for (OperationDefinition op : operations) {
			if (op.getBusinessOperation() == bo) {
				return op;
			}
		}
		return null;
	}

	/**
	 * Builds the operation request, hands the {@link Context} to the pipeline as
	 * {@code rawRequest}, invokes the domain, and reconciles the HTTP response with
	 * the operation's outcome.
	 * <p>
	 * The Mode-A RESPONSE stage serializes the body onto the {@code Context}, but it
	 * cannot set the status (the pipeline does not yet write an {@code exitCode}) and
	 * leaves a stale/empty body on failures — so the wire response would otherwise
	 * always read 200 regardless of the pipeline outcome. We therefore make the
	 * transport authoritative: the status follows {@link IOperationResponse#getResponseCode()},
	 * and on failure the carried {@link Throwable}'s message becomes the body.
	 */
	private void dispatch(IDomain<?> domain, OperationDefinition operation, Context ctx, String uuid,
			boolean partial) {
		try {
			IOperationRequest request = IOperationRequest.create();
			request.arg(IOperationRequest.OPERATION, operation);
			request.arg(IOperationRequest.RAW_REQUEST, ctx);
			if (uuid != null) {
				request.arg(IOperationRequest.ENTITY_UUID, uuid);
			}
			if (partial) {
				request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);
			}
			IOperationResponse response = domain.invoke(request);
			JavalinResponses.writeFieldReport(ctx, operation, response);

			// A token-minting op (authenticate / refreshAuthorization) that produced an
			// encoded authorization returns it in the X-Authorization response header; the
			// body is the sanitized IAuthentication (login security context — tenant/owner/
			// super/authorities, never credentials/principal). The token travels in the header,
			// never the body. The failure path is unchanged (applyOutcome surfaces the 4xx).
			Object encoded = request.arg(ENCODED_AUTHORIZATION).orElse(null);
			if (encoded != null && JavalinResponses.isSuccess(response)) {
				ctx.header(AUTHORIZATION_RESPONSE_HEADER, JavalinResponses.asTokenString(encoded));
				int status = JavalinResponses.httpStatus(response.getResponseCode());
				// Rendered in the client's negotiated media (JSON, XML, …) via the serializer
				// registry; degrades to plain "ok" only when no registered serializer satisfies
				// Accept, or when the pipeline published no authentication.
				Object authentication = request.arg(AUTHENTICATION).orElse(null);
				Object body = authentication != null ? authentication : new JavalinResponses.StatusEnvelope("ok");
				JavalinResponses.writeEnvelope(ctx, domain, status, body, "ok");
				return;
			}
			JavalinResponses.applyOutcome(ctx, domain, response);
		} catch (RuntimeException e) {
			// Defensive: the pipeline returns error codes rather than throwing, but a
			// transport-level failure (e.g. no protocol resolved) must still answer.
			ctx.status(500).result("Internal error: " + e.getMessage());
		}
	}

	@Override
	public ILifecycle onInit() {
		this.status = LifecycleStatus.INITIALIZED;
		return this;
	}

	@Override
	public ILifecycle onStart() {
		// Only an owned server is started here; an externally-provided server is
		// started by its owner (the interface merely registered its routes on it).
		if (this.ownsServer && !this.started) {
			app().start(this.port);
			this.started = true;
		}
		this.status = LifecycleStatus.STARTED;
		return this;
	}

	@Override
	public ILifecycle onStop() {
		if (this.ownsServer && this.started) {
			app().stop();
			this.started = false;
		}
		this.status = LifecycleStatus.STOPPED;
		return this;
	}

	@Override
	public ILifecycle onFlush() {
		this.status = LifecycleStatus.FLUSHED;
		return this;
	}

	@Override
	public ILifecycle onReload() {
		return this;
	}

	@Override
	public LifecycleStatus status() {
		return this.status;
	}
}
