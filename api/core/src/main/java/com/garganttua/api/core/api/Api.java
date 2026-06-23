package com.garganttua.api.core.api;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.domain.Domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.protocol.IProtocol;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.security.authorization.IAuthorizationProtocol;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.commons.service.IRequestBuilder;
import com.garganttua.core.injection.BeanReference;
import com.garganttua.core.injection.DiException;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.Predefined;
import com.garganttua.core.lifecycle.AbstractLifecycle;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

import com.garganttua.core.observability.Logger;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public class Api extends AbstractLifecycle implements IApi, com.garganttua.core.bootstrap.banner.IBootstrapSummaryContributor {
	private static final Logger log = Logger.getLogger(Api.class);


    private final IInjectionContext injectionContext;
    private final Map<String, IDomain<?>> domainContexts;
    private final String superTenantId;
    private final boolean superTenantAutoCreate;
    private final boolean multiTenant;
    private final boolean lockSuperTenantCreation;
    private final boolean lockSuperOwnerCreation;
    // Server-side registries of super-tenant / super-owner ids. Populated at
    // onStart (scan), seeded by the auto-created master tenant, and maintained
    // on create/update of the tenant/owner domains. Concurrent: the scan runs
    // on the start thread; maintenance runs on request threads.
    private final java.util.Set<String> superTenantIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> superOwnerIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<IMethodBinder<Void>> startupBinders;
    private final List<ISerializer> serializers;
    private final List<IProtocol<?, ?>> protocols;
    private final List<IAuthorizationProtocol> authorizationProtocols;
    private final com.garganttua.api.commons.context.IAuthoritiesEndpoint authoritiesEndpoint;
    // Resources opened by IApiAutoConfiguration (e.g. a MongoClient); adopted into this Api's
    // lifecycle so onStop() closes them regardless of which runner (neutral core / api shim) booted.
    private volatile List<AutoCloseable> autoConfigResources = java.util.List.of();

    public Api(IInjectionContext injectionContext, Map<String, IDomain<?>> domainContexts,
            String superTenantId, boolean superTenantAutoCreate, boolean multiTenant,
            boolean lockSuperTenantCreation, boolean lockSuperOwnerCreation,
            List<IMethodBinder<Void>> startupBinders, List<ISerializer> serializers,
            List<IProtocol<?, ?>> protocols,
            List<IAuthorizationProtocol> authorizationProtocols,
            com.garganttua.api.commons.context.IAuthoritiesEndpoint authoritiesEndpoint) {
        this.injectionContext = Objects.requireNonNull(injectionContext, "Injection context cannot be null");
        this.domainContexts = Collections.unmodifiableMap(new HashMap<>(
                Objects.requireNonNull(domainContexts, "Domain contexts cannot be null")));
        this.superTenantId = superTenantId;
        this.superTenantAutoCreate = superTenantAutoCreate;
        this.multiTenant = multiTenant;
        this.lockSuperTenantCreation = lockSuperTenantCreation;
        this.lockSuperOwnerCreation = lockSuperOwnerCreation;
        this.startupBinders = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(startupBinders, "Startup binders cannot be null")));
        this.serializers = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(serializers, "Serializers cannot be null")));
        this.protocols = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(protocols, "Protocols cannot be null")));
        this.authorizationProtocols = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(authorizationProtocols, "Authorization protocols cannot be null")));
        // null is a legitimate value: it signals that .exposeAuthorities() was
        // never called, and getAuthoritiesEndpoint() must propagate that null
        // to transport modules so they skip the route.
        this.authoritiesEndpoint = authoritiesEndpoint;
    }

    /**
     * Adopts the resources opened by the {@code IApiAutoConfiguration}s so this Api closes them
     * on {@link #doStop()} (e.g. a {@code MongoClient}). Called once by the builder after assembly.
     *
     * @param resources the closeables to adopt (a defensive copy is taken)
     */
    void adoptAutoConfigurationResources(List<AutoCloseable> resources) {
        this.autoConfigResources = List.copyOf(resources);
    }

    @Override
    public List<ISerializer> getSerializers() {
        return this.serializers;
    }

    @Override
    public List<IProtocol<?, ?>> getProtocols() {
        return this.protocols;
    }

    @Override
    public List<IAuthorizationProtocol> getAuthorizationProtocols() {
        return this.authorizationProtocols;
    }

    @Override
    public com.garganttua.api.commons.context.IAuthoritiesEndpoint getAuthoritiesEndpoint() {
        return this.authoritiesEndpoint;
    }

    @Override
    public List<String> getAuthorities() {
        java.util.NavigableSet<String> sorted = new java.util.TreeSet<>();
        for (IDomain<?> domain : this.domainContexts.values()) {
            if (domain.getDomainDefinition() == null) continue;

            // Source 1: operation-level authorities — every domain operation
            // contributes its effectiveAuthorityName() (null when authority is
            // not enforced).
            List<com.garganttua.api.commons.operation.OperationDefinition> ops =
                    domain.getDomainDefinition().operations();
            if (ops != null) {
                for (com.garganttua.api.commons.operation.OperationDefinition op : ops) {
                    String name = op.effectiveAuthorityName();
                    if (name != null && !name.isBlank()) {
                        sorted.add(name);
                    }
                }
            }

            // Source 2: field-level update authorities — declared via
            // entity().update(field, "auth-name") on the DSL. These guard a
            // specific field of an update operation; they're independent of
            // the operation-level authority and live on the EntityDefinition.
            java.util.Map<com.garganttua.core.reflection.ObjectAddress, String> fieldAuths =
                    domain.getAuthorizedUpdateFieldsAndAuthorizations();
            if (fieldAuths != null) {
                for (String name : fieldAuths.values()) {
                    if (name != null && !name.isBlank()) {
                        sorted.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(sorted);
    }

    @Override
    public List<String> getAuthoritiesForCaller(com.garganttua.api.commons.caller.ICaller caller) {
        if (this.authoritiesEndpoint == null) {
            throw new ApiException("Authorities endpoint is not exposed — call "
                    + ".exposeAuthorities() on ApiBuilder to enable it.");
        }
        com.garganttua.api.commons.operation.Access access = this.authoritiesEndpoint.access();
        // Anonymous bypass — no caller checks needed.
        if (access == com.garganttua.api.commons.operation.Access.anonymous) {
            return getAuthorities();
        }
        // Below this line the caller must be non-null and authenticated.
        if (caller == null) {
            throw new ApiException("Authorities endpoint requires access=" + access
                    + " but no caller was provided.");
        }
        boolean superCaller = caller.superTenant() || caller.superOwner();
        switch (access) {
            case authenticated:
                if (!superCaller && caller.tenantId() == null) {
                    throw new ApiException("Authorities endpoint requires an authenticated caller "
                            + "(no tenantId on the caller).");
                }
                break;
            default:
                break;
        }
        // Authority gate — super-tenant / super-owner bypass it.
        String requiredAuthority = this.authoritiesEndpoint.authority();
        if (requiredAuthority != null && !superCaller) {
            List<String> authorities = caller.authorities();
            if (authorities == null || !authorities.contains(requiredAuthority)) {
                throw new ApiException("Authorities endpoint requires authority '"
                        + requiredAuthority + "', which the caller does not carry.");
            }
        }
        return getAuthorities();
    }

    @Override
    public Optional<IDomain<?>> getDomain(String domainName) {
        return Optional.ofNullable(this.domainContexts.get(domainName));
    }

    @Override
    public IRequestBuilder request(String domainName) {
        IDomain<?> domain = this.domainContexts.get(domainName);
        if (domain == null) {
            throw new ApiException("Domain not found: " + domainName);
        }
        return domain.request();
    }

    public IInjectionContext getInjectionContext() {
        return this.injectionContext;
    }

    @Override
    public String getSuperTenantId() {
        return this.superTenantId;
    }

    public boolean isSuperTenantAutoCreate() {
        return this.superTenantAutoCreate;
    }

    @Override
    public boolean isMultiTenant() {
        return this.multiTenant;
    }

    // --- Super-tenant / super-owner registries ---

    @Override
    public boolean isSuperTenant(String tenantId) {
        return tenantId != null && this.superTenantIds.contains(tenantId);
    }

    @Override
    public boolean isSuperOwner(String ownerId) {
        return ownerId != null && this.superOwnerIds.contains(ownerId);
    }

    @Override
    public java.util.Set<String> getSuperTenantIds() {
        return Collections.unmodifiableSet(this.superTenantIds);
    }

    @Override
    public java.util.Set<String> getSuperOwnerIds() {
        return Collections.unmodifiableSet(this.superOwnerIds);
    }

    @Override
    public void registerSuperTenant(String id) {
        if (id != null && !id.isBlank()) {
            this.superTenantIds.add(id);
        }
    }

    @Override
    public void unregisterSuperTenant(String id) {
        if (id != null) {
            this.superTenantIds.remove(id);
        }
    }

    @Override
    public void registerSuperOwner(String id) {
        if (id != null && !id.isBlank()) {
            this.superOwnerIds.add(id);
        }
    }

    @Override
    public void unregisterSuperOwner(String id) {
        if (id != null) {
            this.superOwnerIds.remove(id);
        }
    }

    @Override
    public boolean isSuperTenantCreationLocked() {
        return this.lockSuperTenantCreation;
    }

    @Override
    public boolean isSuperOwnerCreationLocked() {
        return this.lockSuperOwnerCreation;
    }

    public Map<String, IDomain<?>> getDomains() {
        return this.domainContexts;
    }

    @Override
    public IReflection reflection() {
        return DefaultMapper.reflection();
    }

    @Override
    protected ILifecycle doInit() {
        // Initialize the injection context first
        this.injectionContext.onInit();

        // Create and register repositories for each domain
        for (Map.Entry<String, IDomain<?>> entry : this.domainContexts.entrySet()) {
            String domainName = entry.getKey();
            IDomain<?> domainContext = entry.getValue();
            // Set parent API context reference
            if (domainContext instanceof Domain<?> dc) {
                dc.setApi(this);
            }
            // Initialize the domain context
            try {
                domainContext.onInit();
                log.info("Initialized domain '{}'", domainName);
            } catch (LifecycleException e) {
                log.error("Failed to initialize domain '{}': {}", domainName, e.getMessage());
            }

            // Get the repository from the domain context
            IRepository repository = domainContext.getRepository();

            if (repository != null) {
                // Register the repository in the injection context
                String repositoryName = domainName + "-repository";
                try {
                    BeanReference<IRepository> beanRef = new BeanReference<>(
                            IClass.getClass(IRepository.class),
                            Optional.empty(),
                            Optional.of(repositoryName),
                            new HashSet<>());
                    this.injectionContext.addBean(Predefined.BeanProviders.garganttua.toString(), beanRef, repository);
                    log.info("Registered repository '{}' for domain '{}'", repositoryName, domainName);
                } catch (DiException e) {
                    log.error("Failed to register repository '{}' for domain '{}': {}", repositoryName, domainName, e.getMessage());
                }
            }

        }
        return this;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // intentional identity check: skip the already-started tenant domain instance
    protected ILifecycle doStart() {
        // 1. Start the injection context
        this.injectionContext.onStart();

        // 2. Execute API-level startup binders
        executeStartupBinders();

        // 3. Start tenant domain first (other domains may depend on it) — only in multi-tenant mode
        IDomain<?> tenantDomain = this.multiTenant ? startTenantDomainFirst() : null;

        // 4. Auto-create master tenant if configured — only in multi-tenant mode
        ApiSuperRegistryBootstrap superRegistry = new ApiSuperRegistryBootstrap(this);
        if (tenantDomain != null && this.superTenantAutoCreate) {
            superRegistry.autoCreateMasterTenant(tenantDomain, this.superTenantId);
        }

        // 5. Start all remaining (non-tenant) domain contexts
        for (Map.Entry<String, IDomain<?>> entry : this.domainContexts.entrySet()) {
            String domainName = entry.getKey();
            IDomain<?> domainContext = entry.getValue();
            if (domainContext == tenantDomain) {
                continue; // Already started
            }
            domainContext.onStart();
            log.info("Started domain '{}'", domainName);
        }

        // 6. Populate the super-tenant / super-owner registries by scanning the
        //    tenant/owner domains for entities whose superTenant/superOwner flag
        //    is set. Runs after every domain (and the master auto-create) is
        //    started, so already-persisted supers (incl. the stamped master) are
        //    discovered. Exempt from the creation lock — this is the trusted
        //    bootstrap snapshot of what is super.
        superRegistry.scanSuperRegistries();

        return this;
    }

    private void executeStartupBinders() {
        if (this.startupBinders.isEmpty()) {
            return;
        }
        log.debug("Executing {} API-level startup binders", this.startupBinders.size());
        for (IMethodBinder<Void> binder : this.startupBinders) {
            try {
                log.trace("Executing startup binder: {}", binder.getExecutableReference());
                binder.execute();
            } catch (Exception e) {
                throw new ApiException("Failed to execute API startup binder '"
                        + binder.getExecutableReference() + "'", e);
            }
        }
    }

    private IDomain<?> startTenantDomainFirst() {
        for (Map.Entry<String, IDomain<?>> entry : this.domainContexts.entrySet()) {
            IDomain<?> domainContext = entry.getValue();
            if (domainContext.isTenantEntity()) {
                String domainName = entry.getKey();
                domainContext.onStart();
                log.info("Started tenant domain '{}' (priority)", domainName);
                return domainContext;
            }
        }
        return null;
    }

    @Override
    protected ILifecycle doStop() {
        // Stop all domain contexts first
        for (Map.Entry<String, IDomain<?>> entry : this.domainContexts.entrySet()) {
            String domainName = entry.getKey();
            IDomain<?> domainContext = entry.getValue();
            try {
                domainContext.onStop();
                log.info("Stopped domain '{}'", domainName);
            } catch (LifecycleException e) {
                log.error("Failed to stop domain '{}': {}", domainName, e.getMessage());
            }
        }

        // Stop the injection context
        this.injectionContext.onStop();

        // Close auto-configuration resources (e.g. a MongoClient) last, best-effort.
        for (AutoCloseable resource : this.autoConfigResources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close auto-configuration resource {}: {}",
                        resource.getClass().getSimpleName(), e.getMessage());
            }
        }

        return this;
    }

    @Override
    public String getSummaryCategory() {
        return "Garganttua API";
    }

    @Override
    public Map<String, String> getSummaryItems() {
        return ApiSummary.items(this.multiTenant, this.superTenantId, this.superTenantAutoCreate,
                this.domainContexts);
    }

    @Override
    protected ILifecycle doFlush() {
        // Flush all domain contexts
        for (Map.Entry<String, IDomain<?>> entry : this.domainContexts.entrySet()) {
            String domainName = entry.getKey();
            IDomain<?> domainContext = entry.getValue();
            try {
                domainContext.onFlush();
                log.info("Flushed domain '{}'", domainName);
            } catch (LifecycleException e) {
                log.error("Failed to flush domain '{}': {}", domainName, e.getMessage());
            }
        }
        return this;
    }

}
