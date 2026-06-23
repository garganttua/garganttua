package com.garganttua.core.injection.context.dsl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.bootstrap.annotations.Bootstrap;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.annotations.ConfigurableBuilder;
import com.garganttua.core.dsl.IBuilderObserver;
import com.garganttua.core.dsl.IObservableBuilder;
import com.garganttua.core.dsl.MultiSourceCollector;
import com.garganttua.core.dsl.dependency.AbstractAutomaticDependentBuilder;
import com.garganttua.core.dsl.dependency.DependencySpec;
import com.garganttua.core.injection.IBeanProvider;
import com.garganttua.core.injection.IInjectableElementResolver;
import com.garganttua.core.injection.IInjectableElementResolverBuilder;
import com.garganttua.core.injection.IInjectionChildContextFactory;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.IPropertyProvider;
import com.garganttua.core.injection.Predefined;
import com.garganttua.core.injection.SuppressFBWarnings;
import com.garganttua.core.injection.annotations.BeanProvider;
import com.garganttua.core.injection.annotations.Fixed;
import com.garganttua.core.injection.annotations.Null;
import com.garganttua.core.injection.annotations.Property;
import com.garganttua.core.injection.annotations.PropertyProvider;
import com.garganttua.core.injection.annotations.Prototype;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.beans.resolver.PrototypeElementResolver;
import com.garganttua.core.injection.context.beans.resolver.SingletonElementResolver;
import com.garganttua.core.injection.context.properties.resolver.PropertyElementResolver;
import com.garganttua.core.injection.context.resolver.FixedElementResolver;
import com.garganttua.core.injection.context.resolver.NullElementResolver;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.annotations.ReflectedBuilder;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.SupplyException;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Root fluent builder that assembles an {@link IInjectionContext} from bean providers,
 * property providers, child context factories, qualifiers, and element resolvers.
 *
 * <p>Sources are merged by priority (manual over built-in/auto-detected). Classpath
 * auto-detection is delegated to {@link InjectionAutoDetector} once an {@link IReflection}
 * dependency is resolved. Discoverable as a bootstrap builder via {@link InjectionContextBuilderFactory}.
 */
