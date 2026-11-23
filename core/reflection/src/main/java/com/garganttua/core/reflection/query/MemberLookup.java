package com.garganttua.core.reflection.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.List;

import com.garganttua.core.cache.StructureCache;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;

/**
 * Class-hierarchy member lookup helpers used by {@link ObjectQuery}.
 *
 * <p>Resolves fields and methods by name walking declared members, implemented
 * interfaces (needed for anonymous classes) and superclasses. Stateless and
 * thread-safe; extracted from {@code ObjectQuery} to keep that type focused on
 * address/path resolution.
 *
 * <h2>Memoisation</h2>
 * <p>Every lookup here is a pure function of {@code (class, member name)}, and a loaded class's
 * members never change — so each answer is computed once and kept for the life of the JVM. It
 * matters because these are the innermost calls of the per-request resolution path: without a
 * memo the hierarchy is re-walked, and the provider's member arrays re-materialised, on every
 * single request, re-deriving what the AOT index already settled at compile time.
 *
 * <p>The maps hold their keys strongly. That is the same contract as {@code RuntimeClass}' own
 * per-{@code Class} instance cache, and the framework never unloads classes; the number of
 * distinct {@code (class, name)} pairs is bounded by the application's own code.
 */
final class MemberLookup {

    private static final StructureCache<MemberKey, Optional<IField>> FIELDS = StructureCache.named("reflection.member-fields");
    private static final StructureCache<MemberKey, Optional<IMethod>> METHOD = StructureCache.named("reflection.member-method");
    private static final StructureCache<MemberKey, List<IMethod>> METHODS = StructureCache.named("reflection.member-methods");

    /** Memo key: the owning class descriptor and the member name looked up on it. */
    private record MemberKey(IClass<?> owner, String name) {
    }

    private MemberLookup() {
    }

    static IField getField(IClass<?> clazz, String name) {
        return FIELDS.get(new MemberKey(clazz, name),
                key -> Optional.ofNullable(findField(key.owner(), key.name()))).orElse(null);
    }

    private static IField findField(IClass<?> clazz, String name) {
        for (IField f : clazz.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        IClass<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            return findField(superclass, name);
        }
        return null;
    }

    static IMethod getMethod(IClass<?> clazz, String name) {
        return METHOD.get(new MemberKey(clazz, name),
                key -> Optional.ofNullable(findMethod(key.owner(), key.name()))).orElse(null);
    }

    private static IMethod findMethod(IClass<?> clazz, String name) {
        for (IMethod m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        // Traverse interfaces (needed for anonymous classes implementing interfaces)
        for (IClass<?> iface : clazz.getInterfaces()) {
            IMethod m = findMethod(iface, name);
            if (m != null) {
                return m;
            }
        }
        IClass<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            return findMethod(superclass, name);
        }
        return null;
    }

    /**
     * {@return every overload named {@code name} visible on {@code clazz}, most-derived first}
     * The list is immutable: it is shared between callers by the memo above.
     */
    static List<IMethod> getMethods(IClass<?> clazz, String name) {
        return METHODS.get(new MemberKey(clazz, name),
                key -> List.copyOf(findMethods(key.owner(), key.name())));
    }

    private static List<IMethod> findMethods(IClass<?> clazz, String name) {
        // One map for the whole hierarchy walk: an overload declared closer to `clazz` wins, and a
        // signature already seen is not re-added. Collecting in place avoids the previous shape,
        // which built a fresh list and signature set at EVERY level and then merged them upward.
        Map<String, IMethod> bySignature = new LinkedHashMap<>();
        collectMethods(clazz, name, bySignature, new HashSet<>());
        return new ArrayList<>(bySignature.values());
    }

    private static void collectMethods(IClass<?> clazz, String name, Map<String, IMethod> bySignature,
            Set<IClass<?>> visited) {
        if (clazz == null || !visited.add(clazz)) {
            return;
        }
        for (IMethod m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                bySignature.putIfAbsent(buildMethodSignature(m), m);
            }
        }
        // Traverse interfaces (needed for anonymous classes implementing interfaces)
        for (IClass<?> iface : clazz.getInterfaces()) {
            collectMethods(iface, name, bySignature, visited);
        }
        collectMethods(clazz.getSuperclass(), name, bySignature, visited);
    }

    static String buildMethodSignature(IMethod method) {
        StringBuilder signature = new StringBuilder(method.getName());
        signature.append("(");
        IClass<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                signature.append(",");
            }
            signature.append(paramTypes[i].getName());
        }
        signature.append(")");
        return signature.toString();
    }
}
