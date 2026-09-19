package com.garganttua.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.core.script.nodes.StatementBlock;
import com.garganttua.core.workflow.dsl.WorkflowsBuilder;

import jakarta.annotation.Nullable;

/**
 * What a wrapped stage hands its wrapper, and what survives the wrap.
 *
 * <p>
 * {@code IWorkflowStageBuilder.wrap(expression)} is a TEXTUAL substitution: the generator replaces
 * {@code @0} in the wrapper expression with the parenthesized stage content
 * ({@code ScriptGenerator#appendWrappedStage}). That parenthesized group reaches the wrapper
 * function as a {@link StatementBlock} — <strong>a block still to run</strong>, not an evaluated
 * value — so a wrapper MUST execute it, exactly as {@code ControlFlowFunctions.if} executes its
 * branch. A wrapper typed on {@code ISupplier} instead receives a supplier of the block object and
 * returns it unexecuted: the stage silently does nothing.
 * </p>
 *
 * <p>
 * These tests pin the consequence for any "wrap the write in a lock" design: that the stage's
 * declared output AND its generated {@code _<stage>_<script>_code} — which every downstream stage
 * guard reads — still reach the rest of the workflow. The shape mirrors what an api domain workflow
 * generates: a CONDITIONAL, FILE-based stage (the {@code include()} + {@code execute_script()} pair,
 * the only form that produces a code var), followed by a stage reading what it left behind.
 * {@code wrap} shipped with no test and no caller anywhere in the monorepo.
 * </p>
 */
class WorkflowWrapScopeTest {

    /** Counts wrapper entries, so a test can tell "evaluated once" from "never evaluated". */
    static final AtomicInteger ENTERED = new AtomicInteger();

    /** What the stage AFTER the wrapped one could see: its declared output, and its exit code. */
    static final AtomicReference<Object> SEEN_MARKER = new AtomicReference<>();
    static final AtomicReference<Object> SEEN_CODE = new AtomicReference<>();

    /**
     * Stand-ins for a synchronizing wrapper and a downstream reader. {@code wrapProbe} runs the block
     * it is handed exactly as a mutex-holding wrapper would, without needing a mutex manager.
     */
    @Reflected
    public static class WrapProbeFunctions {

        @Expression(name = "wrapProbe", description = "Evaluates the wrapped block and returns its value")
        public static Object wrapProbe(@Nullable Object block) {
            ENTERED.incrementAndGet();
            // Same contract as ControlFlowFunctions.if: a parenthesized group reaches a function as
            // a StatementBlock to run, not as an already-evaluated value.
            if (block instanceof StatementBlock statements) {
                return statements.execute();
            }
            return block;
        }

        @Expression(name = "recordSeen", description = "Records what the downstream stage could read")
        public static Object recordSeen(@Nullable Object marker, @Nullable Object code) {
            SEEN_MARKER.set(marker);
            SEEN_CODE.set(code);
            return "recorded";
        }
    }

    private static IReflectionBuilder reflectionBuilder;

    private IInjectionContextBuilder injectionContextBuilder;
    private IScriptsBuilder scriptsBuilder;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void setupClass() throws Exception {
        Class<? extends IReflectionProvider> providerClass =
                (Class<? extends IReflectionProvider>) Class.forName(
                        "com.garganttua.core.reflection.runtime.RuntimeReflectionProvider");
        reflectionBuilder = ReflectionBuilder.builder()
                .withProvider(providerClass.getDeclaredConstructor().newInstance())
                .withScanner(new ReflectionsAnnotationScanner());
        reflectionBuilder.build();
    }

    @BeforeEach
    void setup() {
        ENTERED.set(0);
        SEEN_MARKER.set(null);
        SEEN_CODE.set(null);

        injectionContextBuilder = InjectionContext.builder()
                .provide(reflectionBuilder)
                .autoDetect(true)
                .withPackage("com.garganttua.core.runtime");

        IExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder.withPackage("com.garganttua").autoDetect(true)
                .provide(injectionContextBuilder);

        injectionContextBuilder.build().onInit().onStart();
        expressionContextBuilder.build();

        IRuntimesBuilder runtimesBuilder = RuntimesBuilder.builder().provide(injectionContextBuilder);
        scriptsBuilder = ScriptsBuilder.builder()
                .provide(injectionContextBuilder)
                .provide(expressionContextBuilder)
                .provide(runtimesBuilder);
    }

    /** A domain-workflow-shaped pair of stages: a guarded, file-based write, then a reader. */
    private IWorkflow writeThenRead(boolean wrapped) {
        var stage = WorkflowsBuilder.builder()
                .provide(injectionContextBuilder)
                .provide(scriptsBuilder)
                .workflow("wrap-scope")
                .stage("write")
                    .when("equals(1, 1)");
        if (wrapped) {
            stage = stage.wrap("wrapProbe(@0)");
        }
        return stage
                    .script("classpath:wrapscope/WRITE.gs")
                        .name("write")
                        .output("entity", "output")
                        .up()
                    .up()
                .stage("after")
                    .script("recorded <- recordSeen(@entity, @_write_write_code)")
                        .name("after")
                        .up()
                    .up()
                .build();
    }

    @Nested
    @DisplayName("a stage whose content is wrapped")
    class WrappedStage {

        @Test
        @DisplayName("still leaves its variables and exit code readable downstream")
        void assignmentsSurviveTheWrappedGroup() {
            IWorkflow workflow = writeThenRead(true);

            WorkflowResult result = workflow.execute();

            assertTrue(result.isSuccess(),
                    () -> "workflow failed: " + result + "\nscript:\n" + workflow.getGeneratedScript());
            assertEquals(1, ENTERED.get(), "the wrapper must have run exactly once");
            assertNotNull(SEEN_CODE.get(),
                    () -> "the next stage saw no exit code — the guards downstream of a wrapped stage"
                            + " would all read null;\nscript:\n" + workflow.getGeneratedScript());
            assertEquals(0, ((Number) SEEN_CODE.get()).intValue(),
                    "_write_write_code is what every downstream guard reads — it must survive the wrap");
            assertNotNull(SEEN_MARKER.get(),
                    () -> "the declared output never reached the next stage — a wrapped business stage"
                            + " would serialize nothing;\nscript:\n" + workflow.getGeneratedScript());
            assertEquals(42, ((Number) SEEN_MARKER.get()).intValue(),
                    "the stage's declared output must survive the wrap");
        }

        @Test
        @DisplayName("behaves exactly as the same stage unwrapped")
        void matchesTheUnwrappedBaseline() {
            IWorkflow workflow = writeThenRead(false);

            WorkflowResult result = workflow.execute();

            assertTrue(result.isSuccess(),
                    () -> "baseline failed: " + result + "\nscript:\n" + workflow.getGeneratedScript());
            assertEquals(0, ENTERED.get(), "no wrapper declared, none must run");
            assertEquals(0, ((Number) SEEN_CODE.get()).intValue(),
                    "baseline: the exit code reaches the next stage");
            assertEquals(42, ((Number) SEEN_MARKER.get()).intValue(),
                    "baseline: the declared output reaches the next stage");
        }
    }
}