@Bootstrap
@ReflectedBuilder
@Reflected
@ConfigurableBuilder("injection")
// AvoidFieldNameMatchingMethodName: the resolvers() accessor intentionally mirrors its backing field name.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class InjectionContextBuilder extends AbstractAutomaticDependentBuilder<IInjectionContextBuilder, IInjectionContext>
        implements IInjectionContextBuilder {
    private static final Logger log = Logger.getLogger(InjectionContextBuilder.class);

    private static final String SCOPE_NULL_MSG = "Scope cannot be null";

    private static final String SOURCE_MANUAL = "manual";
    private static final String SOURCE_BUILT_IN = "built-in";
    private static final String SOURCE_AUTO_DETECTED = "auto-detected";

    // package-private: read by InjectionAutoDetector
    final Set<String> packages = new HashSet<>();

    // Bean providers: built-in (P1) + manual (P0)
    private final Map<String, IBeanProviderBuilder> manualBeanProviders = new HashMap<>();
    private final Map<String, IBeanProviderBuilder> builtInBeanProviders = new HashMap<>();
    private final MultiSourceCollector<String, IBeanProviderBuilder> beanProviderCollector;

    // Property providers: built-in (P1) + manual (P0)
    private final Map<String, IPropertyProviderBuilder> manualPropertyProviders = new HashMap<>();
    private final Map<String, IPropertyProviderBuilder> builtInPropertyProviders = new HashMap<>();
    private final MultiSourceCollector<String, IPropertyProviderBuilder> propertyProviderCollector;

    // Child context factories: manual (P0) + auto-detected (P1)
    private final Map<String, IInjectionChildContextFactory<? extends IInjectionContext>> manualChildContextFactories = new HashMap<>();
    final Map<String, IInjectionChildContextFactory<? extends IInjectionContext>> autoDetectedChildContextFactories = new HashMap<>();
    private final MultiSourceCollector<String, IInjectionChildContextFactory<? extends IInjectionContext>> childContextFactoryCollector;

    // Qualifiers: manual (P0) + auto-detected (P1)
    private final Map<String, IClass<? extends Annotation>> manualQualifiers = new HashMap<>();
    final Map<String, IClass<? extends Annotation>> autoDetectedQualifiers = new HashMap<>();
    private final MultiSourceCollector<String, IClass<? extends Annotation>> qualifierCollector;

    IInjectableElementResolverBuilder resolvers;
    private Set<IBuilderObserver<IInjectionContextBuilder, IInjectionContext>> observers = new HashSet<>();

    private static <K, V> MultiSourceCollector<K, V> pairedCollector(Map<K, V> manual, Map<K, V> secondary,
            String secondarySource) {
        MultiSourceCollector<K, V> collector = new MultiSourceCollector<>();
        collector.source(mapSupplier(manual), 0, SOURCE_MANUAL);
        collector.source(mapSupplier(secondary), 1, secondarySource);
        return collector;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> ISupplier<Map<K, V>> mapSupplier(Map<K, V> map) {
        return new ISupplier<>() {
            @Override
            public Optional<Map<K, V>> supply() throws SupplyException {
                return Optional.of(map);
            }
            @Override
            public Type getSuppliedType() {
                return Map.class;
            }
            @Override
            public IClass<Map<K, V>> getSuppliedClass() {
                return (IClass<Map<K, V>>) (IClass<?>) IClass.getClass(Map.class);
            }
        };
    }

    /**
     * Creates a new injection context builder seeded with the default bean/property providers and resolver.
     *
     * @return a fresh injection context builder
     * @throws DslException if the builder cannot be initialised
     */
    public static IInjectionContextBuilder builder() throws DslException {
        return new InjectionContextBuilder();
    }

    private IObservableBuilder<?, ?> reflectionBuilderRef;

    /**
     * Creates an injection context builder, initialising the source collectors, the built-in
     * Garganttua bean and property providers, the default element resolver, and the framework's
     * own package scan.
     *
     * @throws DslException if initialisation fails
     */
    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW",
            justification = "Builder constructor legitimately propagates DslException from dependency-spec "
                    + "wiring; no partially-constructed instance is published before completion.")
    public InjectionContextBuilder() throws DslException {
        super(Set.of(
                DependencySpec.requireAutoDetect(IClass.getClass(IReflectionBuilder.class)),
                DependencySpec.require(IClass.getClass(IReflectionBuilder.class))));
        // Initialize collectors (manual = P0, built-in/auto-detected = P1)
        this.beanProviderCollector = pairedCollector(manualBeanProviders, builtInBeanProviders, SOURCE_BUILT_IN);
        this.propertyProviderCollector = pairedCollector(manualPropertyProviders, builtInPropertyProviders, SOURCE_BUILT_IN);
        this.childContextFactoryCollector = pairedCollector(manualChildContextFactories, autoDetectedChildContextFactories, SOURCE_AUTO_DETECTED);
        this.qualifierCollector = pairedCollector(manualQualifiers, autoDetectedQualifiers, SOURCE_AUTO_DETECTED);
        // Built-in providers
        this.builtInBeanProviders.put(Predefined.BeanProviders.garganttua.toString(),
                new BeanProviderBuilder(this).autoDetect(false));
        this.builtInPropertyProviders.put(Predefined.PropertyProviders.garganttua.toString(),
                new PropertyProviderBuilder(this).autoDetect(false));
        this.resolvers = new InjectableElementResolverBuilder(this);
        this.addPackageInternal("com.garganttua.core.injection");
        log.debug("Initialized default bean and property providers and resolver");
    }

    final Map<String, IBeanProviderBuilder> getAllBeanProviders() {
        return this.beanProviderCollector.build();
    }

    private Map<String, IPropertyProviderBuilder> getAllPropertyProviders() {
        return this.propertyProviderCollector.build();
    }

    /**
     * Enables or disables classpath auto-detection, propagating the flag to all bean providers,
     * property providers, and the resolver builder.
     *
     * @param b {@code true} to enable auto-detection
     * @return this builder for chaining
     * @throws DslException if propagation fails
     */
    @Override
    public IInjectionContextBuilder autoDetect(boolean b) throws DslException {
        getAllBeanProviders().values().forEach(bp -> bp.autoDetect(b));
        getAllPropertyProviders().values().forEach(pp -> pp.autoDetect(b));
        this.resolvers.autoDetect(b);
        return super.autoDetect(b);
    }

    /**
     * Registers a manual child context factory. If the context is already built and the factory
     * class is new, it is also forwarded to the built context.
     *
     * @param factory the child context factory; must not be {@code null}
     * @return this builder for chaining
     */
    @Override
    public IInjectionContextBuilder childContextFactory(
            IInjectionChildContextFactory<? extends IInjectionContext> factory) {
        Objects.requireNonNull(factory, "ChildContextFactory cannot be null");
        String key = factory.getClass().getName();
        boolean alreadyRegistered = this.manualChildContextFactories.containsKey(key);
        this.manualChildContextFactories.put(key, factory);
        log.debug("Added new child context factory: {}", factory);
        // Only forward to the built context when this is a genuinely new factory
        // class. Re-forwarding an already-registered class triggers the context's
        // dedup WARN even though the call is a harmless no-op.
        if (!alreadyRegistered && this.built != null) {
            this.built.registerChildContextFactory(factory);
            log.debug("Registered child context factory to built context: {}", factory);
        }
        return this;
    }

    private Map<String, IBeanProvider> buildBeanProviders() {
        Set<IClass<? extends Annotation>> allQualifiers = new HashSet<>(this.qualifierCollector.build().values());
        return getAllBeanProviders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    IBeanProviderBuilder provider = entry.getValue();
                    if (provider instanceof BeanProviderBuilder bpb) {
                        bpb.setQualifierAnnotations(allQualifiers);
                        if (this.reflectionBuilderRef != null) {
                            bpb.provide(this.reflectionBuilderRef);
                        }
                        bpb.provide(this.resolvers);
                        log.debug("Configured BeanProviderBuilder for scope: {}", entry.getKey());
                    }
                    return provider.build();
                }));
    }

    private Map<String, IPropertyProvider> buildPropertyProviders() {
        return getAllPropertyProviders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().build()));
    }

    /**
     * Registers a manual bean provider under the given scope, wiring it to this builder and
     * propagating the current auto-detect flag and packages.
     *
     * @param scope    the scope name; must not be {@code null}
     * @param provider the bean provider builder; must not be {@code null}
     * @return the registered provider builder for chaining
     */
    @Override
    public IBeanProviderBuilder beanProvider(String scope, IBeanProviderBuilder provider) {
        Objects.requireNonNull(scope, SCOPE_NULL_MSG);
        Objects.requireNonNull(provider, "BeanProvider cannot be null");
        provider.setUp(this);
        provider.autoDetect(isAutoDetected());
        manualBeanProviders.put(scope, provider);
        provider.withPackages(this.packages.stream().toArray(String[]::new));
        log.debug("Added bean provider for scope: {}", scope);
        return provider;
    }

    /**
     * Returns the bean provider builder for the given scope, creating and registering an
     * empty one (under the manual source) if none exists yet.
     *
     * <p>Create-if-absent makes the fluent form {@code beanProvider("app").withBean(...)}
     * work for a brand-new scope, and lets external configuration target arbitrary scopes.</p>
     *
     * @param scope the scope name; must not be {@code null}
     * @return the provider builder for the scope (never {@code null})
     */
    @Override
    public IBeanProviderBuilder beanProvider(String scope) {
        Objects.requireNonNull(scope, SCOPE_NULL_MSG);
        IBeanProviderBuilder provider = getAllBeanProviders().get(scope);
        if (provider == null) {
            log.debug("Creating bean provider for new scope '{}'", scope);
            provider = new BeanProviderBuilder(this).autoDetect(false);
            this.manualBeanProviders.put(scope, provider);
        }
        return provider;
    }

    /**
     * Registers a manual property provider under the given scope, wiring it to this builder.
     *
     * @param scope    the scope name; must not be {@code null}
     * @param provider the property provider builder; must not be {@code null}
     * @return the registered provider builder for chaining
     */
    @Override
    public IPropertyProviderBuilder propertyProvider(String scope, IPropertyProviderBuilder provider) {
        Objects.requireNonNull(scope, SCOPE_NULL_MSG);
        Objects.requireNonNull(provider, "PropertyProvider cannot be null");
        provider.setUp(this);
        manualPropertyProviders.put(scope, provider);
        log.debug("Added property provider for scope: {}", scope);
        return provider;
    }

    /**
     * Returns the property provider builder registered under the given scope.
     *
     * @param scope the scope name; must not be {@code null}
     * @return the provider builder, or {@code null} if none is registered for the scope
     */
    @Override
    public IPropertyProviderBuilder propertyProvider(String scope) {
        Objects.requireNonNull(scope, SCOPE_NULL_MSG);
        IPropertyProviderBuilder provider = getAllPropertyProviders().get(scope);
        if (provider == null) {
            log.debug("Creating property provider for new scope '{}'", scope);
            provider = new PropertyProviderBuilder(this).autoDetect(false);
            this.manualPropertyProviders.put(scope, provider);
        }
        return provider;
    }

    /**
     * Adds several packages to scan, propagating them to bean providers and the resolver builder.
     *
     * @param packageNames the packages to scan
     * @return this builder for chaining
     */
    @Override
    public IInjectionContextBuilder withPackages(String[] packageNames) {
        this.packages.addAll(Set.of(packageNames));
        getAllBeanProviders().values().forEach(p -> p.withPackages(packageNames));
        this.resolvers.withPackages(packageNames);
        log.debug("Added packages: {}", Arrays.toString(packageNames));
        return this;
    }

    /**
     * Adds a package to scan, propagating it to bean providers and the resolver builder.
     *
     * @param packageName the package to scan
     * @return this builder for chaining
     */
    @Override
    public IInjectionContextBuilder withPackage(String packageName) {
        addPackageInternal(packageName);
        return this;
    }

    private void addPackageInternal(String packageName) {
        this.packages.add(packageName);
        getAllBeanProviders().values().forEach(p -> p.withPackage(packageName));
        this.resolvers.withPackage(packageName);
        log.debug("Added package: {}", packageName);
    }

    /** {@return the element resolver builder used to register custom {@link IInjectableElementResolver}s} */
    @Override
    public IInjectableElementResolverBuilder resolvers() {
        return this.resolvers;
    }

    /**
     * Registers a qualifier annotation used to mark and resolve qualified beans.
     *
     * @param qualifier the qualifier annotation class; must not be {@code null}
     * @return this builder for chaining
     */
    @Override
    public IInjectionContextBuilder withQualifier(IClass<? extends Annotation> qualifier) {
        Objects.requireNonNull(qualifier, "Qualifier cannot be null");
        this.manualQualifiers.put(qualifier.getName(), qualifier);
        log.debug("Added qualifier: {}", qualifier);
        return this;
    }

    @Override
    protected IInjectionContext doBuild() throws DslException {
        Map<String, IBeanProviderBuilder> allBeanProviders = getAllBeanProviders();
        Map<String, IPropertyProviderBuilder> allPropertyProviders = getAllPropertyProviders();
        if (allBeanProviders.isEmpty() && allPropertyProviders.isEmpty()) {
            log.error("No BeanProvider or PropertyProvider defined. Throwing DslException.");
            throw new DslException("At least one BeanProvider and PropertyProvider must be provided");
        }
        Set<IClass<? extends Annotation>> allQualifiers = new HashSet<>(this.qualifierCollector.build().values());
        InjectionContextBuilder.setBuiltInResolvers(this.resolvers, allQualifiers, this.autoDetect.booleanValue());
        log.debug("Set built-in resolvers");
        allQualifiers.forEach(qualifier -> {
            this.resolvers.withResolver(qualifier, new SingletonElementResolver(Set.of()));
            log.debug("Added resolver for qualifier: {}", qualifier);
        });
        IInjectableElementResolver builtResolvers = this.resolvers.build();
        log.debug("Built IInjectableElementResolver");
        List<IInjectionChildContextFactory<? extends IInjectionContext>> allChildContextFactories =
                new ArrayList<>(this.childContextFactoryCollector.build().values());
        IInjectionContext built = InjectionContext.master(
                builtResolvers,
                this.buildBeanProviders(),
                this.buildPropertyProviders(),
                allChildContextFactories);
        log.debug("Constructed IInjectionContext master instance");
        this.notifyObserver(built);
        return built;
    }

    private void notifyObserver(IInjectionContext built) {
        this.observers.parallelStream().forEach(observer -> {
            observer.handle(built);
            log.debug("Notified observer: {}", observer);
        });
    }

    /**
     * Registers the framework's built-in element resolvers (singleton, inject, prototype, and —
     * unless auto-detection is enabled — property, null, and fixed) on the given resolver builder.
     *
     * @param resolvers  the resolver builder to populate
     * @param qualifiers the known qualifier annotations passed to the bean resolvers
     * @param autoDetect when {@code true}, the property/null/fixed resolvers are left to auto-detection
     */
    @SuppressWarnings("unchecked")
    public static void setBuiltInResolvers(IInjectableElementResolverBuilder resolvers,
            Set<IClass<? extends Annotation>> qualifiers, boolean autoDetect) {

        resolvers.withResolver((IClass<? extends Annotation>) IClass.getClass(Singleton.class), new SingletonElementResolver(qualifiers))
                .withResolver((IClass<? extends Annotation>) IClass.getClass(Inject.class), new SingletonElementResolver(qualifiers))
                .withResolver((IClass<? extends Annotation>) IClass.getClass(Prototype.class), new PrototypeElementResolver(qualifiers));
        if (!autoDetect) {
            resolvers.withResolver((IClass<? extends Annotation>) IClass.getClass(Property.class), new PropertyElementResolver())
                    .withResolver((IClass<? extends Annotation>) IClass.getClass(Null.class), new NullElementResolver())
                    .withResolver((IClass<? extends Annotation>) IClass.getClass(Fixed.class), new FixedElementResolver());
        }
    }

    @Override
    protected void doAutoDetection() throws DslException {
        log.trace("doAutoDetection() — no-op, scanning deferred to doAutoDetectionWithDependency()");
    }

    /** {@return the packages configured for classpath auto-detection} */
    @Override
    public String[] getPackages() {
        return this.packages.toArray(new String[0]);
    }

    /**
     * Registers a build observer. If the context is already built, the observer is notified immediately.
     *
     * @param observer the build observer; must not be {@code null}
     * @return this builder for chaining
     */
    @Override
    public IInjectionContextBuilder observer(IBuilderObserver<IInjectionContextBuilder, IInjectionContext> observer) {
        Objects.requireNonNull(observer, "Observer cannot be null");
        this.observers.add(observer);
        log.debug("Added observer: {}", observer);
        // If context is already built, notify the observer immediately
        if (this.built != null) {
            observer.handle(this.built);
            log.debug("Context already built, immediately notified observer: {}", observer);
        }
        return this;
    }

    @Override
    protected void doAutoDetectionWithDependency(Object dependency) throws DslException {
        if (dependency instanceof IReflection reflection) {
            InjectionAutoDetector.detect(this, reflection);
        }
    }

    /**
     * Captures the {@link IReflectionBuilder} dependency when supplied, then delegates to the superclass.
     *
     * @param dependency the dependency builder being provided
     * @return this builder for chaining
     * @throws DslException if the dependency cannot be accepted
     */
    @Override
    public IInjectionContextBuilder provide(IObservableBuilder<?, ?> dependency) throws DslException {
        if (dependency instanceof IReflectionBuilder) {
            this.reflectionBuilderRef = dependency;
        }
        return super.provide(dependency);
    }

    @Override
    protected void doPreBuildWithDependency(Object dependency) {
        // IReflection already handled in doAutoDetectionWithDependency
    }

    @Override
    protected void doPostBuildWithDependency(Object dependency) {
        // No post-build behavior needed
    }
}
