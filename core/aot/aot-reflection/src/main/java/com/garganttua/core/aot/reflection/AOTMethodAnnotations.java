package com.garganttua.core.aot.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Method-level annotation recovery for {@link AOTMethod}.
 *
 * <p>The AOT source generator emits {@code new Annotation[0]} for the method
 * annotation descriptor — exactly as it does for parameter annotations — so the
 * actual annotations must be recovered from the live {@link Method}. Without
 * this, RUNTIME-retained annotations such as {@code @Expression} are invisible
 * under AOT, silently breaking the {@code @Expression} SPI (FQN/registrar path)
 * and the security/mapper binders.</p>
 *
 * <p>All lookups degrade to the descriptor-only result when the live method is
 * unreachable (e.g. native-image, where the supplier yields {@code null}),
 * preserving prior native behaviour.</p>
 */
final class AOTMethodAnnotations {

    private AOTMethodAnnotations() {
    }

    /**
     * Returns the annotation of the given type from the descriptor, falling back
     * to the live method, or {@code null} when absent / unreachable.
     *
     * @param resolveLive resolves the live method; may throw when unreachable
     */
    @SuppressWarnings("unchecked")
    static <T extends Annotation> T get(Annotation[] descriptor, Supplier<Method> resolveLive,
            String annotationClassName) {
        for (Annotation a : descriptor) {
            if (a.annotationType().getName().equals(annotationClassName)) {
                return (T) a;
            }
        }
        Method method = liveOrNull(resolveLive);
        if (method != null) {
            for (Annotation a : method.getDeclaredAnnotations()) {
                if (a.annotationType().getName().equals(annotationClassName)) {
                    return (T) a;
                }
            }
        }
        return null;
    }

    /**
     * Merges descriptor annotations with the live-method annotations (descriptor
     * wins on type collisions); returns a clone of the descriptor when the live
     * method is unreachable.
     *
     * @param resolveLive resolves the live method; may throw when unreachable
     */
    static Annotation[] merge(Annotation[] descriptor, Supplier<Method> resolveLive) {
        Method method = liveOrNull(resolveLive);
        if (method == null) {
            return descriptor.clone();
        }
        Map<String, Annotation> merged = new LinkedHashMap<>();
        for (Annotation a : descriptor) {
            merged.put(a.annotationType().getName(), a);
        }
        for (Annotation a : method.getDeclaredAnnotations()) {
            merged.putIfAbsent(a.annotationType().getName(), a);
        }
        return merged.values().toArray(new Annotation[0]);
    }

    /** Resolves the live method, swallowing the unreachable (native) failure as {@code null}. */
    private static Method liveOrNull(Supplier<Method> resolveLive) {
        try {
            return resolveLive.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
