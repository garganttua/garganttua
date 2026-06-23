package com.garganttua.core.aot.reflection;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IConstructor;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;

/**
 * On-demand synthesis of {@code AOT*} members from the live {@link Class} for
 * shallow {@link AOTClass} descriptors.
 *
 * <p>Shallow descriptors (produced by {@code CoreInfrastructureSeed.synthesize}
 * for types not run through the annotation processor) carry only identity +
 * flags + a synthesised no-arg constructor. When framework wiring requests a
 * method, field or non-trivial constructor, this collaborator loads the live
 * {@code Class<?>} via {@code Class.forName} and synthesises the {@code AOT*}
 * member on demand. Pure-AOT JVM mode works since {@code Class.forName} +
 * reflection are available; native-image consumers need the member in
 * reflect-config (handled by the {@code GarganttuaAotFeature} for seeded types).
 *
 * <p>One instance is owned per {@link AOTClass}. The bulk-array fallbacks
 * memoise their result and {@link #loadLiveClass()} memoises the resolved
 * {@code Class<?>}, so repeated calls during auto-detection no longer re-run
 * {@code Class.forName} + re-allocate {@code N×AOTMethod} per invocation. The
 * single-member fallbacks stay non-cached: each (name, parameter-types) tuple
 * would need its own cache key and the lookup pattern is one-shot per name.
 *
 * <p>Thread-safe: {@code loadLiveClass} double-checks under {@code synchronized};
 * the cache fields are {@code volatile}.
 */
// ReturnEmptyCollectionRatherThanNull / NullAssignment: null is the documented "no fallback
//   available" sentinel throughout this collaborator — callers in AOTClass branch on it to keep
//   their own empty backing arrays, which is semantically distinct from an empty fallback result.
// UnusedAssignment: false positive — the cache fields ARE read on the fast path of every method;
//   PMD's intra-method dataflow does not see the cross-call memoisation.
@SuppressWarnings({"PMD.ReturnEmptyCollectionRatherThanNull", "PMD.NullAssignment",
        "PMD.UnusedAssignment"})
// PZLA_PREFER_ZERO_LENGTH_ARRAYS: null is the documented "no fallback available" sentinel;
// callers in AOTClass branch on it to retain their own backing arrays — a zero-length array
// would be indistinguishable from a successful empty-member synthesis.
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
    justification = "null is the meaningful 'no fallback available' sentinel; callers in AOTClass "
        + "distinguish it from an empty fallback result.")
final class AOTLiveClassFallback {

    private final String name;

    private volatile Class<?> liveClassCache;
    private volatile boolean liveClassResolved;
    private volatile java.util.List<IMethod> cachedFallbackMethods;
    private volatile java.util.List<IField> cachedFallbackFields;
    private volatile java.util.List<IConstructor<?>> cachedFallbackConstructors;

    AOTLiveClassFallback(String name) {
        this.name = name;
    }

    // UseProperClassLoader: the context loader IS preferred; this class's own loader is only the
    // documented last-resort fallback when no context loader is set.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private Class<?> loadLiveClass() {
        if (liveClassResolved) return liveClassCache;
        synchronized (this) {
            if (liveClassResolved) return liveClassCache;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = AOTLiveClassFallback.class.getClassLoader();
                liveClassCache = Class.forName(name, false, cl);
            } catch (ClassNotFoundException | LinkageError ignored) {
                liveClassCache = null;
            }
            liveClassResolved = true;
            return liveClassCache;
        }
    }

    private Class<?>[] toRawClasses(IClass<?>... parameterTypes) {
        if (parameterTypes == null) return new Class<?>[0];
        Class<?>[] out = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            IClass<?> p = parameterTypes[i];
            if (p == null) return null;
            java.lang.reflect.Type t = p.getType();
            if (!(t instanceof Class<?> c)) return null;
            out[i] = c;
        }
        return out;
    }

    AOTMethod declaredMethod(String methodName, IClass<?>... parameterTypes) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        Class<?>[] raw = toRawClasses(parameterTypes);
        if (raw == null) return null;
        try {
            return AOTMethod.synthesizeFrom(live.getDeclaredMethod(methodName, raw));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    AOTMethod publicMethod(String methodName, IClass<?>... parameterTypes) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        Class<?>[] raw = toRawClasses(parameterTypes);
        if (raw == null) return null;
        try {
            return AOTMethod.synthesizeFrom(live.getMethod(methodName, raw));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    AOTField declaredField(String fieldName) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        try {
            return AOTField.synthesizeFrom(live.getDeclaredField(fieldName));
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    AOTField publicField(String fieldName) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        try {
            return AOTField.synthesizeFrom(live.getField(fieldName));
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    AOTConstructor<?> declaredConstructor(IClass<?>... parameterTypes) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        Class<?>[] raw = toRawClasses(parameterTypes);
        if (raw == null) return null;
        try {
            return AOTConstructor.synthesizeFrom(live.getDeclaredConstructor(raw));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    AOTConstructor<?> publicConstructor(IClass<?>... parameterTypes) {
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        Class<?>[] raw = toRawClasses(parameterTypes);
        if (raw == null) return null;
        try {
            return AOTConstructor.synthesizeFrom(live.getConstructor(raw));
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    IMethod[] declaredMethods() {
        java.util.List<IMethod> cached = cachedFallbackMethods;
        if (cached != null) return cached.isEmpty() ? null : cached.toArray(new IMethod[0]);
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        try {
            java.util.List<IMethod> out = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : live.getDeclaredMethods()) {
                out.add(AOTMethod.synthesizeFrom(m));
            }
            cachedFallbackMethods = out;
            return out.toArray(new IMethod[0]);
        } catch (RuntimeException | LinkageError ignored) {
            cachedFallbackMethods = java.util.List.of();
            return null;
        }
    }

    IField[] declaredFields() {
        java.util.List<IField> cached = cachedFallbackFields;
        if (cached != null) return cached.isEmpty() ? null : cached.toArray(new IField[0]);
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        try {
            java.util.List<IField> out = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : live.getDeclaredFields()) {
                out.add(AOTField.synthesizeFrom(f));
            }
            cachedFallbackFields = out;
            return out.toArray(new IField[0]);
        } catch (RuntimeException | LinkageError ignored) {
            cachedFallbackFields = java.util.List.of();
            return null;
        }
    }

    IConstructor<?>[] declaredConstructors() {
        java.util.List<IConstructor<?>> cached = cachedFallbackConstructors;
        if (cached != null) return cached.isEmpty() ? null : cached.toArray(new IConstructor<?>[0]);
        Class<?> live = loadLiveClass();
        if (live == null) return null;
        try {
            java.util.List<IConstructor<?>> out = new java.util.ArrayList<>();
            for (java.lang.reflect.Constructor<?> c : live.getDeclaredConstructors()) {
                out.add(AOTConstructor.synthesizeFrom(c));
            }
            cachedFallbackConstructors = out;
            return out.toArray(new IConstructor<?>[0]);
        } catch (RuntimeException | LinkageError ignored) {
            cachedFallbackConstructors = java.util.List.of();
            return null;
        }
    }
}
