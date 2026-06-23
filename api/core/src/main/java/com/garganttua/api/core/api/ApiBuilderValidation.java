package com.garganttua.api.core.api;

import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.definition.IDomainAuthenticatorAuthorizationDefinition;
import com.garganttua.api.commons.definition.IDomainAuthorizationDefinition;
import com.garganttua.api.commons.definition.IDomainSecurityDefinition;
import com.garganttua.api.core.domain.DomainDefinition;
import com.garganttua.core.reflection.IClass;

/**
 * Build-time security validations for {@link ApiBuilder}: rejects signable authorizations with no
 * key configured, unresolved / unmarked {@code .key(domain)} references, and the inconsistent
 * autoRotate-without-autoGenerate combination. Extracted from {@code ApiBuilder} to keep that wide
 * builder under the file-size gate; behaviour is identical.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class ApiBuilderValidation {

    private ApiBuilderValidation() {
    }

    /**
     * Walks every authenticator domain, finds its linked authorization definition, and refuses the
     * build when the authorization is signable but no key was configured ({@code .key(supplier)} or
     * {@code .key(domain)}). Also rejects a {@code .key(domain)} pointing at a domain that is not a
     * marked {@code @Key} domain.
     */
    static void validateSignableKeyConfig(Map<String, IDomain<?>> domainContexts) throws ApiException {
        for (IDomain<?> domain : domainContexts.values()) {
            validateDomainSignableKey(domain, domainContexts);
        }
    }

    private static void validateDomainSignableKey(IDomain<?> domain,
            Map<String, IDomain<?>> domainContexts) throws ApiException {
        if (!(domain.getDomainDefinition() instanceof DomainDefinition<?> domDef)) {
            return;
        }
        IDomainSecurityDefinition secDef = domDef.domainSecurityDefinition();
        if (secDef == null || secDef.authenticatorDefinition() == null) {
            return;
        }
        IDomainAuthenticatorAuthorizationDefinition authzAuthDef =
                secDef.authenticatorDefinition().authorizationDefinition();
        if (authzAuthDef == null || authzAuthDef.authorizationDomainBuilder() == null) {
            return;
        }

        IDomainBuilder<?> authzDomainBuilder = authzAuthDef.authorizationDomainBuilder();
        IDomain<?> authzDomain = findDomainByEntityClass(domainContexts, authzDomainBuilder.getEntityClass());
        if (authzDomain == null || !isSignable(authzDomain)) {
            return;
        }

        boolean hasSupplier = authzAuthDef.keyRealm() != null;
        boolean hasKeyDomain = authzAuthDef.keyDefinition() != null
                && authzAuthDef.keyDefinition().keyDomain() != null;
        if (!hasSupplier && !hasKeyDomain) {
            throw new ApiException("Domain '" + domain.getDomainName()
                    + "' declares a signable authorization (linked to domain '"
                    + authzDomain.getDomainName()
                    + "') but neither .key(supplier) nor .key(domain) is wired on its "
                    + "authenticator's authorization DSL. Add one before .build().");
        }
        if (hasKeyDomain) {
            validateKeyDomain(domain, authzAuthDef, domainContexts);
        }
    }

    private static boolean isSignable(IDomain<?> authzDomain) {
        IDomainSecurityDefinition authzSecDef =
                authzDomain.getDomainDefinition() instanceof DomainDefinition<?> authzDef
                        ? authzDef.domainSecurityDefinition() : null;
        IDomainAuthorizationDefinition signableDef =
                authzSecDef != null ? authzSecDef.authorizationDefinition() : null;
        return signableDef != null && signableDef.signable();
    }

    private static void validateKeyDomain(IDomain<?> domain,
            IDomainAuthenticatorAuthorizationDefinition authzAuthDef,
            Map<String, IDomain<?>> domainContexts) throws ApiException {
        IClass<?> keyEntityClass = authzAuthDef.keyDefinition().keyDomain().getEntityClass();
        IDomain<?> keyDomain = findDomainByEntityClass(domainContexts, keyEntityClass);
        if (keyDomain == null) {
            throw new ApiException("Domain '" + domain.getDomainName()
                    + "' references a .key(domain) whose entity class '" + keyEntityClass.getName()
                    + "' did not resolve to a registered domain on the API. Make sure "
                    + ".domain(KeyEntity.class) was declared before .build().");
        }
        if (keyDomain.getDomainDefinition().keyDefinition() == null) {
            throw new ApiException("Domain '" + domain.getDomainName()
                    + "' references key domain '" + keyDomain.getDomainName()
                    + "' which is not marked as a @Key domain. Annotate the entity with @Key "
                    + "and its fields with @KeyName / @KeyAlgorithm / @KeySignatureAlgorithm / "
                    + "@KeyForSigning / @KeyForSignatureVerification, or call .security().key().name(...)... "
                    + "on its domain builder.");
        }

        // Rotation creates new keys — it implies generation. Refuse the inconsistent combination at
        // build time so the user catches it before the first sign call.
        var keyConfig = authzAuthDef.keyDefinition();
        if (keyConfig.autoRotate() && !keyConfig.autoGenerate()) {
            throw new ApiException("Domain '" + domain.getDomainName()
                    + "' configures .autoRotate(true) with .autoGenerate(false) on its key DSL — "
                    + "rotation creates a new key, which is a generation. Either flip autoGenerate "
                    + "to true, or flip autoRotate to false.");
        }
    }

    private static IDomain<?> findDomainByEntityClass(Map<String, IDomain<?>> domains, IClass<?> target) {
        if (target == null) {
            return null;
        }
        for (IDomain<?> domain : domains.values()) {
            IClass<?> entityClass = domain.getEntityClass();
            if (entityClass != null && entityClass.equals(target)) {
                return domain;
            }
        }
        return null;
    }
}
