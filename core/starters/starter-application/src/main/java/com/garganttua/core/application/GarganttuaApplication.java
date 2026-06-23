package com.garganttua.core.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.core.bootstrap.dsl.Bootstrap;
import com.garganttua.core.bootstrap.dsl.IBuiltRegistry;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;

/**
 * Neutral, core-level application entry point.
 *
 * <p>One call boots garganttua-core's {@code Bootstrap} and returns the
 * {@link IBuiltRegistry} of every module it assembled. The runner is
 * <b>engine-neutral</b>: it has no compile-time dependency on garganttua-api or
 * garganttua-events. Every engine (api, events, …) is discovered at runtime
 * through its {@code IBootstrapBuilderFactory} SPI on the classpath, which is the
 * whole point of staying neutral — pulling a different engine JAR onto the
 * classpath changes what boots without touching this module.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * public final class MyApp {
 *     public static void main(String[] args) {
 *         IBuiltRegistry app = GarganttuaApplication.run(MyApp.class, args);
 *         // Pull whichever engine the classpath provides:
 *         IApi api = GarganttuaApplication.get(app, IApi.class).orElseThrow();
 *         IEvents events = GarganttuaApplication.get(app, IEvents.class).orElseThrow();
 *     }
 * }
 * }</pre>
 *
 * <p>{@code IApi} / {@code IEvents} are referenced in prose only — they live in
 * the api / events libraries and are pulled out of the returned registry by
 * interface (the registry matches by assignability). Use {@link #runAndWait} to
 * block the calling thread until JVM shutdown.</p>
 *
 * <p>Packages to scan default to the {@code source} class's package; override by
 * setting the {@code garganttua.packages} system property to a comma-separated
 * list. A JVM shutdown hook stops every {@link ILifecycle} built object in
 * reverse build order.</p>
 *
 * @since 3.0.0-ALPHA04
 */
public final class GarganttuaApplication {

    private static final Logger LOG = Logger.getLogger(GarganttuaApplication.class);

    /** System property holding a comma-separated package list to scan. */
    private static final String PACKAGES_PROPERTY = "garganttua.packages";

    private GarganttuaApplication() {
    }

    /**
     * Boots the Bootstrap and returns the registry of bootstrapped modules
     * without blocking the calling thread.
     *
     * @param source the application's main class; its package is scanned by default
     * @param args   process arguments (currently unused, reserved for parity)
     * @return the {@link IBuiltRegistry} of every module assembled by the boot
     * @throws GarganttuaApplicationException if the Bootstrap fails to assemble
     */
    public static IBuiltRegistry run(Class<?> source, String... args) {
        String[] packages = resolvePackages(source);
        try {
            IBuiltRegistry registry = Bootstrap.builder()
                    .autoDetect(true)
                    .withApplicationName(source.getSimpleName())
                    .withPackages(packages)
                    .load()
                    .build();
            installShutdownHook(registry);
            return registry;
        } catch (DslException e) {
            throw new GarganttuaApplicationException(
                    "GarganttuaApplication failed to boot " + source.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Same as {@link #run} but blocks the calling thread until JVM shutdown.
     *
     * @param source the application's main class
     * @param args   process arguments
     */
    public static void runAndWait(Class<?> source, String... args) {
        run(source, args);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Convenience lookup over a registry by interface or concrete type.
     *
     * @param <T>      the requested type
     * @param registry the registry returned by {@link #run}
     * @param type     the type to match (interface or concrete class)
     * @return an {@link Optional} holding the first assignable built object
     */
    public static <T> Optional<T> get(IBuiltRegistry registry, Class<T> type) {
        return registry.request(IClass.getClass(type));
    }

    /** Resolves scan packages: {@code garganttua.packages} property, else the source package. */
    private static String[] resolvePackages(Class<?> source) {
        String configured = System.getProperty(PACKAGES_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return configured.split(",");
        }
        return new String[] { source.getPackageName() };
    }

    /** Registers a JVM shutdown hook that stops lifecycle objects in reverse build order. */
    private static void installShutdownHook(IBuiltRegistry registry) {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> stopAll(registry), "garganttua-application-shutdown"));
    }

    /** Stops every {@link ILifecycle} built object in reverse build order, isolating failures. */
    private static void stopAll(IBuiltRegistry registry) {
        List<Object> built = new ArrayList<>(registry.toList());
        java.util.Collections.reverse(built);
        for (Object obj : built) {
            if (obj instanceof ILifecycle lifecycle) {
                try {
                    lifecycle.onStop();
                } catch (Exception e) {
                    LOG.warn("Failed to stop {}: {}", obj.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }
}
