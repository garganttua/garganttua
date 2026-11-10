package com.garganttua.api.commons.event;

/**
 * Where an operation came from: the client address and agent the transport saw, and the session the
 * verified authorization belongs to — what an access log needs to answer "from where, in which session".
 *
 * <p>
 * The address is the one the transport trusts: behind a reverse proxy, the client the TRUSTED proxies
 * forwarded for, never what an untrusted {@code X-Forwarded-For} claims. The session is the uuid of the
 * authorization the request was verified with — never the token itself, and absent when no
 * authorization was verified.
 * </p>
 *
 * @param address   the client address, or null when the transport does not know it
 * @param userAgent the client's user agent, or null
 * @param sessionId the uuid of the verified authorization, or null
 * @since 3.0.0-ALPHA34
 */
public record RequestOrigin(String address, String userAgent, String sessionId) {

	/**
	 * @param sessionId the session the operation was verified in
	 * @return the same origin, in that session
	 */
	public RequestOrigin withSessionId(String sessionId) {
		return new RequestOrigin(this.address, this.userAgent, sessionId);
	}
}
