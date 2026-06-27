package com.garganttua.core.expression.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.context.IExpressionContext;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.supply.ISupplier;

/**
 * Proves {@link IExpressionFunctionContributor}: an application's own {@code @Expression} provider
 * class (in a non-framework package) becomes resolvable in an expression context purely via the SPI
 * descriptor under {@code META-INF/services} — independent of the runtime/AOT package scanner — and
 * that a contributor naming a missing/throwing class is isolated.
 *
 * <p>
 * The context is configured with a package the scanner will NOT find the test function in, so the
 * only way {@code app_test_fn} can resolve is through the SPI-driven FQN registration path.
 * </p>
 */
class ExpressionFunctionContributorTest {

    /** A package that does not contain the test application function, so the scan can't find it. */
    private static final String SCAN_EXCLUDING_PACKAGE = "com.garganttua.core.expression.scanmiss";

    @BeforeEach
    void setUp() {
        IReflection reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider(), 1)
                .withScanner(new ReflectionsAnnotationScanner(), 1)
                .build();
        IClass.setReflection(reflection);
    }

    private IExpressionContext buildContext() throws Exception {
        IInjectionContextBuilder injectionContextBuilder = InjectionContext.builder();
        injectionContextBuilder.provide(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner()));
        ExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder.withPackage(SCAN_EXCLUDING_PACKAGE)
                .autoDetect(true)
                .provide(injectionContextBuilder);
        injectionContextBuilder.build().onInit().onStart();
        return expressionContextBuilder.build();
    }

    @Nested
    @DisplayName("Application @Expression functions registered via the SPI")
    class ApplicationFunctions {

        @Test
        @DisplayName("app_test_fn (non-framework package) resolves and evaluates via the SPI")
        void appFunctionResolvesAndEvaluates() throws Exception {
            IExpressionContext context = buildContext();

            ISupplier<?> result = context.expression("app_test_fn(hello)").evaluate();

            assertEquals("app:hello", result.supply().get(),
                    "the application function registered through the contributor SPI must evaluate");
        }

        @Test
        @DisplayName("a contributor naming a missing class is isolated; the context still builds "
                + "and other functions resolve")
        void missingClassIsIsolated() throws Exception {
            // The test contributor also names a non-existent class. Building the context and
            // resolving a framework built-in proves the bad name was skipped, not fatal.
            IExpressionContext context =
                    assertDoesNotThrow(ExpressionFunctionContributorTest.this::buildContext);

            ISupplier<?> builtin = context.expression(":valueOf(String.class, 7)").evaluate();
            assertEquals("7", builtin.supply().get(),
                    "framework built-ins must still resolve when a contributor names a bad class");
        }
    }

    @Nested
    @DisplayName("FrameworkBuiltinRegistrar events built-in registration")
    class EventsBuiltins {

        @Test
        @DisplayName("EventExpressions is listed in the framework FQN registration list")
        void eventsExpressionsListed() {
            assertTrue(Arrays.asList(FrameworkBuiltinRegistrar.FRAMEWORK_FUNCTION_CLASSES)
                            .contains("com.garganttua.events.expressions.EventExpressions"),
                    "events built-ins must be registered by FQN so they resolve without "
                            + "-Dgarganttua.packages");
        }
    }
}
