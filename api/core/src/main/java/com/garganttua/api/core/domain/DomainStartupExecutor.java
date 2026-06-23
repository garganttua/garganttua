package com.garganttua.api.core.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.api.core.service.OperationResponse;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.workflow.WorkflowExecutionOptions;

/**
 * Startup-time entity seeding for a {@link Domain}: runs the declared startup binders, then creates
 * ({@code createEntity}) and upserts ({@code upsertEntity}) declared entities through the production
 * pipeline (so they get ensure-uuid, tenant/owner stamping, validation and lifecycle hooks).
 * Extracted from {@code Domain} to keep that wide context under the file-size gate; behaviour is
 * identical (it drives {@code Domain.doInvoke} directly to bypass the not-yet-STARTED guard).
 */
final class DomainStartupExecutor<E> {

    private static final Logger log = Logger.getLogger(DomainStartupExecutor.class);

    private final Domain<E> domain;
    private final DomainDefinition<E> domainDefinition;

    DomainStartupExecutor(Domain<E> domain) {
        this.domain = domain;
        this.domainDefinition = domain.domainDefinition;
    }

    void executeStartupBinders() {
        List<IMethodBinder<Void>> startupBinders = this.domainDefinition.startupBinders();
        if (startupBinders == null || startupBinders.isEmpty()) {
            return;
        }
        log.debug("Executing {} startup binders for domain {}", startupBinders.size(),
                this.domainDefinition.domainName());
        for (IMethodBinder<Void> binder : startupBinders) {
            try {
                log.trace("Executing startup binder: {}", binder.getExecutableReference());
                binder.execute();
            } catch (ReflectionException e) {
                log.error("Failed to execute startup binder {} for domain {}: {}",
                        binder.getExecutableReference(), this.domainDefinition.domainName(), e.getMessage(), e);
                throw new ApiException(
                        "Startup binder execution failed for domain " + this.domainDefinition.domainName(), e);
            }
        }
        log.debug("Successfully executed all startup binders for domain {}", this.domainDefinition.domainName());
    }

    /**
     * Best-effort create of declared {@code createEntity(...)} entries at startup, each through the
     * CREATE_ONE pipeline (so a declared entity needs no hand-written uuid). Runs from inside
     * {@code doStart()} before STARTED, so it dispatches to {@code Domain.doInvoke} directly.
     */
    void createStartupEntities() {
        List<E> createEntities = this.domainDefinition.createEntities();
        if (createEntities == null || createEntities.isEmpty()) {
            return;
        }
        IReflection reflection = this.domain.reflection();
        String uuidFieldPath = this.domainDefinition.entityDefinition().uuid().toString();
        log.info("Creating {} startup entities for domain {} (through the create pipeline)",
                createEntities.size(), this.domainDefinition.domainName());
        for (E entity : createEntities) {
            try {
                Object uuidValue = reflection.getFieldValue(entity, uuidFieldPath);
                String uuid = uuidValue != null ? uuidValue.toString() : null;
                if (uuid != null && this.domain.repository.doesExist(uuid)) {
                    log.warn("Startup entity (uuid={}) already exists for domain {}, skipping",
                            uuid, this.domainDefinition.domainName());
                    continue;
                }
                OperationResponse response = bootstrapCreate(entity);
                if (!isPipelineSuccess(response)) {
                    log.warn("Startup entity creation failed for domain {} (best-effort, continuing): {}",
                            this.domainDefinition.domainName(), describeFailure(response));
                    continue;
                }
                log.info("Startup entity created for domain {}", this.domainDefinition.domainName());
            } catch (ApiException e) {
                log.warn("Startup entity creation failed for domain {} (best-effort, continuing): {}",
                        this.domainDefinition.domainName(), e.getMessage());
            }
        }
    }

