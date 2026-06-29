package com.garganttua.core.aot.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;

/**
 * Regression oracle: a generated {@link AOTMethod} descriptor stores no
 * method-level annotations (the source generator emits {@code new Annotation[0]}),
 * so RUNTIME-retained <em>method</em> annotations must be recovered from the live
 * {@link java.lang.reflect.Method} — mirroring how parameter annotations already
 * are. Before the fix, {@link AOTMethod#getAnnotation(IClass)} returned
 * {@code null} for every method annotation under AOT, even {@code @Retention(RUNTIME)}
 * ones such as {@code @Expression}, silently breaking the {@code @Expression} SPI
 * (FQN/registrar path), security and mapper binders.
 */
class AOTMethodAnnotationsTest {

    @BeforeEach
    void setUp() {
        TestReflectionSupport.installReflection();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface TestMethodAnno {
        String value();
    }

    @TestMethodAnno("v")
    @SuppressWarnings("unused")
    public static String fn(String s) {
        return s;
    }

    @SuppressWarnings("unused")
    public static String plain(String s) {
        return s;
    }

    @Expression(name = "aot_test_fn")
    @SuppressWarnings("unused")
    public static String exprFn(String s) {
        return s;
    }

    /**
     * Mirrors exactly what the AOT source generator emits: types + names only,
     * an empty method-level annotation array, no captured annotations.
     */
    private static AOTMethod descriptorForFn() {
        return new AOTMethod(
                "fn",
                AOTMethodAnnotationsTest.class.getName(),
                "java.lang.String",
                new String[] { "java.lang.String" },
                new String[] { "s" },
                Modifier.PUBLIC | Modifier.STATIC,
                new Annotation[0],
                false, false, false,
                new String[0]);
    }

    private static AOTMethod descriptorForPlain() {
        return new AOTMethod(
                "plain",
                AOTMethodAnnotationsTest.class.getName(),
                "java.lang.String",
                new String[] { "java.lang.String" },
                new String[] { "s" },
                Modifier.PUBLIC | Modifier.STATIC,
                new Annotation[0],
                false, false, false,
                new String[0]);
    }

    @Test
    void getAnnotationRecoversMethodAnnotationFromLiveMethod() {
        AOTMethod method = descriptorForFn();

        TestMethodAnno anno = method.getAnnotation(IClass.getClass(TestMethodAnno.class));
        assertNotNull(anno, "@TestMethodAnno must surface on the method (live-reflection fallback)");
        assertEquals("v", anno.value());
    }

    @Test
    void getAnnotationsRecoversMethodAnnotationsFromLiveMethod() {
        AOTMethod method = descriptorForFn();

        Annotation[] all = method.getAnnotations();
        assertTrue(
                Arrays.stream(all).anyMatch(a -> a.annotationType() == TestMethodAnno.class),
                "getAnnotations() must include @TestMethodAnno recovered from the live method");
    }

    @Test
    void getDeclaredAnnotationsRecoversMethodAnnotationsFromLiveMethod() {
        AOTMethod method = descriptorForFn();

        Annotation[] declared = method.getDeclaredAnnotations();
        assertTrue(
                Arrays.stream(declared).anyMatch(a -> a.annotationType() == TestMethodAnno.class),
                "getDeclaredAnnotations() must include @TestMethodAnno from the live method");
    }

    /**
     * SPI/registrar proof: {@code FrameworkBuiltinRegistrar.registerFunctionClass}
     * registers an {@code @Expression} function by iterating
     * {@code IClass.getDeclaredMethods()} and calling
     * {@code m.getAnnotation(IClass.getClass(Expression.class))}. Under AOT that
     * call returned {@code null} before the fix, so the FQN/registrar path
     * registered zero functions. This asserts the exact call the registrar makes
     * now resolves the real {@code @Expression} annotation on an AOT descriptor.
     */
    @Test
    void expressionAnnotationResolvesOnAotDescriptor() {
        AOTMethod exprMethod = new AOTMethod(
                "exprFn",
                AOTMethodAnnotationsTest.class.getName(),
                "java.lang.String",
                new String[] { "java.lang.String" },
                new String[] { "s" },
                Modifier.PUBLIC | Modifier.STATIC,
                new Annotation[0],
                false, false, false,
                new String[0]);

        Expression expr = exprMethod.getAnnotation(IClass.getClass(Expression.class));
        assertNotNull(expr, "the registrar's getAnnotation(@Expression) call must resolve under AOT");
        assertEquals("aot_test_fn", expr.name());
    }

    @Test
    void absentAnnotationStillReturnsNull() {
        AOTMethod method = descriptorForPlain();

        assertEquals(null, method.getAnnotation(IClass.getClass(TestMethodAnno.class)),
                "a method without the annotation must still report null");
        assertEquals(0, method.getAnnotations().length,
                "a method without annotations carries none");
    }
}
