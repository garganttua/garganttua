package com.garganttua.core.cache.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.garganttua.core.cache.ICache;
import com.garganttua.core.reflection.annotations.Indexed;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Marks an {@code ICacheFactory} and names the implementation it creates, so that a {@code CacheManagerBuilder}
 * scanning the factory's package registers it — indexed, hence found in a native image too.
 */
@Indexed
@Reflected
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheFactory {

	/** {@return the cache implementation this factory creates} */
	@SuppressWarnings("rawtypes")
	Class<? extends ICache> type();
}