    /**
     * Fail-fast upsert of declared {@code upsertEntity(...)} entries at startup. Upsert = update in
     * place when the row already exists (matched by uuid, else by a unicity constraint), else create.
     * The in-place UPDATE_ONE keeps the row's identity / non-declared data and self-excludes on
     * unicity; a declared entity matching nothing is simply created.
     */
    void upsertStartupEntities() {
        List<E> upsertEntities = this.domainDefinition.upsertEntities();
        if (upsertEntities == null || upsertEntities.isEmpty()) {
            return;
        }
        IReflection reflection = this.domain.reflection();
        String uuidFieldPath = this.domainDefinition.entityDefinition().uuid().toString();
        log.info("Upserting {} startup entities for domain {} (through the pipeline)",
                upsertEntities.size(), this.domainDefinition.domainName());
        for (E entity : upsertEntities) {
            try {
                String uuid = resolveUpsertTargetUuid(entity);
                OperationResponse result;
                if (uuid != null) {
                    // Update the existing row IN PLACE — pin its identity onto the declared entity so
                    // the merge cannot move the row, then merge the declared (updatable) fields. No
                    // delete: the row keeps its _id/relations and any non-declared data, and the
                    // unicity check self-excludes it (UPDATE_ONE adds $ne uuid).
                    reflection.setFieldValue(entity, uuidFieldPath, uuid);
                    result = bootstrapUpdate(entity, uuid);
                    if (!isPipelineSuccess(result)) {
                        throw bootstrapFailure("update (upsert)", result);
                    }
                } else {
                    result = bootstrapCreate(entity);
                    if (!isPipelineSuccess(result)) {
                        throw bootstrapFailure("create (upsert)", result);
                    }
                }
                log.info("Startup entity upserted for domain {}", this.domainDefinition.domainName());
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException("Failed to upsert startup entity for domain "
                        + this.domainDefinition.domainName(), e);
            }
        }
    }

