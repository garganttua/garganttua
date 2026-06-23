package com.garganttua.api.core.expression;

import java.time.Instant;
import java.util.List;

import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.api.commons.definition.IAuthenticatorDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.IMethodReturn;
import com.garganttua.core.reflection.binders.IContextualMethodBinder;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.runtime.RuntimeExpressionContext;

/**
 * Shared field-population / detail helpers for the security expression classes
 * ({@link SecurityExpressions} and its splits). Extracted to keep those expression registries under
 * the file-size gate and their entry methods under the method-length gate; behaviour is identical.
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", // repeated "unchecked" suppression strings
        "PMD.ReturnEmptyCollectionRatherThanNull"}) // null authorities (absent) is distinct from empty
final class SecurityExpressionsSupport {

    private SecurityExpressionsSupport() {
    }

    /**
     * Reconstructs an authorization entity by invoking its decode method on a fresh instance with the
     * raw bytes — factory-style (returns a fresh entity) or populate-style (void / returns this).
     * Wraps reflective failures in an ApiException.
     */
    static Object invokeDecode(IClass<?> entityClass, String methodName, byte[] rawBytes) {
        try {
            IReflection reflection = DefaultMapper.reflection();
            Object entity = entityClass.getConstructor().newInstance();
            com.garganttua.core.reflection.IMethod method = reflection.resolveMethod(entityClass, methodName)
                    .orElseThrow(() -> new ApiException("decodeAuthorizationEntity: method '" + methodName
                            + "' not found on " + entityClass.getName()));
            Object result = method.invoke(entity, rawBytes);
            if (result != null && entityClass.getType() instanceof Class<?> raw2 && raw2.isInstance(result)) {
                return result;
            }
            return entity;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("decodeAuthorizationEntity failed: " + e.getMessage(), e);
        }
    }

    /**
     * Invokes {@code getDataToSign} on an authorization entity, signs the bytes with the realm's
     * signing key and writes the signature back into the configured signature field. Wraps reflective
     * / crypto failures in an ApiException.
     */
    static boolean doSign(Object authzEntity, ObjectAddress dataMethod, ObjectAddress sigField,
            IKeyRealm realm) {
        try {
            IReflection reflection = DefaultMapper.reflection();
            byte[] data = reflection.invokeMethod(authzEntity, dataMethod.toString(),
                    IClass.getClass(byte[].class));
            if (data == null) {
                throw new ApiException("signAuthorization: getDataToSign returned null");
            }
            IKey key = realm.getKeyForSigning();
            reflection.setFieldValue(authzEntity, sigField, key.sign(data));
            return true;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("signAuthorization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs a domain's custom issuer binder ({@code .authenticator().authorization(issuer,"method")}):
     * publishes the authentication / domain / request into the runtime context and returns the token
     * the method produces. Throws when the method produces none.
     */
    @SuppressWarnings("unchecked")
    static Object runCustomIssuer(IMethodBinder<?> issuerBinder, IAuthentication authResult,
            IDomain<?> authenticatorDomain, IOperationRequest opReq) {
        IRuntimeContext<?, ?> runtimeCtx = RuntimeExpressionContext.get();
        if (runtimeCtx != null) {
            runtimeCtx.setVariable("authentication", authResult);
            runtimeCtx.setVariable("domainContext", authenticatorDomain);
            if (opReq != null) {
                runtimeCtx.setVariable("request", opReq);
            }
        }
        Optional<? extends IMethodReturn<?>> result;
        if (issuerBinder instanceof IContextualMethodBinder<?, ?> contextualBinder) {
            result = ((IContextualMethodBinder<?, Object>) contextualBinder).execute(runtimeCtx);
        } else {
            result = issuerBinder.execute();
        }
        Object token = result.isPresent() ? result.get().single() : null;
        if (token == null) {
            throw new ApiException("issueAuthorization: the custom issuer method returned no authorization");
        }
        return token;
    }

    /**
     * Runs a domain's custom {@code .authorization().reconcile(...)} binder: publishes the verified
     * authentication / domain / request into the runtime context and returns the {@link ICaller} the
     * method resolves (which owns the result entirely — its super flags are trusted). Throws when the
     * method does not return an {@link ICaller}.
     */
    @SuppressWarnings("unchecked")
    static ICaller runCustomReconcile(IMethodBinder<?> binder, IAuthentication authentication,
            IDomain<?> dc, IOperationRequest opReq) {
        IRuntimeContext<?, ?> runtimeCtx = RuntimeExpressionContext.get();
        if (runtimeCtx != null) {
            runtimeCtx.setVariable("authentication", authentication);
            if (dc != null) {
                runtimeCtx.setVariable("domainContext", dc);
            }
            if (opReq != null) {
                runtimeCtx.setVariable("request", opReq);
            }
        }
        Optional<? extends IMethodReturn<?>> result;
        if (binder instanceof IContextualMethodBinder<?, ?> contextualBinder) {
            result = ((IContextualMethodBinder<?, Object>) contextualBinder).execute(runtimeCtx);
        } else {
            result = binder.execute();
        }
        Object resolved = result.isPresent() ? result.get().single() : null;
        if (!(resolved instanceof ICaller resolvedCaller)) {
            throw new ApiException("reconcileCaller: the custom reconcile method must return an ICaller");
        }
        return resolvedCaller;
    }

    /**
     * Runs a single authentication's {@code applySecurityOnEntity} binder on the entity (e.g. hashing
     * a password): publishes entity/domain/request into the runtime context and returns the secured
     * entity (the binder may mutate in place — same ref — or return a replacement). No-op when the
     * authentication or its binder is null.
     */
    @SuppressWarnings("unchecked")
    static Object applyOneSecurityBinder(IAuthenticationDefinition auth, Object entity,
            IDomain<?> domain, IOperationRequest opReq) {
        if (auth == null) {
            return entity;
        }
        IMethodBinder<?> binder = auth.applySecurityOnEntityMethodBinder();
        if (binder == null) {
            return entity;
        }
        IRuntimeContext<?, ?> runtimeCtx = RuntimeExpressionContext.get();
        if (runtimeCtx != null) {
            runtimeCtx.setVariable("entity", entity);
            if (domain != null) {
                runtimeCtx.setVariable("domainContext", domain);
            }
            if (opReq != null) {
                runtimeCtx.setVariable("request", opReq);
            }
        }
        Optional<? extends IMethodReturn<?>> result;
        if (binder instanceof IContextualMethodBinder<?, ?> contextualBinder) {
            result = ((IContextualMethodBinder<?, Object>) contextualBinder).execute(runtimeCtx);
        } else {
            result = binder.execute();
        }
        Object secured = result.isPresent() ? result.get().single() : null;
        return secured != null ? secured : entity;
    }

    /**
     * The trusted security context (tenantId / ownerId read off a token, super status from the
     * server registries) that the pipeline reconciles a protocol caller against.
     */
    record TokenSecurityContext(String tenantId, String ownerId, boolean superTenant, boolean superOwner) {
    }

    /** Reads the tenant / owner identity and super status off a token in its authorization domain. */
    static TokenSecurityContext readTokenSecurityContext(IDomain<?> authzDomain, Object token) {
        if (authzDomain == null) {
            return new TokenSecurityContext(null, null, false, false);
        }
        ObjectAddress tenantAddr = authzDomain.getTenantIdFieldAddress();
        String tenantId = tenantAddr != null ? SecurityExpressions.readField(token, tenantAddr) : null;
        ObjectAddress ownedAddr = authzDomain.getDomainDefinition() != null
                ? authzDomain.getDomainDefinition().owned() : null;
        String ownerId = ownedAddr != null ? SecurityExpressions.readField(token, ownedAddr) : null;
        boolean superTenant = false;
        boolean superOwner = false;
        IApi api = SecurityExpressions.apiOf(authzDomain);
        if (api != null) {
            superTenant = tenantId != null && api.isSuperTenant(tenantId);
            superOwner = ownerId != null && api.isSuperOwner(ownerId);
        }
        return new TokenSecurityContext(tenantId, ownerId, superTenant, superOwner);
    }

    /** Reads the authorities list off a token via the authorization definition; null when absent. */
    @SuppressWarnings("unchecked")
    static List<String> readTokenAuthorities(Object token, IDomainAuthorizationDefinition authzDef,
            IReflection reflection, com.garganttua.core.observability.Logger log) {
        if (authzDef == null || authzDef.authorities() == null) {
            return null;
        }
        try {
            Object raw = reflection.getFieldValue(token, authzDef.authorities().toString());
            if (raw instanceof List<?> list) {
                return (List<String>) list;
            }
        } catch (RuntimeException e) {
            log.trace("readTokenAuthorities: could not read authorities: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Instantiates the authorization entity on the (owned) authorization domain, stamps its uuid /
     * qualified owner / tenant identity, then populates the type / authorities / expiration /
     * refresh-token fields. Wrapping all reflective failures in an ApiException.
     */
    static Object buildAuthorizationEntity(IDomain<?> authzDomain, IDomain<?> authenticatorDomain,
            IDomainAuthorizationDefinition authzDef, IAuthenticatorDefinition authDef,
            IAuthentication authResult, Object principalUuid, Object tenantId, IReflection reflection) {
        try {
            if (authzDomain == null) {
                throw new ApiException("createAuthorizationEntity: authorization domain not configured");
            }
            if (!authzDomain.isOwnedEntity()) {
                throw new ApiException("Authorization domain '" + authzDomain.getDomainName()
                        + "' must be owned (use .owned(field) on the domain builder)");
            }

            Object entity = authzDomain.getEntityClass().getConstructor().newInstance();

            ObjectAddress uuidAddress = authzDomain.getEntityDefinition().uuid();
            if (uuidAddress != null) {
                reflection.setFieldValue(entity, uuidAddress.toString(),
                        com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().toString());
            }

            // The authorization is owned by the authenticated principal. Store the owner id qualified
            // with the principal's domain (${domainName}:${uuid}) so the value is self-describing and
            // consistent with every other ownerId; the Caller derived from the token and the
            // repository owner filter both carry the qualified form.
            ObjectAddress ownedField = authzDomain.getDomainDefinition().owned();
            if (ownedField != null && principalUuid != null) {
                reflection.setFieldValue(entity, ownedField,
                        com.garganttua.api.commons.caller.OwnerIds.qualify(
                                authenticatorDomain.getDomainName(), principalUuid.toString()));
            }

            ObjectAddress tenantField = authzDomain.getTenantIdFieldAddress();
            if (tenantField != null && tenantId != null) {
                reflection.setFieldValue(entity, tenantField, tenantId);
            }

            populateAuthorizationEntityFields(entity, authzDef, authDef, authResult, reflection);
            return entity;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Failed to create authorization entity: " + e.getMessage(), e);
        }
    }

    /**
     * Populates the type / authorities / creation / expiration / revoked and refresh-token fields of a
     * freshly-instantiated authorization entity from the authentication result and the authenticator's
     * authorization definition. Identity / owner / tenant fields are stamped by the caller.
     */
    static void populateAuthorizationEntityFields(Object entity, IDomainAuthorizationDefinition authzDef,
            IAuthenticatorDefinition authDef, IAuthentication authResult, IReflection reflection) {
        if (authzDef.type() != null && authResult.authorization() != null) {
            reflection.setFieldValue(entity, authzDef.type(), authResult.authorization());
        }
        if (authzDef.authorities() != null && authResult.authorities() != null) {
            reflection.setFieldValue(entity, authzDef.authorities(), authResult.authorities());
        }
        if (authzDef.creation() != null) {
            reflection.setFieldValue(entity, authzDef.creation(), Instant.now());
        }
        if (authzDef.expiration() != null && authDef.authorizationDefinition() != null) {
            var authzAuthDef = authDef.authorizationDefinition();
            if (authzAuthDef.unit() != null && authzAuthDef.duration() > 0) {
                long millis = authzAuthDef.unit().toMillis(authzAuthDef.duration());
                reflection.setFieldValue(entity, authzDef.expiration(), Instant.now().plusMillis(millis));
            }
        }
        if (authzDef.revoked() != null) {
            reflection.setFieldValue(entity, authzDef.revoked(), false);
        }

        // Refresh-token fields — populated when the authorization is refreshable. The expiration
        // window comes from the authenticator's authorization def (refreshLifeTime); the revoked flag
        // starts false so the authorization domain's repository can later flip it to invalidate.
        if (authzDef.refreshable()) {
            if (authzDef.refreshExpiration() != null && authDef.authorizationDefinition() != null) {
                var authzAuthDef = authDef.authorizationDefinition();
                if (authzAuthDef.refreshUnit() != null && authzAuthDef.refreshDuration() > 0) {
                    long millis = authzAuthDef.refreshUnit().toMillis(authzAuthDef.refreshDuration());
                    reflection.setFieldValue(entity, authzDef.refreshExpiration(),
                            Instant.now().plusMillis(millis));
                }
            }
            if (authzDef.refreshRevoked() != null) {
                reflection.setFieldValue(entity, authzDef.refreshRevoked(), false);
            }
        }
    }
}
