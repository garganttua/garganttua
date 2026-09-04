package com.garganttua.api.commons;

import com.garganttua.core.CoreException;

/**
 * Base exception for the API layer.
 *
 * <h2>Response status</h2>
 * <p>
 * A bare {@code ApiException} carries {@link #API_ERROR_CODE} and is reported as a server error —
 * the right default for an unexpected failure. A <strong>deliberate refusal</strong> (a business
 * rule declining a write from a lifecycle hook, a use case rejecting its input) is not a server
 * failure, and must not be reported as one: build it with one of the factories below, whose code
 * the pipeline honours over the stage's default status.
 * </p>
 *
 * <pre>{@code
 * public static void refuse(Patient p) {
 *     if (p.getName() == null || p.getName().isBlank()) {
 *         throw ApiException.badRequest("Le champ « nom » est obligatoire.");
 *     }
 * }
 * // POST /patients {} -> 400, not 500
 * }</pre>
 *
 * @see com.garganttua.api.commons.service.OperationResponseCode#fromExceptionCode(ApiException)
 */
// AvoidFieldNameMatchingMethodName: each status constant deliberately pairs with the factory that
// builds it (UNAUTHORIZED / unauthorized(), FORBIDDEN / forbidden(), CONFLICT / conflict()). PMD
// compares the names case-insensitively; renaming either half to satisfy it would break the pairing
// that makes this API readable.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class ApiException extends CoreException {

    private static final long serialVersionUID = 1L;

    /** Generic API failure — reported as a server error (HTTP 5xx). */
    public static final int API_ERROR_CODE = 100;

    /** The request is malformed or violates a business rule — reported as HTTP 400. */
    public static final int BAD_REQUEST = 400;

    /** The caller is not authenticated, or its credentials are no longer valid — HTTP 401. */
    public static final int UNAUTHORIZED = 401;

    /** The caller is authenticated but not allowed to perform the operation — HTTP 403. */
    public static final int FORBIDDEN = 403;

    /** The addressed resource does not exist — HTTP 404. */
    public static final int NOT_FOUND = 404;

    /** The request cannot be satisfied in a representation the caller accepts — HTTP 406. */
    public static final int NOT_ACCEPTABLE = 406;

    /** The request conflicts with the current state (e.g. a unicity violation) — HTTP 409. */
    public static final int CONFLICT = 409;

    public ApiException(String message) {
        super(API_ERROR_CODE, message);
    }

    public ApiException(String message, Throwable cause) {
        super(API_ERROR_CODE, message, cause);
    }

    public ApiException(Throwable cause) {
        super(cause instanceof CoreException ? ((CoreException) cause).getCode() : API_ERROR_CODE,
              cause.getMessage(), cause);
    }

    protected ApiException(int code, String message) {
        super(code, message);
    }

    protected ApiException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }

    /**
     * Builds an exception carrying an explicit status code. Prefer the named factories
     * ({@link #badRequest(String)} and friends); this overload exists for the codes they do not
     * cover.
     *
     * @param code    one of the status constants declared on this class
     * @param message the message rendered to the caller
     * @return the exception, to be thrown by the caller
     */
    public static ApiException of(int code, String message) {
        return new ApiException(code, message);
    }

    /** {@return a refusal reported as HTTP 400 — the request is malformed or breaks a business rule} */
    public static ApiException badRequest(String message) {
        return new ApiException(BAD_REQUEST, message);
    }

    /** {@return a refusal reported as HTTP 400, carrying the underlying cause} */
    public static ApiException badRequest(String message, Throwable cause) {
        return new ApiException(BAD_REQUEST, message, cause);
    }

    /** {@return a refusal reported as HTTP 401 — the caller is not authenticated} */
    public static ApiException unauthorized(String message) {
        return new ApiException(UNAUTHORIZED, message);
    }

    /** {@return a refusal reported as HTTP 403 — the caller is not allowed to do this} */
    public static ApiException forbidden(String message) {
        return new ApiException(FORBIDDEN, message);
    }

    /** {@return a refusal reported as HTTP 404 — the addressed resource does not exist} */
    public static ApiException notFound(String message) {
        return new ApiException(NOT_FOUND, message);
    }

    /** {@return a refusal reported as HTTP 406 — no acceptable representation} */
    public static ApiException notAcceptable(String message) {
        return new ApiException(NOT_ACCEPTABLE, message);
    }

    /** {@return a refusal reported as HTTP 409 — the request conflicts with the current state} */
    public static ApiException conflict(String message) {
        return new ApiException(CONFLICT, message);
    }

    /**
     * {@return whether this exception carries a status chosen by its thrower} True only for one of
     * the status constants declared above — a code inherited from a wrapped {@link CoreException}
     * (a reflection or injection failure, say) is a diagnostic, not a chosen status, and does not
     * count. A bare {@code ApiException} keeps whatever status the failing pipeline stage declares.
     */
    public boolean hasExplicitStatus() {
        return switch (getCode()) {
            case BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, NOT_ACCEPTABLE, CONFLICT -> true;
            default -> false;
        };
    }

    public static ApiException wrap(Throwable e) {
        if (e instanceof ApiException) {
            return (ApiException) e;
        }
        return new ApiException(e);
    }

}
