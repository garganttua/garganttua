#!/usr/bin/env gs
#@workflow
#  Business stage of the share operation (PUT /<domain>/{uuid}/shares): replaces the grants of one
#  entity — the tenants and owners that may read it, or read and write it — with those of the body.
#
#  Only the entity's own tenant and owner reach it (MANAGE access): no grant lets a grantee lend the
#  entity further, or take the owner's grants back. The body is a ShareRequest; every token is
#  validated before anything is written.
#
#  @in  operationRequest: [0] IOperationRequest
#  @in  repository: [1] IRepository
#  @in  domainContext: [2] IDomain
#  @out output -> output: Object
#  @return 0: SUCCESS (the entity, with its new grants)
#  @return 400: missing caller or body, malformed grant
#  @return 404: no entity of the caller's own with this uuid
#  @return 500: internal error
#@end
caller <- :arg(@0, "caller")
grants <- :arg(@0, "entity")
lookupType <- :arg(@0, "type")
lookupId <- :arg(@0, "identifier")
requirePresent(@caller)
! => recordCaughtException(@0, @exception) -> 400
requirePresent(@grants)
! => recordCaughtException(@0, @exception) -> 400
grants <- optionalGet(@grants)

// MANAGE access: only the caller's own entity, never one shared with it.
filter <- buildGetOneAccessFilter(@caller, @lookupType, @lookupId, @2, "manage")
! => recordCaughtException(@0, @exception) -> 500
entities <- getEntities(@1, :arg(@0, "pageable"), @filter, :arg(@0, "sort"))
! => recordCaughtException(@0, @exception) -> 500
storedEntity <- first(@entities)
! => recordCaughtException(@0, @exception) -> 404

// Validate and write the grants
storedEntity <- applyShareGrants(@storedEntity, @grants, @2)
! => recordCaughtException(@0, @exception) -> 400

// Sharing changes the entity: it runs the update hooks, like any other write
storedEntity <- runBeforeUpdate(@storedEntity, @0)
! => recordCaughtException(@0, @exception) -> 500
saveEntity(@1, @storedEntity)
! => recordCaughtException(@0, @exception) -> 500
storedEntity <- runAfterUpdate(@storedEntity, @0)
! => recordCaughtException(@0, @exception) -> 500

output <- @storedEntity -> 0
