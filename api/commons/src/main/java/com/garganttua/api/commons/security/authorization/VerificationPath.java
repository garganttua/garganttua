package com.garganttua.api.commons.security.authorization;

/**
 * How the verification of a self-verifying token — a token whose domain declares its own authenticator —
 * reaches what it needs. Chosen per token domain, separately for running the authenticator
 * ({@code .authorization().authenticateOnVerify(...)}) and for reading the token's record
 * ({@code .authorization().lookupOnVerify(...)}).
 *
 * <p>
 * The choice is a trade between what a request costs and what the rest of the domain sees. Through the
 * {@link #PIPELINE pipeline}, the verification is an operation like any other: observers, hooks and DTO
 * rules see it, at the price of a nested workflow per request. {@link #DIRECT Directly}, it runs the same
 * checks with the same outcome, without the workflow, and nothing outside the verification sees it.
 * </p>
 */
public enum VerificationPath {

	/**
	 * Through the token domain's own operations: the {@code authenticate} operation for the authenticator,
	 * the {@code readAll} operation for the record. Each is a workflow nested in the request's. The default.
	 */
	PIPELINE,

	/**
	 * Without a nested workflow: the authenticator's strategies run in the request's pipeline, and the
	 * record is read from the repository, once, and shared with the stored-record checks of the same
	 * verification.
	 */
	DIRECT
}
