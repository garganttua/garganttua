package com.garganttua.core.cache.dsl;

import com.garganttua.core.cache.ICacheFactory;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.dsl.IAutomaticBuilder;
import com.garganttua.core.dsl.IPackageableBuilder;
import com.garganttua.core.reflection.IClass;

/**
 * Builds an {@link ICacheManager}: the factories of the in-memory implementations are always there, others are
 * registered with {@link #withFactory} or found by scanning packages for {@code @CacheFactory} classes.
 */
public interface ICacheManagerBuilder
        extends IAutomaticBuilder<ICacheManagerBuilder, ICacheManager>,
        IPackageableBuilder<ICacheManagerBuilder, ICacheManager> {

    /** Registers the factory of the {@code type} implementation, over any found by scanning. */
    ICacheManagerBuilder withFactory(IClass<?> type, ICacheFactory factory);
}
