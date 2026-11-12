#!/usr/bin/env gs

#@workflow
#  Trades a still-valid authorization for a freshly issued one.
#
#  The client presents an existing authorization — over HTTP in the
#  Authorization header (POST /<authenticator>/refresh), in-process under the
#  request's entity — in the wire form the issuer returned at login. The script checks the token
#  itself (signature, refresh-revoked, refresh-expired), then — the token
#  being signed once and never changing — judges a storable authorization by
#  its STORED record: absent or revoked, refused; already exchanged, refused
#  and every live authorization of the owner revoked (reuse = theft); else
#  marked exchanged (rotation). The principal is re-read and must still be
#  active; its authentication is re-derived by the authenticator's refresh
#  strategy (current authorities) and the new authorization is issued by the
#  same path as a login — the declared issuer when there is one.
#
#  No caller is required — refresh is the entry point for an unauthenticated
#  client presenting a token (anonymous from the framework's point of view).
#
#  @in operationRequest: [0] IOperationRequest
#  @in repository:       [1] IRepository
#  @in domainContext:    [2] IDomain (authenticator)
#  @out output -> output: Object
#  @return 0:   SUCCESS
#  @return 401: missing entity, signature invalid, refresh revoked or expired, stored record absent,
#               revoked or already exchanged, unknown or inactive principal, refresh strategy refusal
#  @return 500: misconfiguration (signable but no key realm wired) or persistence failure
#@end

// The authorization presented: the Authorization header over HTTP, the request entity in-process
entity <- presentedForRefresh(@0)
! => recordCaughtException(@0, @exception) -> 401

// The client presents the SAME wire form the issuer returned at login — for an
// encoded authorization that is the transport form (e.g. a JWT string), not the
// entity. Decode it back to the entity when a decode method is configured; a
// pre-decoded entity (Mode B / in-process) passes through unchanged.
entity <- decodeAuthorizationEntity(@entity, @2)
! => recordCaughtException(@0, @exception) -> 401

// Reject early when the linked authorization is not refreshable at all
requirePresent(if(isAuthorizationRefreshable(@2), 1))
! => recordCaughtException(@0, @exception) -> 401

// Verify the cryptographic signature when applicable. A tampered or
// unsignable-but-misconfigured token must surface as 401, not 500.
// @0 (operationRequest) is forwarded so persisted-mode key lookup can
// scope by caller — anonymous on refresh, by design.
_sigOk <- verifyIfSignable(@entity, @2, @0)
! => recordCaughtException(@0, @exception) -> 401
requirePresent(if(@_sigOk, 1))
! => recordCaughtException(@0, @exception) -> 401

// Refresh-revoked / refresh-expired guards
requirePresent(if(refreshNotRevoked(@entity, @2), 1))
! => recordCaughtException(@0, @exception) -> 401
requirePresent(if(refreshNotExpired(@entity, @2), 1))
! => recordCaughtException(@0, @exception) -> 401

// The stored record of a storable authorization: refused when absent, revoked
// or already exchanged (then every live authorization of the owner is
// revoked); otherwise marked exchanged before a new one is issued (rotation).
_claimed <- claimRefresh(@entity, @2)
! => recordCaughtException(@0, @exception) -> 401

// Resolve the principal from the authorization's ownerId (lookup via the
// authenticator domain's repository), and check it is still active — as a
// login does.
_principal <- findPrincipalByOwnerUuid(@_claimed, @2, @1)
! => recordCaughtException(@0, @exception) -> 401
checkAccountStatus(authenticatorContext(@2), @_principal)
! => recordCaughtException(@0, @exception) -> 401

// Re-derive the authentication: the authenticator's refresh strategy decides
// the authorities of the moment (a refusal refuses the refresh); without one,
// those of the replaced authorization are copied.
_authResult <- refreshAuthentication(@_principal, @_claimed, @2, @0)
! => recordCaughtException(@0, @exception) -> 401

// Issue the new authorization by the login's own path: the declared issuer
// (.authorization(issuer, "method")) when there is one, else build + sign.
// It inherits the refresh window of the one it replaces (refreshNotAfter):
// the window is fixed at login, a refresh does not push it back.
setRequestArg(@0, "refreshNotAfter", refreshWindowEnd(@entity, @2))
// A declared issuer may carry over what it put in the replaced authorization
// (RefreshedAuthorizationSupplierBuilder / @RefreshedAuthorization; null at login).
setRequestArg(@0, "refreshedAuthorization", @entity)
output <- issueAuthorization(@_authResult, @2, @0)
! => recordCaughtException(@0, @exception) -> 500

// Encode the freshly signed authorization to its transport form, if a method
// is declared. Symmetric to CREATE_AUTHORIZATION.
_encoded <- encodeIfPossible(@output, @2)
! => recordCaughtException(@0, @exception) -> 500
setRequestArg(@0, "encodedAuthorization", @_encoded)

// Persist the freshly-minted authorization when storable.
persistIfStorable(@output, @2)
! => recordCaughtException(@0, @exception) -> 500

// Propagate the principal to downstream stages, and the sanitized security
// context the transport renders as the body — symmetric to CREATE_AUTHORIZATION.
setRequestArg(@0, "principal", @_principal)
setRequestArg(@0, "authentication", authenticationResponse(@_authResult, @output, @_encoded, @2))

// Emit the encoded transport form (e.g. JWT) as the output when an encode method
// produced one; otherwise ship the entity. Symmetric to CREATE_AUTHORIZATION.
output <- coalesce(@_encoded, @output) -> 0
