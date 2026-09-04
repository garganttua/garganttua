package com.garganttua.api.commons.service;

import com.garganttua.api.commons.ApiException;

/**
 * Outcome of an operation, as reported to the transport layer.
 */
public enum OperationResponseCode {
	NOT_AVAILABLE, SERVER_ERROR, CLIENT_ERROR, CREATED, NOT_FOUND, OK, UPDATED, DELETED, UNAUTHORIZED, FORBIDDEN,
	CONFLICT, NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE;

	/**
	 * Maps an exception's status code to the response code that reports it.
	 *
	 * <p>
	 * Only the status codes an {@link ApiException} thrower can choose deliberately
	 * ({@link ApiException#badRequest(String)} and friends) are distinguished. Everything else —
	 * a bare {@code ApiException}, or a code inherited from a wrapped core failure — is a server
	 * error, which stays the correct default for an unexpected failure.
	 * </p>
	 *
	 * @param e the exception to classify
	 * @return the response code reporting it
	 */
	public static OperationResponseCode fromExceptionCode(ApiException e) {
		return switch (e.getCode()) {
			case ApiException.BAD_REQUEST -> CLIENT_ERROR;
			case ApiException.UNAUTHORIZED -> UNAUTHORIZED;
			case ApiException.FORBIDDEN -> FORBIDDEN;
			case ApiException.NOT_FOUND -> NOT_FOUND;
			case ApiException.NOT_ACCEPTABLE -> NOT_ACCEPTABLE;
			case ApiException.CONFLICT -> CONFLICT;
			default -> SERVER_ERROR;
		};
	}
}
