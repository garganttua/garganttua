package com.garganttua.api.binding.javalin;

import java.util.List;

import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;

import io.javalin.Javalin;
import io.javalin.http.Handler;

/**
 * The stateless half of {@link JavalinInterface}'s route table: verbs, paths, mount-path normalisation, the
 * operation a route serves, and the binding of a handler.
 */
final class JavalinRoutes {

	/** The HTTP verbs a route is bound to. */
	enum HttpVerb { GET, POST, PATCH, PUT, DELETE }

	private JavalinRoutes() {
	}

	/**
	 * Normalises a declared mount path to either {@code ""} or {@code "/segment[/segment…]"}:
	 * a missing leading slash is added, trailing slashes are dropped, and {@code null} / blank /
	 * {@code "/"} all mean "the root", so that {@code "api"}, {@code "/api"} and {@code "/api/"}
	 * cannot produce three different mountings — nor {@code /api//invoices}.
	 */
	static String normaliseMountPath(String mountPath) {
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

	/**
	 * The HTTP verbs a use case's technical operation maps to. An {@code update} use case is
	 * reachable under both PATCH and PUT — PATCH first, since it is the preferred verb — exactly
	 * like the CRUD update route.
	 */
	static List<HttpVerb> verbsOf(com.garganttua.api.commons.operation.TechnicalOperation op) {
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
	static String toJavalinPath(OperationDefinition op) {
		String path = op.getPath() != null ? op.getPath().path() : null;
		if (path == null || path.isBlank()) {
			return "/";
		}
		return path.replace("${uuid}", "{uuid}");
	}

	/** Records a mounted route, skipping the operations the domain does not expose. */
	static void record(List<String> mounted, String route) {
		if (route != null) {
			mounted.add(route);
		}
	}

	/** Binds a handler to a Javalin route for the given verb. */
	static void register(Javalin server, HttpVerb verb, String path, Handler handler) {
		switch (verb) {
			case GET -> server.get(path, handler);
			case POST -> server.post(path, handler);
			case PATCH -> server.patch(path, handler);
			case PUT -> server.put(path, handler);
			case DELETE -> server.delete(path, handler);
		}
	}

	static OperationDefinition findOperation(List<OperationDefinition> operations, BusinessOperation bo) {
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
}
