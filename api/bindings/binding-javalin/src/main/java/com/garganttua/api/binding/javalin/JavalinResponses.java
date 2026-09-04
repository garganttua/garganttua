package com.garganttua.api.binding.javalin;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.commons.service.WrittenFields;
import com.garganttua.api.core.expression.SerializationExpressions;
import com.garganttua.core.observability.Logger;

import io.javalin.http.Context;

/**
 * How an operation outcome becomes an HTTP response: the status it maps to, the envelope written
 * when it failed, the headers that describe what a write actually did, and the media negotiation
 * that renders any of it.
 *
 * <p>
 * Split out of {@link JavalinInterface} to keep that class within the size gate. It reads as one
 * concept and depends on no interface state — every method here is a pure function of the
 * {@link Context}, the domain and the response — which is why it moves cleanly, and why it is
 * static.
 * </p>
 */
final class JavalinResponses {

	/**
	 * Response headers naming the DTO fields a write applied and those it dropped. Always both, and
	 * always present on a write — see {@link #writeFieldReport}.
	 */
	private static final String FIELDS_APPLIED_HEADER = "X-Garganttua-Fields-Applied";
	private static final String FIELDS_REJECTED_HEADER = "X-Garganttua-Fields-Rejected";

	private static final Logger log = Logger.getLogger(JavalinResponses.class);

	private JavalinResponses() {
	}

	/** A successful outcome carries a payload, not a {@link Throwable}. */
	static boolean isSuccess(IOperationResponse response) {
		return response != null && !(response.getResponse() instanceof Throwable);
	}

	/**
	 * Reports, on every WRITE, which of the fields the client named were applied and which were
	 * dropped.
	 *
	 * <p>
	 * Both headers are emitted even when nothing was rejected — an empty value rather than no
	 * header. Their absence would be ambiguous between "nothing rejected" and "a framework too old
	 * to say", and no client could then rely on them. The status is deliberately NOT changed: the
	 * request was processed, partially; turning a 200 into a 403 would break every client that
	 * treats 2xx as success, for a problem that is one of observability.
	 * </p>
	 */
	static void writeFieldReport(Context ctx, OperationDefinition operation, IOperationResponse response) {
		// A null response is a legitimate outcome here (the pipeline answered on the Context
		// directly); there is then nothing to report, and this must not be what turns it into a 500.
		if (response == null || !isWrite(operation)) {
			return;
		}
		WrittenFields fields = response.getWrittenFields();
		ctx.header(FIELDS_APPLIED_HEADER, String.join(",", fields.applied()));
		ctx.header(FIELDS_REJECTED_HEADER, String.join(",", fields.rejected()));
	}

	/** Whether this operation writes an entity — the only ones for which a field report means anything. */
	private static boolean isWrite(OperationDefinition operation) {
		if (operation == null) {
			return false;
		}
		BusinessOperation bo = operation.getBusinessOperation();
		return bo == BusinessOperation.create || bo == BusinessOperation.update;
	}

	/** Renders the encoded token as a header string (it may be a String or a byte[]/Byte[] wire form). */
	static String asTokenString(Object encoded) {
		if (encoded instanceof String s) {
			return s;
		}
		if (encoded instanceof byte[] b) {
			return new String(b, java.nio.charset.StandardCharsets.UTF_8);
		}
		if (encoded instanceof Byte[] boxed) {
			byte[] out = new byte[boxed.length];
			for (int i = 0; i < boxed.length; i++) {
				out[i] = boxed[i];
			}
			return new String(out, java.nio.charset.StandardCharsets.UTF_8);
		}
		return String.valueOf(encoded);
	}

