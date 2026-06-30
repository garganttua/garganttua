package com.garganttua.api.core.unit.usecase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.operation.Scope;
import com.garganttua.api.commons.operation.TechnicalOperation;
import com.garganttua.api.core.usecase.UseCase;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethodReturn;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;

/**
 * Verifies that {@link UseCase}'s contract methods are honest and non-throwing: none of the five
 * formerly-stubbed methods raises {@link UnsupportedOperationException}, and each returns the
 * documented value. {@code UseCase} is a builder artifact; real execution flows through
 * {@code UseCaseDefinition.binder()}.
 */
@DisplayName("UseCase (builder artifact) Tests")
class UseCaseTest {

    @BeforeAll
    static void initReflection() {
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner())
                .build());
    }

    private UseCase<String, Integer> useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.useCase = new UseCase<>(
                "myUseCase",
                null,
                "/suffix",
                "/path",
                Scope.oneEntity,
                TechnicalOperation.create,
                (IClass<Object>) (IClass<?>) IClass.getClass(String.class),
                (IClass<Object>) (IClass<?>) IClass.getClass(Integer.class));
    }

    @Test
    @DisplayName("getExecutableReference() returns a stable non-null reference and does not throw")
    void getExecutableReferenceIsHonest() {
        String ref = assertDoesNotThrow(() -> this.useCase.getExecutableReference());
        assertNotNull(ref);
        assertEquals("myUseCase", ref);
    }

    @Test
    @DisplayName("execute() returns Optional.empty() and does not throw")
    void executeReturnsEmpty() {
        Optional<IMethodReturn<Integer>> result = assertDoesNotThrow(() -> this.useCase.execute());
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("dependencies() returns an empty set and does not throw")
    void dependenciesReturnsEmptySet() {
        Set<IClass<?>> deps = assertDoesNotThrow(() -> this.useCase.dependencies());
        assertNotNull(deps);
        assertTrue(deps.isEmpty());
    }

    @Test
    @DisplayName("supply() delegates to execute() and returns Optional.empty()")
    void supplyReturnsEmpty() {
        Optional<IMethodReturn<Integer>> result = assertDoesNotThrow(() -> this.useCase.supply());
        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("getSuppliedType() returns the use-case output type and does not throw")
    void getSuppliedTypeIsHonest() {
        var type = assertDoesNotThrow(() -> this.useCase.getSuppliedType());
        assertEquals(Integer.class, type);
    }

    @Test
    @DisplayName("getSuppliedClass() returns IMethodReturn and does not throw")
    void getSuppliedClassIsHonest() {
        IClass<IMethodReturn<Integer>> clazz = assertDoesNotThrow(() -> this.useCase.getSuppliedClass());
        assertNotNull(clazz);
        assertTrue(clazz.represents(IMethodReturn.class));
    }
}
