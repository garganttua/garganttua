package com.garganttua.core.reflection.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.reflection.IField;

/**
 * Covers {@code IClass.findDeclaredField}, the non-throwing counterpart of
 * {@code getDeclaredField}.
 *
 * <p>
 * It exists because "is there a field by this name?" is asked about every element the resolution
 * path walks, and most of those are methods — so answering by constructing a
 * {@link NoSuchFieldException}, stack trace included, made the negative answer the expensive one.
 * </p>
 */
@DisplayName("RuntimeClass.findDeclaredField")
class FindDeclaredFieldBehaviourTest {

    public static class Base {
        protected String inherited;
    }

    public static class Sample extends Base {
        private String present;

        public String method() { return present; }
    }

    @Test
    @DisplayName("a declared field is found")
    void declaredFieldIsFound() {
        Optional<IField> found = RuntimeClass.of(Sample.class).findDeclaredField("present");

        assertTrue(found.isPresent());
        assertEquals("present", found.get().getName());
    }

    @Test
    @DisplayName("an absent name answers empty instead of throwing")
    void absentFieldIsEmpty() {
        assertTrue(RuntimeClass.of(Sample.class).findDeclaredField("nope").isEmpty());
    }

    @Test
    @DisplayName("a METHOD name is not a field — the common case on a resolution path")
    void methodNameIsNotAField() {
        assertTrue(RuntimeClass.of(Sample.class).findDeclaredField("method").isEmpty());
    }

    @Test
    @DisplayName("it stays DECLARED-only: an inherited field is not found here")
    void inheritedFieldIsNotDeclared() {
        assertTrue(RuntimeClass.of(Sample.class).findDeclaredField("inherited").isEmpty(),
                "same scope as getDeclaredField — only the failure shape differs");
        assertTrue(RuntimeClass.of(Base.class).findDeclaredField("inherited").isPresent());
    }

    @Test
    @DisplayName("it agrees with getDeclaredField on both outcomes")
    void agreesWithTheThrowingForm() throws Exception {
        assertEquals(RuntimeClass.of(Sample.class).getDeclaredField("present").getName(),
                RuntimeClass.of(Sample.class).findDeclaredField("present").orElseThrow().getName());

        assertThrows(NoSuchFieldException.class, () -> RuntimeClass.of(Sample.class).getDeclaredField("nope"));
        assertFalse(RuntimeClass.of(Sample.class).findDeclaredField("nope").isPresent());
    }
}
