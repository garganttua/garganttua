package com.garganttua.core.expression.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;

import fixtures.expressions.AmbiguousFixtures;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * One expression name must designate one method.
 *
 * <p>
 * Two {@code @Expression} methods sharing a name AND an arity resolve to both, and the failure lands
 * where nothing explains it: a reflection ambiguity thrown when the function is first used, naming
 * neither the expression nor the module it came from. {@code garganttua-mutex-redis} shipped that
 * for three versions — two {@code syncRedis(…, ISupplier)}, one on {@code String} and one on
 * {@code Object} — so merely putting the jar on an application's classpath stopped it from starting.
 * </p>
 */
@DisplayName("An expression name carried by two methods")
class AmbiguousExpressionNameTest {

    @BeforeAll
    static void setupReflection() {
        IReflection reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider(), 1)
                .build();
        IClass.setReflection(reflection);
    }




    @Test
    @DisplayName("is refused while registering, naming the offending pair")
    void ambiguityIsRefused() {
        DslException refused = assertThrows(DslException.class,
                () -> check(AmbiguousFixtures.Ambiguous.class));

        assertTrue(refused.getMessage().contains("collide"),
                () -> "the message must name the expression: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("Ambiguous"),
                () -> "and the class that declares it: " + refused.getMessage());
    }

    @Test
    @DisplayName("is allowed when the arities differ — those resolve unambiguously")
    void differentAritiesAreFine() {
        assertDoesNotThrow(() -> check(AmbiguousFixtures.DifferentArities.class));
    }

    @Test
    @DisplayName("is not triggered by two same-arity methods under different names")
    void distinctNamesAreFine() {
        assertDoesNotThrow(() -> check(AmbiguousFixtures.DistinctNames.class));
    }

    @Test
    @DisplayName("is not triggered when the parameter types tell the two apart")
    void unrelatedParameterTypesAreFine() {
        // The shape Beans.bean has: bean(Optional, BeanReference) and bean(IClass, String). Refusing
        // it would condemn a function that resolves and works, which is why the rule tests shadowing
        // rather than arity.
        assertDoesNotThrow(() -> check(AmbiguousFixtures.SameArityUnrelatedTypes.class));
    }

    /** Runs the rule over every declared method of {@code fixture}, as each registration path does. */
    private static void check(Class<?> fixture) {
        IClass<?> owner = IClass.getClass(fixture);
        ExpressionNames.refuseAmbiguous(owner.getCanonicalName(),
                java.util.Arrays.asList(owner.getDeclaredMethods()));
    }
}
