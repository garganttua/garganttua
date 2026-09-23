package com.garganttua.core.workflow.aot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.aot.annotation.scanner.AOTAnnotationScanner;
import com.garganttua.core.aot.commons.AOTRegistry;
import com.garganttua.core.aot.reflection.AOTReflectionProvider;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IConstructor;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;

/**
 * End-to-end coverage, in pure-AOT mode, for the two defects a consumer hit on
 * a native image: {@code private} members missing from the generated
 * descriptors, and nested {@code @Reflected} types registered under a key
 * nobody looks up.
 *
 * <p>The descriptors read here were produced by the real annotation processor
 * during this module's test compilation. Reflection is wired to the AOT
 * provider <em>alone</em>, so nothing on the classpath can quietly answer in
 * the descriptor's place.</p>
 */
@DisplayName("Pure-AOT — private members are described, nested types are found")
class PrivateMemberAotTest {

    private IReflection priorReflection;

    @BeforeEach
    void installAotOnlyReflection() {
        priorReflection = safeCurrentReflection();
        IClass.setReflection(null);
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new AOTReflectionProvider(), 20)
                .withScanner(new AOTAnnotationScanner(), 20)
                .build());
    }

    @AfterEach
    void restorePriorReflection() {
        IClass.setReflection(priorReflection);
    }

    private static IReflection safeCurrentReflection() {
        try {
            return IClass.getReflection();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Set<String> fieldNames(IField[] fields) {
        return Arrays.stream(fields).map(IField::getName).collect(Collectors.toSet());
    }

    private static IField field(IClass<?> type, String name) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no declared field '" + name + "' on " + type.getName()));
    }

    private static String markerValue(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a instanceof MemberMarker marker) {
                return marker.value();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private members
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void declaredFieldsAreCompleteWhenAConstantSitsNextToPrivateOnes() {
        IClass<ConstantAndPrivateFieldsBean> type = IClass.getClass(ConstantAndPrivateFieldsBean.class);

        assertEquals(Set.of("VERSION", "id", "count", "label"), fieldNames(type.getDeclaredFields()),
                "the public constant used to be the ONLY declared field the descriptor kept");
    }

    @Test
    void privateFieldDescriptorCarriesItsAnnotation() {
        IClass<ConstantAndPrivateFieldsBean> type = IClass.getClass(ConstantAndPrivateFieldsBean.class);

        IField id = field(type, "id");
        assertEquals("id", markerValue(id.getAnnotations()),
                "a private field must carry its real annotations, like any other");
        assertTrue(id.getClass().getSimpleName().startsWith("AOTField_"),
                () -> "the answer must come from the generated descriptor, not a live-class synthesis; got "
                        + id.getClass().getName());
    }

    @Test
    void privateFieldIsStillReadableThroughTheReflectiveAccessor() throws IllegalAccessException {
        IClass<ConstantAndPrivateFieldsBean> type = IClass.getClass(ConstantAndPrivateFieldsBean.class);

        ConstantAndPrivateFieldsBean bean = ConstantAndPrivateFieldsBean.of("abc");
        assertEquals("abc", field(type, "id").get(bean),
                "no direct binder is generated for a private field, so AOTField's memoised "
                        + "reflective handle must do the reading");
    }

    @Test
    void privateMethodAndConstructorAreDescribedToo() {
        IClass<ConstantAndPrivateFieldsBean> type = IClass.getClass(ConstantAndPrivateFieldsBean.class);

        IMethod secret = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> "secret".equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the private method must be declared"));
        assertEquals("method", markerValue(secret.getAnnotations()));

        IConstructor<?>[] constructors = type.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals("constructor", markerValue(constructors[0].getAnnotations()),
                "the only constructor is private — it used to be dropped from the descriptor");
    }

    @Test
    void theConsumerScenarioResolvesTheIdentifierField() {
        // What entity().id("id") does under the hood: ObjectQuery → MemberLookup,
        // which walks getDeclaredFields(). With the private fields missing, this
        // threw and the API refused to start.
        IClass<ConstantAndPrivateFieldsBean> type = IClass.getClass(ConstantAndPrivateFieldsBean.class);

        assertNotNull(assertDoesNotThrow(() -> IClass.getReflection().query(type).address("id"),
                "resolving the identifier field must not fail on a class carrying a constant"));
    }

    @Test
    void aFullyPrivateClassStillAnswersCompletely() {
        // This one worked by accident: everything was dropped, the array was
        // empty, and AOTClass fell back to the live class. It must keep working
        // now that the answer comes from the descriptor.
        IClass<AllPrivateFieldsBean> type = IClass.getClass(AllPrivateFieldsBean.class);

        assertEquals(Set.of("only", "size"), fieldNames(type.getDeclaredFields()));
        assertEquals("only", markerValue(field(type, "only").getAnnotations()));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nested types
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void nestedReflectedTypeIsRegisteredUnderItsBinaryName() {
        assertTrue(AOTRegistry.getInstance().contains(NestedHolder.Inner.class.getName()),
                "the descriptor must be registered under the key Class#getName() produces: "
                        + NestedHolder.Inner.class.getName());
    }

    @Test
    void nestedReflectedTypeUsesItsGeneratedDescriptor() {
        IClass<NestedHolder.Inner> type = IClass.getClass(NestedHolder.Inner.class);

        assertEquals("AOTClass_NestedHolder_Inner", type.getClass().getSimpleName(),
                "a registry miss would hand back a shallow synthesised descriptor instead");
        assertSame(NestedHolder.Inner.class, type.getType());
        assertEquals("nested-field", markerValue(field(type, "label").getAnnotations()),
                "the nested type's member annotations must survive too");
    }

    @Test
    void nestedDescriptorNamesTheTypeTheWayClassForNameExpects() throws ClassNotFoundException {
        IClass<NestedHolder.Inner> type = IClass.getClass(NestedHolder.Inner.class);

        // AOTLiveClassFallback hands this name straight to Class.forName; the
        // dotted canonical form throws ClassNotFoundException.
        assertEquals(NestedHolder.Inner.class.getName(), type.getName());
        assertSame(NestedHolder.Inner.class, Class.forName(type.getName()));
    }
}
