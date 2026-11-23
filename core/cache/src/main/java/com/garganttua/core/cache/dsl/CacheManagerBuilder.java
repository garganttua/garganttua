package com.garganttua.core.cache.dsl;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.garganttua.core.cache.CacheManager;
import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.cache.LocalCacheFactory;
import com.garganttua.core.cache.NoCache;
import com.garganttua.core.cache.NoCacheFactory;
import com.garganttua.core.cache.annotations.CacheFactory;
import com.garganttua.core.dsl.AbstractAutomaticBuilder;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Builds a {@link CacheManager}. The in-memory implementations ({@link LocalCache}, {@link NoCache}) are always
 * served; others come from {@link #withFactory} — which wins — or from the {@code @CacheFactory} classes of the
 * scanned packages, when auto-detection is on.
 *
 * <pre>{@code
 * ICacheManager caches = CacheManagerBuilder.builder()
 *         .withFactory(IClass.getClass(RedisNearCache.class), new RedisNearCacheFactory(jedisPool))
 *         .build();
 * }</pre>
 */
@Reflected
public final class CacheManagerBuilder extends AbstractAutomaticBuilder<ICacheManagerBuilder, ICacheManager>
        implements ICacheManagerBuilder {

    private final Set<String> packages = new LinkedHashSet<>();
    private final Map<IClass<?>, ICacheFactory> manual = new HashMap<>();
    private final Map<IClass<?>, ICacheFactory> detected = new HashMap<>();

    private CacheManagerBuilder() {
    }

    /** {@return a new builder} */
    public static ICacheManagerBuilder builder() {
        return new CacheManagerBuilder();
    }

    @Override
    public ICacheManagerBuilder withFactory(IClass<?> type, ICacheFactory factory) {
        this.manual.put(Objects.requireNonNull(type, "Cache type cannot be null"),
                Objects.requireNonNull(factory, "Cache factory cannot be null"));
        return this;
    }

    @Override
    public ICacheManagerBuilder withPackage(String packageName) {
        this.packages.add(Objects.requireNonNull(packageName, "Package name cannot be null"));
        return this;
    }

    @Override
    public ICacheManagerBuilder withPackages(String... packageNames) {
        for (String packageName : Objects.requireNonNull(packageNames, "Package names cannot be null")) {
            withPackage(packageName);
        }
        return this;
    }

    @Override
    public String[] getPackages() {
        return this.packages.toArray(new String[0]);
    }

    @Override
    protected ICacheManager doBuild() throws DslException {
        Map<IClass<?>, ICacheFactory> factories = new HashMap<>();
        factories.put(IClass.getClass(LocalCache.class), new LocalCacheFactory());
        factories.put(IClass.getClass(NoCache.class), new NoCacheFactory());
        factories.putAll(this.detected);
        factories.putAll(this.manual);
        return new CacheManager(factories);
    }

    @Override
    protected void doAutoDetection() throws DslException {
        IReflection reflection = IClass.getReflection();
        IClass<CacheFactory> annotation = IClass.getClass(CacheFactory.class);
        for (String packageName : this.packages) {
            for (IClass<?> factoryClass : reflection.getClassesWithAnnotation(packageName, annotation)) {
                IClass<?> type = IClass.getClass(factoryClass.getAnnotation(annotation).type());
                try {
                    this.detected.putIfAbsent(type, (ICacheFactory) reflection.newInstance(factoryClass));
                } catch (ReflectionException e) {
                    throw new DslException("Failed to instantiate cache factory " + factoryClass.getName(), e);
                }
            }
        }
    }
}