	/**
	 * Reconciles the HTTP response with the pipeline's {@link IOperationResponse} so the
	 * wire reflects the operation, not the always-200 default. On failure (the response
	 * carries a {@link Throwable}) the status comes from the response code; on success
	 * the RESPONSE stage already serialized the body, so only the status is corrected.
	 * <p>
	 * The error body is rendered in the client's negotiated media (JSON, XML, …) via
	 * the serializer registry. It degrades to {@code text/plain} (the raw message) only
	 * when no registered serializer satisfies {@code Accept} — the very situation a
	 * {@code 406} reports, where answering in a served media would repeat the
	 * content-negotiation violation being signalled. {@code text/plain} every client accepts.
	 */
	static void applyOutcome(Context ctx, IDomain<?> domain, IOperationResponse response) {
		if (response == null) {
			return;
		}
		int status = httpStatus(response.getResponseCode());
		Object payload = response.getResponse();
		if (payload instanceof Throwable t) {
			String message = (t.getMessage() != null && !t.getMessage().isBlank())
					? t.getMessage() : t.getClass().getSimpleName();
			writeEnvelope(ctx, domain, status, new ErrorEnvelope(message), message);
		} else {
			ctx.status(status);
		}
	}

	/**
	 * Writes a small envelope object as the response body in the client's negotiated
	 * media. Reuses the framework's RFC 7231 negotiation ({@link SerializationExpressions#negotiateSerializer})
	 * over the API's serializer registry, labels the response with the chosen media type,
	 * and falls back to {@code text/plain} (the supplied raw text) only when the API has
	 * no serializer or none satisfies {@code Accept}.
	 */
	// justification: GuardLogStatement is moot with {}-parameterized logging (accepted noise per code-quality rules).
	@SuppressWarnings("PMD.GuardLogStatement")
	static void writeEnvelope(Context ctx, IDomain<?> domain, int status, Object envelope, String fallbackText) {
		IApi api = apiOf(domain);
		if (api != null) {
			try {
				ISerializer serializer = SerializationExpressions.negotiateSerializer(api, ctx.header("Accept"));
				byte[] body = serializer.serialize(envelope);
				ctx.status(status);
				if (serializer.mimeType() != null) {
					ctx.contentType(serializer.mimeType().toString());
				}
				ctx.result(body);
				return;
			} catch (RuntimeException negotiationOrSerializationFailed) {
				// No serializer satisfies Accept (or serialization failed) — degrade to plain text.
				// negotiateSerializer/serialize raise the unchecked ApiException (e.g. 415); any
				// runtime failure here is non-fatal and falls through to the text/plain branch.
				log.debug("Serializer negotiation/serialization failed, degrading to text/plain: {}",
						negotiationOrSerializationFailed.getMessage());
			}
		}
		ctx.status(status).contentType("text/plain").result(fallbackText);
	}

	/** The API context backing a domain (the serializer registry lives on it), or null. */
	private static IApi apiOf(IDomain<?> domain) {
		return domain != null ? domain.getApiContext() : null;
	}

	/** Minimal success envelope: serializes to {@code {"status":"ok"}} (JSON) / {@code <StatusEnvelope><status>ok</status></StatusEnvelope>} (XML). */
	public static final class StatusEnvelope {
		private final String status;
		public StatusEnvelope(String status) { this.status = status; }
		public String getStatus() { return this.status; }
	}

	/** Minimal error envelope: serializes to {@code {"error":"…"}} (JSON) / {@code <ErrorEnvelope><error>…</error></ErrorEnvelope>} (XML). */
	public static final class ErrorEnvelope {
		private final String error;
		public ErrorEnvelope(String error) { this.error = error; }
		public String getError() { return this.error; }
	}

	/** Maps the framework's response code to an HTTP status. */
	static int httpStatus(OperationResponseCode code) {
		if (code == null) {
			return 200;
		}
		return switch (code) {
			case OK, UPDATED, DELETED -> 200;
			case CREATED -> 201;
			case CLIENT_ERROR -> 400;
			case UNAUTHORIZED -> 401;
			case FORBIDDEN -> 403;
			case NOT_FOUND -> 404;
			case NOT_ACCEPTABLE -> 406;
			case CONFLICT -> 409;
			case UNSUPPORTED_MEDIA_TYPE -> 415;
			case NOT_AVAILABLE -> 503;
			case SERVER_ERROR -> 500;
		};
	}
}
