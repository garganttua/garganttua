package com.garganttua.api.core.repository;

import com.garganttua.api.core.filter.Filter;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.entity.ShareGrant;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.reflection.ObjectAddress;

import com.garganttua.core.observability.Logger;

/**
 * Builds the filter that scopes a repository access to what the caller may see or change.
 *
 * <p>
 * A row is reachable through one of two branches:
 * </p>
 * <ul>
 * <li><b>its own</b> — the row is in the caller's (requested) tenant and, on an owned domain, belongs
 * to the caller. A super owner skips the owner part. A caller without an ownerId, on an owned domain,
 * reaches only the rows that belong to no owner;</li>
 * <li><b>a grant</b> — the row is shared with the caller's tenant or with the caller itself: the legacy
 * {@code .shared(field)} (a tenant, read only) or the {@code .shares(field)} grant tokens
 * ({@link ShareGrant}). A grant to a tenant opens the row to every owner of that tenant. On a
 * hiddenable domain a grant reaches only visible rows.</li>
 * </ul>
 * <p>
 * The {@link AccessMode} picks the grants that count: every grant to read, write grants to change,
 * none to delete or re-share. A public domain is readable as before; only {@link AccessMode#MANAGE}
 * narrows it to the caller's own rows, so one tenant's bulk delete cannot reach another's.
 * </p>
 */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public class RepositoryFilterTools {
	private static final Logger log = Logger.getLogger(RepositoryFilterTools.class);


    private RepositoryFilterTools() {
        // Utility class - no instantiation
    }

    /**
     * Builds the READ filter, multi-tenancy on.
     *
     * @param caller The caller making the request
     * @param baseFilter Optional base filter to include
     * @param domainDefinition The domain definition with entity configuration
     * @return The composed filter, or null if no filtering needed
     */
    public static IFilter buildFilter(ICaller caller, IFilter baseFilter, IDomainDefinition<?> domainDefinition) {
        return buildFilter(caller, baseFilter, domainDefinition, true);
    }

    /** Builds the READ filter. */
    public static IFilter buildFilter(ICaller caller, IFilter baseFilter, IDomainDefinition<?> domainDefinition, boolean multiTenant) {
        return buildFilter(caller, baseFilter, domainDefinition, multiTenant, AccessMode.READ);
    }

    /**
     * Builds the filter scoping {@code mode} for {@code caller}.
     *
     * @param caller the caller making the request; null leaves {@code baseFilter} alone
     * @param baseFilter optional filter the request carries, ANDed with the access filter
     * @param domainDefinition the domain definition with entity configuration
     * @param multiTenant whether the API isolates tenants
     * @param mode what the caller is about to do with the selected rows
     * @return the composed filter, or null if no filtering is needed
     */
    public static IFilter buildFilter(ICaller caller, IFilter baseFilter, IDomainDefinition<?> domainDefinition,
            boolean multiTenant, AccessMode mode) {
        if (caller == null) {
            return baseFilter;
        }
        log.debug("Building {} filter for domain {} with caller tenantId={}, ownerId={}, multiTenant={}",
                    mode, domainDefinition.domainName(), caller.requestedTenantId(), caller.ownerId(), multiTenant);

        FilterContext ctx = new FilterContext(caller, domainDefinition, multiTenant);

        // Super tenant without specific tenant ID bypasses tenant filtering
        if (ctx.isSuperTenantWithoutTenant()) {
            log.debug("Super tenant access without tenant restriction");
            return baseFilter;
        }

        List<Filter> filters = new ArrayList<>();
        if (baseFilter instanceof Filter filter) {
            filters.add(filter);
        }
        Filter access = ctx.isPublicEntity ? publicAccess(ctx, mode) : privateAccess(ctx, mode);
        if (access != null) {
            filters.add(access);
        }
        return all(filters);
    }

    /**
     * A public domain, as it always behaved for reads and updates: (own tenant OR visible) when
     * hiddenable, everything otherwise — narrowed to the caller's rows on an owned domain. MANAGE
     * keeps to the own branch.
     */
    private static Filter publicAccess(FilterContext ctx, AccessMode mode) {
        if (mode == AccessMode.MANAGE) {
            return ownBranch(ctx);
        }
        List<Filter> parts = new ArrayList<>();
        if (ctx.isHiddenableEntity) {
            Filter tenantFilter = ctx.buildTenantFilter();
            Filter visibleFilter = ctx.buildVisibleFilter();
            Filter visibility = tenantFilter != null && visibleFilter != null
                    ? Filter.or(tenantFilter, visibleFilter) : visibleFilter;
            addIfPresent(parts, visibility);
        }
        if (ctx.isOwnedEntity && !ctx.isSuperOwner && ctx.ownerId != null) {
            parts.add(ctx.buildOwnerIdFilter());
        }
        return all(parts);
    }

    /** A private domain: the caller's own rows, OR the rows a grant valid for {@code mode} opens. */
    private static Filter privateAccess(FilterContext ctx, AccessMode mode) {
        Filter own = ownBranch(ctx);
        if (own == null) {
            // Nothing restricts the caller's own branch: every row is already its own.
            return null;
        }
        Filter grants = grants(ctx, mode);
        if (grants == null) {
            return own;
        }
        Filter visibleFilter = ctx.isHiddenableEntity ? ctx.buildVisibleFilter() : null;
        if (visibleFilter != null) {
            grants = Filter.and(visibleFilter, grants);
        }
        return Filter.or(own, grants);
    }

    /** The caller's own rows: its tenant, and on an owned domain itself. Null when nothing restricts. */
    private static Filter ownBranch(FilterContext ctx) {
        List<Filter> parts = new ArrayList<>();
        addIfPresent(parts, ctx.buildTenantFilter());
        if (ctx.isOwnedEntity && !ctx.isSuperOwner) {
            parts.add(ctx.buildOwnerIdFilter());
        }
        return all(parts);
    }

    /** The grants that count for {@code mode}, ORed; null when none apply. */
    private static Filter grants(FilterContext ctx, AccessMode mode) {
        if (mode == AccessMode.MANAGE) {
            return null;
        }
        List<Filter> grants = new ArrayList<>();
        if (mode == AccessMode.READ) {
            addIfPresent(grants, ctx.buildShareFilter());
        }
        ObjectAddress sharesField = ctx.domainDefinition.shares();
        if (sharesField != null) {
            String tenant = ctx.multiTenant ? ctx.requestedTenantId : null;
            List<String> tokens = mode == AccessMode.READ
                    ? ShareGrant.readTokens(tenant, ctx.ownerId)
                    : ShareGrant.writeTokens(tenant, ctx.ownerId);
            if (!tokens.isEmpty()) {
                grants.add(Filter.in(sharesField.toString(), tokens.toArray()));
            }
        }
        if (grants.isEmpty()) {
            return null;
        }
        return grants.size() == 1 ? grants.get(0) : Filter.or(grants.toArray(new Filter[0]));
    }

    private static void addIfPresent(List<Filter> filters, Filter filter) {
        if (filter != null) {
            filters.add(filter);
        }
    }

    /** ANDs the filters: null when there are none, the filter itself when there is one. */
    private static Filter all(List<Filter> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return Filter.and(filters.toArray(new Filter[0]));
    }

    /**
     * Creates a filter for UUID lookup.
     */
    public static IFilter createUuidFilter(ObjectAddress uuidFieldAddress, String uuid) {
        if (uuidFieldAddress == null || uuid == null) {
            return null;
        }
        return Filter.eq(uuidFieldAddress.toString(), uuid);
    }

    /**
     * Creates a filter for ID lookup.
     */
    public static IFilter createIdFilter(ObjectAddress idFieldAddress, String id) {
        if (idFieldAddress == null || id == null) {
            return null;
        }
        return Filter.eq(idFieldAddress.toString(), id);
    }

    /**
     * Creates a filter for UUID lookup using field name.
     */
    public static IFilter createUuidFilter(String uuidFieldName, String uuid) {
        if (uuidFieldName == null || uuid == null) {
            return null;
        }
        return Filter.eq(uuidFieldName, uuid);
    }

    /**
     * Creates a filter for ID lookup using field name.
     */
    public static IFilter createIdFilter(String idFieldName, String id) {
        if (idFieldName == null || id == null) {
            return null;
        }
        return Filter.eq(idFieldName, id);
    }

    /**
     * Internal context class to hold filter building state.
     */
    private static class FilterContext {
        final String requestedTenantId;
        final String ownerId;
        final boolean isSuperOwner;
        final boolean isSuperTenant;
        final boolean isSharedEntity;
        final boolean isHiddenableEntity;
        final boolean isOwnedEntity;
        final boolean isPublicEntity;
        final boolean multiTenant;
        final IDomainDefinition<?> domainDefinition;

        FilterContext(ICaller caller, IDomainDefinition<?> domainDefinition, boolean multiTenant) {
            this.domainDefinition = domainDefinition;
            this.multiTenant = multiTenant;
            this.requestedTenantId = caller.requestedTenantId();
            this.ownerId = caller.ownerId();
            this.isSuperOwner = caller.superOwner();
            this.isSuperTenant = caller.superTenant();
            this.isSharedEntity = domainDefinition.shared() != null;
            this.isHiddenableEntity = domainDefinition.hiddenable() != null;
            this.isOwnedEntity = domainDefinition.owned() != null;
            this.isPublicEntity = Boolean.TRUE.equals(domainDefinition.publik());
        }

        boolean isSuperTenantWithoutTenant() {
            if (!multiTenant) return false;
            return isSuperTenant && (requestedTenantId == null || requestedTenantId.isEmpty());
        }

        Filter buildTenantFilter() {
            if (!multiTenant) return null;
            if (requestedTenantId == null || domainDefinition.entityDefinition() == null) {
                return null;
            }
            ObjectAddress filterField = domainDefinition.entityDefinition().tenantId();
            // For a tenant entity (domain marked .tenant(true)) that does NOT
            // carry a tenantId field, the entity's uuid plays the role of
            // tenantId — without this fallback a non-super caller would see
            // every tenant row (no filter → no isolation). When tenantId IS
            // configured we keep filtering on it for backwards compatibility
            // with domains that mark .tenant(true) + .tenantId(field) together.
            if (filterField == null && Boolean.TRUE.equals(domainDefinition.tenant())) {
                filterField = domainDefinition.entityDefinition().uuid();
            }
            if (filterField == null) {
                return null;
            }
            return Filter.eq(filterField.toString(), requestedTenantId);
        }

        Filter buildShareFilter() {
            if (!multiTenant) return null;
            ObjectAddress shareField = domainDefinition.shared();
            if (!isSharedEntity || requestedTenantId == null || shareField == null) {
                return null;
            }
            return Filter.eq(shareField.toString(), requestedTenantId);
        }

        Filter buildVisibleFilter() {
            ObjectAddress hiddenField = domainDefinition.hiddenable();
            if (!isHiddenableEntity || isSuperOwner || hiddenField == null) {
                return null;
            }
            return Filter.eq(hiddenField.toString(), false);
        }

        /**
         * The rows that belong to the caller. A caller without an ownerId owns nothing: it reaches
         * only the rows that belong to no owner, never every owner's rows of its tenant.
         */
        Filter buildOwnerIdFilter() {
            return Filter.eq(domainDefinition.owned().toString(), ownerId);
        }
    }
}
