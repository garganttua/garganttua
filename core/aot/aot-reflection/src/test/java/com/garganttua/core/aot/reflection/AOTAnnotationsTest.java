package com.garganttua.core.aot.reflection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.annotation.Annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AOTAnnotations}, the helper the generated field / method /
 * constructor descriptors call to carry the member's real annotations.
 */
@DisplayName("AOTAnnotations — member annotations recovered from the live class")
class AOTAnnotationsTest {

    private static String markerValue(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a instanceof AOTAnnotationsMarker marker) {
                return marker.value();
            }
        }
        return null;
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        void field_annotation_is_recovered() {
            Annotation[] annotations = AOTAnnotations.ofField(AOTAnnotationsFixture.class, "tag");
            assertEquals("field", markerValue(annotations));
        }

        @Test
        void method_annotation_is_recovered_with_erased_parameter_types() {
            Annotation[] annotations = AOTAnnotations.ofMethod(
                    AOTAnnotationsFixture.class, "describe", String.class);
            assertEquals("method", markerValue(annotations));
        }

        @Test
        void constructor_annotation_is_recovered() {
            Annotation[] annotations = AOTAnnotations.ofConstructor(
                    AOTAnnotationsFixture.class, String.class);
            assertEquals("constructor", markerValue(annotations));
        }

        @Test
        void unannotated_members_yield_an_empty_array() {
            assertEquals(0, AOTAnnotations.ofField(AOTAnnotationsFixture.class, "count").length);
            assertEquals(0, AOTAnnotations.ofMethod(AOTAnnotationsFixture.class, "plain").length);
            assertEquals(0, AOTAnnotations.ofConstructor(AOTAnnotationsFixture.class).length);
        }
    }

    @Nested
    @DisplayName("degradation — a descriptor must never break a class load")
    class Degradation {

        @Test
        void unknown_field_yields_an_empty_array() {
            assertEquals(0, AOTAnnotations.ofField(AOTAnnotationsFixture.class, "gone").length);
        }

        @Test
        void unknown_method_or_changed_signature_yields_an_empty_array() {
            assertEquals(0, AOTAnnotations.ofMethod(AOTAnnotationsFixture.class, "gone").length);
            assertEquals(0, AOTAnnotations.ofMethod(
                    AOTAnnotationsFixture.class, "describe", Integer.class).length);
        }

        @Test
        void unknown_constructor_signature_yields_an_empty_array() {
            assertEquals(0, AOTAnnotations.ofConstructor(AOTAnnotationsFixture.class, Integer.class).length);
        }

        @Test
        void null_owner_or_name_yields_an_empty_array() {
            assertEquals(0, AOTAnnotations.ofField(null, "tag").length);
            assertEquals(0, AOTAnnotations.ofField(AOTAnnotationsFixture.class, null).length);
            assertEquals(0, AOTAnnotations.ofMethod(null, "describe", String.class).length);
            assertEquals(0, AOTAnnotations.ofMethod(AOTAnnotationsFixture.class, null).length);
            assertEquals(0, AOTAnnotations.ofConstructor(null).length);
        }
    }

    @Nested
    @DisplayName("descriptors built with the recovered arrays expose the annotation")
    class Descriptors {

        @Test
        void aot_field_exposes_the_recovered_annotation() {
            AOTField field = new AOTField("tag", AOTAnnotationsFixture.class.getName(),
                    "java.lang.String", 1,
                    AOTAnnotations.ofField(AOTAnnotationsFixture.class, "tag"), null);
            assertEquals("field", markerValue(field.getAnnotations()));
            assertEquals("field", markerValue(field.getDeclaredAnnotations()));
        }

        @Test
        void aot_constructor_exposes_the_recovered_annotation() {
            AOTConstructor<AOTAnnotationsFixture> constructor = new AOTConstructor<>(
                    AOTAnnotationsFixture.class.getName(),
                    new String[] {"java.lang.String"}, new String[] {"tag"}, 1,
                    AOTAnnotations.ofConstructor(AOTAnnotationsFixture.class, String.class),
                    false);
            assertEquals("constructor", markerValue(constructor.getAnnotations()));
        }

        @Test
        void aot_method_exposes_the_recovered_annotation() {
            AOTMethod method = new AOTMethod("describe", AOTAnnotationsFixture.class.getName(),
                    "java.lang.String", new String[] {"java.lang.String"}, new String[] {"prefix"},
                    1, AOTAnnotations.ofMethod(AOTAnnotationsFixture.class, "describe", String.class),
                    false, false, false);
            assertEquals("method", markerValue(method.getAnnotations()));
        }
    }

    @Test
    @DisplayName("the returned array is a fresh copy, never a shared constant")
    void returned_array_is_not_shared() {
        Annotation[] first = AOTAnnotations.ofField(AOTAnnotationsFixture.class, "tag");
        Annotation[] second = AOTAnnotations.ofField(AOTAnnotationsFixture.class, "tag");
        assertNotNull(first[0]);
        assertNotSame(first, second, "each call must hand out its own array");
        assertSame(first[0].annotationType(), second[0].annotationType());
    }
}
