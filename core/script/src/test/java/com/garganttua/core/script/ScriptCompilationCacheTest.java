package com.garganttua.core.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.garganttua.core.expression.context.IExpressionContext;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.context.ScriptCompilationCache;
import com.garganttua.core.script.context.ScriptContext;

/**
 * Locks in the {@code include()} compilation cache: a script source is parsed and built into a
 * runtime once per lineage, not once per call.
 *
 * <p>Without it, {@link com.garganttua.core.script.ICompiledScript}'s per-call frame — which exists
 * for re-entrancy safety — threw away the included-script registry on every execution, so a
 * per-request workflow re-compiled every script it includes on every single request.
 */
@DisplayName("include() compilation cache — compile once per source, per lineage")
class ScriptCompilationCacheTest {

    @TempDir
    Path tempDir;

    private static IReflectionBuilder reflectionBuilder;

    @BeforeAll
    static void setup() throws Exception {
        @SuppressWarnings("unchecked")
        Class<? extends IReflectionProvider> providerClass =
                (Class<? extends IReflectionProvider>) Class.forName(
                        "com.garganttua.core.reflection.runtime.RuntimeReflectionProvider");
        reflectionBuilder = ReflectionBuilder.builder()
                .withProvider(providerClass.getDeclaredConstructor().newInstance())
                .withScanner(new ReflectionsAnnotationScanner());
        reflectionBuilder.build();
    }

    private ScriptContext newRootContext() {
        IInjectionContextBuilder injectionContextBuilder = InjectionContext.builder()
                .provide(reflectionBuilder)
                .autoDetect(true)
                .withPackage("com.garganttua.core.runtime");

        ExpressionContextBuilder expressionContextBuilder = ExpressionContextBuilder.builder();
        expressionContextBuilder.withPackage("com.garganttua").autoDetect(true).provide(injectionContextBuilder);

        IInjectionContext injectionContext = injectionContextBuilder.build();
        injectionContext.onInit().onStart();

        IExpressionContext expressionContext = expressionContextBuilder.build();
        return new ScriptContext(expressionContext,
                () -> RuntimesBuilder.builder().provide(injectionContextBuilder), null);
    }

    private String writeScript(String fileName, String body) throws IOException {
        File file = tempDir.resolve(fileName).toFile();
        Files.writeString(file.toPath(), body);
        return file.getAbsolutePath().replace("\\", "\\\\");
    }

    @Nested
    @DisplayName("Across per-call frames")
    class AcrossFrames {

        @Test
        @DisplayName("repeated executions of one compiled script compile the included source once")
        void includedSourceIsCompiledOnce() throws IOException {
            String subPath = writeScript("sub.gs", "value <- string(\"sub-result\") -> 42");

            ScriptContext root = newRootContext();
            root.load(String.format("""
                    include("%s")
                    code <- call("sub") -> 200
                    """, subPath));
            root.compile();

            ICompiledScript compiled = root.toCompiled();
            ScriptCompilationCache cache = root.getCompilationCache();

            compiled.execute();
            long afterFirst = cache.hits();
            assertEquals(1, cache.size(), "one distinct included source must be cached");

            compiled.execute();
            compiled.execute();

            assertEquals(1, cache.size(), "further executions must not add cache entries");
            assertTrue(cache.hits() > afterFirst,
                    "later frames must be served from the cache, hits=" + cache.hits());
            assertEquals(1, cache.misses(),
                    "the source must be compiled exactly once, misses=" + cache.misses());
        }

        @Test
        @DisplayName("each execution still gets its own sub-script instance and results")
        void framesStayIsolated() throws IOException {
            String subPath = writeScript("isolated.gs", "value <- string(\"run\") -> 7");

            ScriptContext root = newRootContext();
            root.load(String.format("""
                    include("%s")
                    code <- call("isolated") -> 200
                    """, subPath));
            root.compile();

            ICompiledScript compiled = root.toCompiled();

            IScriptExecutionResult first = compiled.execute();
            IScriptExecutionResult second = compiled.execute();

            assertEquals(200, first.code());
            assertEquals(second.code(), first.code());
            // Distinct result objects: sharing a compiled runtime must not share per-run state.
            assertTrue(first != second, "each execution must produce its own result");
        }
    }

    @Nested
    @DisplayName("Cache scope")
    class Scope {

        @Test
        @DisplayName("child frames share their parent's cache")
        void childrenShareTheCache() {
            ScriptContext root = newRootContext();
            ScriptContext child = root.createChildScript();
            ScriptContext grandChild = child.createChildScript();

            assertSame(root.getCompilationCache(), child.getCompilationCache());
            assertSame(root.getCompilationCache(), grandChild.getCompilationCache());
        }

        @Test
        @DisplayName("independent roots do not share compilations")
        void independentRootsAreIsolated() throws IOException {
            String subPath = writeScript("shared.gs", "value <- string(\"x\") -> 1");
            String source = String.format("include(\"%s\")%n", subPath);

            ScriptContext firstRoot = newRootContext();
            firstRoot.load(source);
            firstRoot.compile();
            firstRoot.toCompiled().execute();

            ScriptContext secondRoot = newRootContext();

            assertEquals(1, firstRoot.getCompilationCache().size());
            assertEquals(0, secondRoot.getCompilationCache().size(),
                    "a separate lineage must start with an empty cache");
        }
    }
}
