#!/usr/bin/env gs

#@workflow
#  Reads the CALLER'S OWN entity on an authenticator domain.
#
#  Identical to READ_ONE once the identity is known — same filter, same injection, same afterGet
#  hooks, same projection. What differs is where the identity comes from: the VERIFIED caller, never
#  the path and never a header. There is no input by which a caller could name someone else, which
#  is what makes this route safe to grant on its own.
#
#  @in operationRequest: [0] IOperationRequest
#  @in repository: [1] IRepository
#  @in domainContext: [2] IDomainContext
#  @out output -> output: Object
#  @return 0: SUCCESS
#@end

caller <- :arg(@0, "caller")
projection <- :arg(@0, "projection")

requirePresent(@caller)
! => recordCaughtException(@0, @exception) -> 400

// The caller's own uuid, read off its qualified ownerId and checked against THIS domain: a token
// minted by another authenticator must not resolve here.
selfUuid <- callerSelfUuid(@caller, @2)
! => recordCaughtException(@0, @exception) -> 403

// From here on this is READ_ONE, with the identity it just derived.
filter <- buildGetOneFilter(@caller, "uuid", @selfUuid, @2)
! => recordCaughtException(@0, @exception) -> 500

daoProjection <- effectiveDaoProjection(@2, @projection)
entities <- getEntitiesProjected(@1, :arg(@0, "pageable"), @filter, :arg(@0, "sort"), @daoProjection)
! => recordCaughtException(@0, @exception) -> 500

// A verified caller whose own row is gone: the token outlived the account.
entity <- first(@entities)
! => recordCaughtException(@0, @exception) -> 404

entities <- asList(@entity)
entities <- doInjection(@0, @entities)
! => recordCaughtException(@0, @exception) -> 500
entities <- runAfterGet(@entities, @0)
! => recordCaughtException(@0, @exception) -> 500

entities <- projectFields(@entities, @2, @projection)
! => recordCaughtException(@0, @exception) -> 400
entity <- first(@entities)

output <- @entity -> 0
