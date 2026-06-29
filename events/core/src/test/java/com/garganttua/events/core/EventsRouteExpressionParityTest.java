package com.garganttua.events.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.IScript;
import com.garganttua.core.script.IScriptingEnvironment;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.events.api.IEvents;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.core.dsl.EventsBuilder;

/**
 * Oracle test for events route-stage expression-resolution parity with garganttua-api.
 *
 * <p>An application declares its {@code @Expression} provider package
 * ({@code com.garganttua.events.core.exprapp}) only through the events DSL
 * ({@link IEventsBuilder#withPackage(String)}) — the same way an application exposes its functions
 * in api pipeline stages. The framework function packages are registered on the expression context
 * (as the bootstrap does), but the application package is deliberately NOT registered directly on
 * the expression/injection builders: the only path for it to reach the scan is the events DSL
 * propagating it into the captured {@link IExpressionContextBuilder}.
 *
 * <p>The chain is wired exactly like {@code Events.buildRouteWorkflow} sequences it (injection →
 * expression → runtimes → scripts), and the expression context is built AFTER {@code EventsBuilder}
 * has captured and configured it — mirroring the bootstrap, which calls {@code provide(...)} during
 * dependency resolution (before any {@code build()}). A route stage that calls the app function
 * {@code app_route_fn("x")} is then compiled and executed through the very
 * {@link IScriptingEnvironment} the route would use, and must evaluate to {@code "app:x"} rather
 * than failing with "Undefined function".
 */
@DisplayName("Events route-stage @Expression parity — app function resolves via EventsBuilder.withPackage")
class EventsRouteExpressionParityTest {

    /** Application package whose @Expression methods must resolve in a route stage. */
    private static final String APP_PACKAGE = "com.garganttua.events.core.exprapp";

    private IReflectionBuilder reflectionBuilder;
    private IInjectionContextBuilder injectionContextBuilder;
    private IExpressionContextBuilder expressionContextBuilder;
    private IScriptsBuilder scriptsBuilder;

    @BeforeEach
    void setUp() throws Exception {
        reflectionBuilder = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner());
        IClass.setReflection(reflectionBuilder.build());

        // Mirror the bootstrap: register the FRAMEWORK function packages on the injection context
        // (so framework built-ins resolve), but NOT the application package — the app package may
        // only reach the scan through the events DSL.
        injectionContextBuilder = InjectionContext.builder()
                .provide(reflectionBuilder)
                .autoDetect(true)
                .withPackage("com.garganttua.core.expression.functions")
                .withPackage("com.garganttua.core.script.functions")
                .withPackage("com.garganttua.core.observability");

        expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder.autoDetect(true).provide(injectionContextBuilder);
    }

    @AfterEach
    void tearDown() {
        IClass.setReflection(null);
    }

    /**
     * Drives an EventsBuilder the way the bootstrap does (capture the dependency builders, declare
     * the application package via the DSL), then builds the chain and runs a route-stage script that
     * calls the application {@code @Expression}. Proves the app function resolves to {@code "app:x"}.
     */
    @Test
    @DisplayName("a route stage calling an app @Expression resolves to app:x")
    void appExpressionResolvesInRouteStage() throws Exception {
        // An application wires its package through the events DSL only — never on the expr builder.
        IEventsBuilder eventsBuilder = EventsBuilder.builder()
                .asset("oracle")
                .withPackage(APP_PACKAGE);

        // Build the scripts chain (the IScriptsBuilder EventsBuilder depends on). Wired exactly as the
        // bootstrap does: injection → expression → runtimes → scripts.
        IRuntimesBuilder runtimesBuilder = RuntimesBuilder.builder().provide(injectionContextBuilder);
        scriptsBuilder = ScriptsBuilder.builder()
                .provide(injectionContextBuilder)
                .provide(expressionContextBuilder)
                .provide(runtimesBuilder);

        // The bootstrap captures the dependency builders via provide() BEFORE any build(); EventsBuilder
        // must, at that moment, configure the shared expression context (api parity). The application
        // package was declared via withPackage() before the expression builder was captured, so it must
        // be replayed into the captured builder here.
        eventsBuilder.provide(injectionContextBuilder);
        eventsBuilder.provide(expressionContextBuilder);
        eventsBuilder.provide(scriptsBuilder);

        // Now build/scan the contexts — the app package must already be configured on the shared
        // expression context by EventsBuilder.provide(expressionContextBuilder).
        injectionContextBuilder.build().onInit().onStart();
        expressionContextBuilder.provide(injectionContextBuilder);
        expressionContextBuilder.build();

        // Build the events engine via the DSL (exercises the full provide()/build() path).
        IEvents events = eventsBuilder.build();
        assertNotNull(events, "EventsBuilder must build an IEvents");

        // Compile and run a route-stage-style script through the same scripting environment a route
        // stage uses (Events.addStages compiles "exchange <- <expression>" against this chain).
        IScriptingEnvironment environment = scriptsBuilder.build();
        IScript script = environment.newScript();
        script.load("result <- app_route_fn(\"x\")");
        script.compile();
        int exitCode = script.execute();

        assertEquals(0, exitCode,
                "the route stage must run without aborting (app_route_fn must resolve, not be 'Undefined function'): "
                        + script.getLastExceptionMessage().orElse("no error"));
        Optional<String> result = script.getVariable("result", IClass.getClass(String.class));
        assertTrue(result.isPresent(), "the route stage must produce a 'result' variable");
        assertEquals("app:x", result.get(),
                "app-defined @Expression must resolve in a route stage exactly as in api pipelines");
    }
}
