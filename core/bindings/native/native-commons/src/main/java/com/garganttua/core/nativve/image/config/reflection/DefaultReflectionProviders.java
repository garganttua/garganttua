package com.garganttua.core.nativve.image.config.reflection;

import com.garganttua.core.reflection.IReflectionProvider;

/**
 * Lazily resolves the default {@link IReflectionProvider} (the runtime reflection
 * provider) via reflection, so that {@code garganttua-runtime-reflection} stays an
 * optional runtime dependency of this binding. Caches the resolved instance.
 *
 * <p>Extracted from the serialization DTOs so they remain plain, single-threaded
 * value objects (no shared {@code volatile} state of their own).</p>
 */
final class DefaultReflectionProviders {

    private static final String RUNTIME_PROVIDER_CLASS =
            "com.garganttua.core.reflection.runtime.RuntimeReflectionProvider";

    private static volatile IReflectionProvider cached;

    private DefaultReflectionProviders() {
    }

    /**
     * Resolves the default runtime reflection provider, instantiating and caching it
     * on first use.
     *
     * @return the shared default {@link IReflectionProvider}
     */
    static IReflectionProvider get() {
        IReflectionProvider local = cached;
        if (local != null) {
            return local;
        }
        try {
            Class<?> providerClass = Class.forName(RUNTIME_PROVIDER_CLASS);
            local = (IReflectionProvider) providerClass.getDeclaredConstructor().newInstance();
            cached = local;
            return local;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "No IReflectionProvider available. Ensure garganttua-runtime-reflection is on the classpath.", e);
        }
    }
}