    /**
     * The uuid of the existing row an upsert must update in place: the declared uuid when present,
     * else the uuid of a row matching one of the entity's unicity constraints (keeping upsert
     * idempotent for entities keyed on a unique business field). Returns {@code null} when nothing
     * matches (a genuine fresh create). Mirrors {@code validateUnicity}'s tenant-scoped lookup.
     */
    private String resolveUpsertTargetUuid(E entity) {
        IReflection reflection = this.domain.reflection();
        var entityDef = this.domainDefinition.entityDefinition();
        String uuidPath = entityDef.uuid().toString();

        Object declaredUuid = reflection.getFieldValue(entity, uuidPath);
        if (declaredUuid != null && this.domain.repository.doesExist(declaredUuid.toString())) {
            return declaredUuid.toString();
        }

        List<Pair<ObjectAddress, UnicityScope>> unicities = entityDef.unicities();
        if (unicities == null || unicities.isEmpty()) {
            return null;
        }
        ObjectAddress tenantIdAddress = entityDef.tenantId();
        for (Pair<ObjectAddress, UnicityScope> unicity : unicities) {
            String matched = matchUnicity(entity, reflection, uuidPath, tenantIdAddress, unicity);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    /** Resolves the existing-row uuid for a single unicity constraint, or null when no row matches. */
    private String matchUnicity(E entity, IReflection reflection, String uuidPath,
            ObjectAddress tenantIdAddress, Pair<ObjectAddress, UnicityScope> unicity) {
        ObjectAddress fieldAddress = unicity.getValue0();
        Object value = reflection.getFieldValue(entity, fieldAddress.toString());
        if (value == null) {
            return null;
        }
        Filter filter = Filter.eq(fieldAddress.toString(), value);
        if (unicity.getValue1() == UnicityScope.tenant && tenantIdAddress != null) {
            Object tenantId = reflection.getFieldValue(entity, tenantIdAddress.toString());
            if (tenantId != null) {
                filter = Filter.and(filter, Filter.eq(tenantIdAddress.toString(), tenantId));
            }
        }
        List<Object> matches = this.domain.repository.getEntities(
                Optional.empty(), Optional.of(filter), Optional.empty());
        if (matches.isEmpty()) {
            return null;
        }
        Object existingUuid = reflection.getFieldValue(matches.get(0), uuidPath);
        return existingUuid != null ? existingUuid.toString() : null;
    }

    /**
     * Dispatches a bootstrap CREATE through the pipeline as an {@link Access#anonymous} operation with
     * a caller that mirrors the entity's own tenant/owner, so CREATE_ONE's ensure-stamping stays
     * idempotent.
     */
    private OperationResponse bootstrapCreate(E entity) {
        OperationDefinition op = OperationDefinition.createOne(
                this.domainDefinition.domainName(), this.domain.getEntityClass(), false, null, Access.anonymous);
        return bootstrapInvoke(op, bootstrapCaller(entity), req -> req.arg("entity", entity));
    }

    /**
     * Dispatches a bootstrap UPDATE-by-uuid through the pipeline (used by upsert to refresh an
     * existing row in place); the declared entity is the merge body, no delete.
     */
    private OperationResponse bootstrapUpdate(E entity, String uuid) {
        OperationDefinition op = OperationDefinition.updateOne(
                this.domainDefinition.domainName(), this.domain.getEntityClass(), false, null, Access.anonymous);
        return bootstrapInvoke(op, bootstrapCaller(entity), req -> {
            req.arg("entity", entity);
            req.arg(IOperationRequest.ENTITY_UUID, uuid);
        });
    }

    /**
     * Builds a bootstrap operation request (same arg shape as a transport-issued one) and runs it on
     * {@code Domain.doInvoke} — the started-check-free pipeline entry.
     */
    private OperationResponse bootstrapInvoke(OperationDefinition op, ICaller caller,
            java.util.function.Consumer<OperationRequest> setup) {
        OperationRequest req = new OperationRequest(new HashMap<>());
        req.arg(IOperationRequest.OPERATION, op);
        req.arg(IOperationRequest.TENANT_ID, caller.tenantId());
        req.arg(IOperationRequest.REQUESTED_TENANT_ID, caller.requestedTenantId());
        req.arg(IOperationRequest.CALLER_ID, caller.callerId());
        req.arg(IOperationRequest.OWNER_ID, caller.ownerId());
        req.arg(IOperationRequest.SUPER_TENANT, caller.superTenant());
        req.arg(IOperationRequest.SUPER_OWNER, caller.superOwner());
        // A startup-declared write is framework-orchestrated, not a client transport call — flag it so
        // guards like requireNotDirectAuthorizationCreate treat it as internal.
        req.arg(com.garganttua.api.core.expression.SecurityExpressions.FRAMEWORK_INTERNAL_WRITE_ARG, Boolean.TRUE);
        setup.accept(req);
        return this.domain.doInvoke(req, WorkflowExecutionOptions.none());
    }

    /** Caller for a bootstrap write: mirrors the entity's own tenant + owner; anonymous when no tenant. */
    private ICaller bootstrapCaller(E entity) {
        IReflection reflection = this.domain.reflection();
        var tenantAddr = this.domainDefinition.entityDefinition().tenantId();
        String tenantId = tenantAddr != null ? asString(reflection.getFieldValue(entity, tenantAddr.toString())) : null;
        var ownedAddr = this.domainDefinition.owned();
        String ownerId = ownedAddr != null ? asString(reflection.getFieldValue(entity, ownedAddr.toString())) : null;
        if (tenantId == null) {
            return Caller.createAnonymousCaller();
        }
        return Caller.createTenantCallerWithOwnerId(tenantId, ownerId);
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static boolean isPipelineSuccess(IOperationResponse response) {
        OperationResponseCode code = response.getResponseCode();
        return code == OperationResponseCode.OK || code == OperationResponseCode.CREATED
                || code == OperationResponseCode.UPDATED || code == OperationResponseCode.DELETED;
    }

    private ApiException bootstrapFailure(String what, IOperationResponse response) {
        Throwable cause = response.getException().orElse(null);
        String base = "Startup " + what + " on domain '" + this.domainDefinition.domainName()
                + "' returned " + response.getResponseCode();
        return cause != null ? new ApiException(base, cause) : new ApiException(base);
    }

    private String describeFailure(IOperationResponse response) {
        Throwable cause = response.getException().orElse(null);
        return response.getResponseCode() + (cause != null ? " — " + cause.getMessage() : "");
    }
}
