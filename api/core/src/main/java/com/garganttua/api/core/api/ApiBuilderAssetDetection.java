package com.garganttua.api.core.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.protocol.IProtocol;
import com.garganttua.api.commons.protocol.Protocol;
import com.garganttua.api.commons.security.authorization.AuthorizationProtocol;
import com.garganttua.api.commons.security.authorization.IAuthorizationProtocol;
import com.garganttua.api.commons.serialization.ISerializer;
import com.garganttua.api.commons.serialization.Serializer;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;

/**
 * Classpath asset auto-detection for {@link ApiBuilder}: scans the configured packages for
 * {@code @Serializer}, {@code @Protocol} and {@code @AuthorizationProtocol} types and registers an
 * instance of each onto the builder's corresponding pool. Also hosts the reflective instantiation
 * helpers (with their type-mismatch / missing-ctor diagnostics). Extracted from {@code ApiBuilder}
 * to keep that wide builder under the file-size gate; behaviour is identical.
 */
final class ApiBuilderAssetDetection {

    private static final Logger log = Logger.getLogger(ApiBuilderAssetDetection.class);

    private ApiBuilderAssetDetection() {
    }

    /** Runs all three asset scans (serializers, protocols, authorization protocols). */
    static void autoDetectAll(ApiBuilder builder, Set<String> scanSurface) {
        autoDetectSerializers(builder, scanSurface);
        autoDetectProtocols(builder, scanSurface);
        autoDetectAuthorizationProtocols(builder, scanSurface);
    }

    private static void autoDetectSerializers(ApiBuilder builder, Set<String> scanSurface) {
        autoDetect(scanSurface, "@Serializer", IClass.getClass(Serializer.class),
                builder.serializers, ISerializer::getClass,
                ApiBuilderAssetDetection::instantiateSerializer);
    }

    private static void autoDetectProtocols(ApiBuilder builder, Set<String> scanSurface) {
        autoDetect(scanSurface, "@Protocol", IClass.getClass(Protocol.class),
                builder.protocols, p -> p.getClass(),
                ApiBuilderAssetDetection::instantiateProtocol);
    }

    private static void autoDetectAuthorizationProtocols(ApiBuilder builder, Set<String> scanSurface) {
        autoDetect(scanSurface, "@AuthorizationProtocol", IClass.getClass(AuthorizationProtocol.class),
                builder.authorizationProtocols, IAuthorizationProtocol::getClass,
                ApiBuilderAssetDetection::instantiateAuthorizationProtocol);
    }

    /**
     * Generic scan-and-register: instantiates every annotated class across the scan surface, deduping
     * by concrete class against the pool's already-registered instances. No-op without packages or a
     * reflection scanner (e.g. native image without pre-computed metadata).
     */
    private static <T> void autoDetect(Set<String> scanSurface, String label,
            IClass<? extends java.lang.annotation.Annotation> annotation, List<T> pool,
            Function<T, Class<?>> classOf, Function<IClass<?>, T> instantiate) {
        if (scanSurface.isEmpty()) {
            return;
        }
        IReflection reflection;
        try {
            reflection = IClass.getReflection();
        } catch (Exception e) {
            log.warn("No IReflection available for {} auto-detection: {}", label, e.getMessage());
            return;
        }

        Set<Class<?>> seen = new HashSet<>();
        for (T registered : pool) {
            seen.add(classOf.apply(registered));
        }

        int discovered = 0;
        for (String pkg : scanSurface) {
            List<IClass<?>> found = reflection.getClassesWithAnnotation(pkg, annotation);
            for (IClass<?> clazz : found) {
                T instance = instantiate.apply(clazz);
                if (!seen.add(classOf.apply(instance))) {
                    continue;
                }
                pool.add(instance);
                discovered++;
            }
        }
        if (discovered > 0) {
            log.debug("Auto-detected {} {} class(es) across {} package(s)",
                    discovered, label, scanSurface.size());
        }
    }

    static IAuthorizationProtocol instantiateAuthorizationProtocol(IClass<?> clazz) {
        Object instance = newInstance(clazz, "@AuthorizationProtocol");
        if (!(instance instanceof IAuthorizationProtocol protocol)) {
            throw new ApiException(
                    "Class '" + clazz.getName() + "' is annotated with @AuthorizationProtocol "
                    + "but does not implement " + IAuthorizationProtocol.class.getName());
        }
        return protocol;
    }

    static IProtocol<?, ?> instantiateProtocol(IClass<?> clazz) {
        Object instance = newInstance(clazz, "@Protocol");
        if (!(instance instanceof IProtocol<?, ?> protocol)) {
            throw new ApiException(
                    "Class '" + clazz.getName() + "' is annotated with @Protocol "
                    + "but does not implement " + IProtocol.class.getName());
        }
        return protocol;
    }

    static ISerializer instantiateSerializer(IClass<?> clazz) {
        Object instance = newInstance(clazz, "@Serializer");
        if (!(instance instanceof ISerializer serializer)) {
            throw new ApiException(
                    "Class '" + clazz.getName() + "' is annotated with @Serializer "
                    + "but does not implement " + ISerializer.class.getName());
        }
        return serializer;
    }

    private static Object newInstance(IClass<?> clazz, String marker) {
        try {
            return clazz.getConstructor().newInstance();
        } catch (Exception e) {
            throw new ApiException(
                    "Failed to instantiate " + marker + " class '" + clazz.getName()
                    + "'. A public no-arg constructor is required.", e);
        }
    }
}
