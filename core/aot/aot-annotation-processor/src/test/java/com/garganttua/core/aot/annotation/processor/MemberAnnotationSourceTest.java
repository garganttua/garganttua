package com.garganttua.core.aot.annotation.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Member-annotation coverage for the three member source generators.
 *
 * <p>{@code AOTClassSourceGenerator} has always emitted
 * {@code <Type>.class.getAnnotations()} for a type's own annotations, but the
 * field / method / constructor generators hardcoded {@code new Annotation[0]}:
 * as soon as a descriptor existed, every member annotation was gone. These
 * tests pin the generated source down to the {@code AOTAnnotations.ofXxx(...)}
 * call that carries the member's real annotations — including the erased
 * parameter types that make {@code getDeclaredMethod} /
 * {@code getDeclaredConstructor} find the member again.</p>
 *
 * <p>The AOT runtime classes are not on this module's classpath, so the
 * generated sources are produced ({@code -proc:only}) but not compiled;
 * end-to-end "the descriptor really returns the annotation" coverage lives in
 * {@code garganttua-workflow} ({@code MemberAnnotationAotTest}), where the
 * processor runs for real.</p>
 */
@DisplayName("AOT member descriptors — real annotations, not new Annotation[0]")
class MemberAnnotationSourceTest {

    @Test
    void fieldDescriptorCarriesTheRealFieldAnnotations() throws IOException {
        CompileResult r = compile("sample.Marked", """
                package sample;
                import com.garganttua.core.reflection.annotations.Reflected;
                @Reflected(allDeclaredFields = true)
                public class Marked {
                    String tag;
                }
                """);
        assertCompiled(r);
        String fieldSrc = Files.readString(r.outputDir.resolve("sample/AOTField_Marked_tag.java"));
        assertTrue(fieldSrc.contains(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofField(Marked.class, \"tag\")"),
                () -> "field descriptor must source its annotations from the live field; got:\n" + fieldSrc);
        assertFalse(fieldSrc.contains("new Annotation[0]"),
                () -> "field descriptor must not hardcode an empty annotation array; got:\n" + fieldSrc);
    }

    @Test
    void methodDescriptorCarriesTheRealMethodAnnotationsWithErasedParameters() throws IOException {
        CompileResult r = compile("sample.Marked", """
                package sample;
                import com.garganttua.core.reflection.annotations.Reflected;
                import java.util.List;
                @Reflected(queryAllDeclaredMethods = true)
                public class Marked {
                    public <T> void put(String key, List<T> values, int count) {}
                }
                """);
        assertCompiled(r);
        String methodSrc = Files.readString(r.outputDir.resolve("sample/AOTMethod_Marked_put_0.java"));
        assertTrue(methodSrc.contains(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofMethod(Marked.class, \"put\", "
                        + "java.lang.String.class, java.util.List.class, int.class)"),
                () -> "method descriptor must look the method up by its ERASED signature; got:\n" + methodSrc);
        assertFalse(methodSrc.contains("new Annotation[0]"),
                () -> "method descriptor must not hardcode an empty annotation array; got:\n" + methodSrc);
    }

    @Test
    void constructorDescriptorCarriesTheRealConstructorAnnotations() throws IOException {
        CompileResult r = compile("sample.Marked", """
                package sample;
                import com.garganttua.core.reflection.annotations.Reflected;
                @Reflected(queryAllDeclaredConstructors = true)
                public class Marked {
                    public Marked(String tag, long size) {}
                }
                """);
        assertCompiled(r);
        String ctorSrc = Files.readString(r.outputDir.resolve("sample/AOTConstructor_Marked_0.java"));
        assertTrue(ctorSrc.contains(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofConstructor(Marked.class, "
                        + "java.lang.String.class, long.class)"),
                () -> "constructor descriptor must source its annotations from the live ctor; got:\n" + ctorSrc);
        assertFalse(ctorSrc.contains("new Annotation[0]"),
                () -> "constructor descriptor must not hardcode an empty annotation array; got:\n" + ctorSrc);
    }

    @Test
    void noArgConstructorDescriptorEmitsAnArgumentLessLookup() throws IOException {
        CompileResult r = compile("sample.Marked", """
                package sample;
                import com.garganttua.core.reflection.annotations.Reflected;
                @Reflected(queryAllDeclaredConstructors = true)
                public class Marked {
                    public Marked() {}
                }
                """);
        assertCompiled(r);
        String ctorSrc = Files.readString(r.outputDir.resolve("sample/AOTConstructor_Marked_0.java"));
        assertTrue(ctorSrc.contains(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofConstructor(Marked.class)"),
                () -> "no-arg ctor lookup must pass no parameter types; got:\n" + ctorSrc);
    }

    @Test
    void arrayParameterIsEmittedAsAnArrayClassLiteral() throws IOException {
        CompileResult r = compile("sample.Marked", """
                package sample;
                import com.garganttua.core.reflection.annotations.Reflected;
                @Reflected(queryAllDeclaredMethods = true)
                public class Marked {
                    public void feed(byte[] payload, String[] names) {}
                }
                """);
        assertCompiled(r);
        String methodSrc = Files.readString(r.outputDir.resolve("sample/AOTMethod_Marked_feed_0.java"));
        assertTrue(methodSrc.contains("ofMethod(Marked.class, \"feed\", byte[].class, java.lang.String[].class)"),
                () -> "array parameters must be emitted as array class literals; got:\n" + methodSrc);
    }

    // --- harness (mirrors DirectBinderGeneratorTest's) ---

    private record CompileResult(Path outputDir, DiagnosticCollector<JavaFileObject> diagnostics) {}

    private CompileResult compile(String className, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "this test requires a JDK (not JRE)");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path outputDir = Files.createTempDirectory("aot-member-annotations");
        outputDir.toFile().deleteOnExit();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(outputDir));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics,
                    List.of("-Agarganttua.direct.binders=true", "-proc:only"),
                    null, List.of(new InMemorySource(className, source)));
            task.setProcessors(List.of(new DirectBinderGenerator()));
            task.call();
            return new CompileResult(outputDir, diagnostics);
        }
    }

    /**
     * Only processor-emitted errors matter here: javac cannot resolve
     * {@code AOTField} / {@code AOTMethod} / … because the AOT runtime modules
     * are not on this module's classpath.
     */
    private static void assertCompiled(CompileResult r) {
        boolean processorError = r.diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(null).contains("[garganttua-aot]"));
        assertFalse(processorError, () -> "processor emitted an unexpected ERROR; got: " + summary(r));
    }

    private static String summary(CompileResult r) {
        StringBuilder sb = new StringBuilder("\n");
        for (Diagnostic<? extends JavaFileObject> d : r.diagnostics.getDiagnostics()) {
            sb.append("  ").append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
        }
        return sb.toString();
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;
        InMemorySource(String className, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.content = content;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
