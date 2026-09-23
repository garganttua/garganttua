package com.garganttua.core.aot.reflection;

import java.lang.annotation.Annotation;

/**
 * Member-level annotation recovery for the AOT descriptors, called from
 * <em>generated</em> source.
 *
 * <p>{@code AOTClassSourceGenerator} sources a type's annotations from the live
 * class literal ({@code MyClass.class.getAnnotations()}), which is AOT-friendly:
 * the literal is a constant-pool entry and the call runs once, when the
 * descriptor's {@code INSTANCE} is initialised — at image-build time under
 * native-image. The field / method / constructor generators had no equivalent
 * and hardcoded {@code new Annotation[0]}, so <strong>every member annotation
 * vanished as soon as a descriptor existed</strong>: {@code @EntityId},
 * {@code @Inject}, mapping rules, security markers — all invisible under AOT
 * while the very same annotation on the enclosing type survived.</p>
 *
 * <p>The member equivalent cannot be inlined in the generated {@code super(...)}
 * call, because {@link Class#getDeclaredField(String)} and friends throw checked
 * exceptions. Hence these helpers: same "resolve from the live class" idea,
 * wrapped so that it is an expression.</p>
 *
 * <p><strong>A descriptor must never break a class load.</strong> Any failure to
 * resolve the member — erasure mismatch, a signature that changed since the
 * descriptor was generated, a member missing from the native-image reflection
 * configuration — yields an empty array, exactly the pre-existing behaviour,
 * never an exception.</p>
 *
 * <p>Distinct from {@code AOTMethodAnnotations}, which repairs the empty array
 * <em>at runtime</em> by merging in the live {@link java.lang.reflect.Method}:
 * that path only works for methods, only on a JVM where the live class is
 * reachable, and pays the lookup on every call. This one bakes the real
 * annotations into the descriptor when it is built.</p>
 *
 * @since 3.0.0-ALPHA23
 */
public final class AOTAnnotations {

    private AOTAnnotations() {
    }

    /**
     * Returns the declared annotations of one field, or an empty array when the
     * field cannot be resolved.
     *
     * @param owner the class declaring the field (may be {@code null})
     * @param fieldName the field's simple name (may be {@code null})
     * @return the field's declared annotations, never {@code null}
     */
    public static Annotation[] ofField(Class<?> owner, String fieldName) {
        if (owner == null || fieldName == null) {
            return new Annotation[0];
        }
        try {
            return owner.getDeclaredField(fieldName).getDeclaredAnnotations();
        } catch (NoSuchFieldException | RuntimeException | LinkageError e) {
            return new Annotation[0];
        }
    }

    /**
     * Returns the declared annotations of one method, or an empty array when the
     * method cannot be resolved.
     *
     * @param owner the class declaring the method (may be {@code null})
     * @param methodName the method's simple name (may be {@code null})
     * @param parameterTypes the method's erased parameter types
     * @return the method's declared annotations, never {@code null}
     */
    public static Annotation[] ofMethod(Class<?> owner, String methodName, Class<?>... parameterTypes) {
        if (owner == null || methodName == null) {
            return new Annotation[0];
        }
        try {
            return owner.getDeclaredMethod(methodName, parameterTypes).getDeclaredAnnotations();
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return new Annotation[0];
        }
    }

    /**
     * Returns the declared annotations of one constructor, or an empty array
     * when the constructor cannot be resolved.
     *
     * @param owner the class declaring the constructor (may be {@code null})
     * @param parameterTypes the constructor's erased parameter types
     * @return the constructor's declared annotations, never {@code null}
     */
    public static Annotation[] ofConstructor(Class<?> owner, Class<?>... parameterTypes) {
        if (owner == null) {
            return new Annotation[0];
        }
        try {
            return owner.getDeclaredConstructor(parameterTypes).getDeclaredAnnotations();
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            return new Annotation[0];
        }
    }
}
