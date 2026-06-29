package com.garganttua.core.aot.annotation.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.garganttua.core.aot.annotation.scanner.app.AppExpressionsOne;
import com.garganttua.core.aot.reflection.AOTReflectionProvider;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Oracle test for the AOT annotation index METHOD path. It reproduces the
 * latent bug whereby {@code M:Class#method(params)} entries were parsed into the
 * index but never served through {@code getMethodsWithAnnotation}, so
 * application {@code @Expression} static methods (discoverable only via the AOT
 * index) resolved to nothing.
 */
@DisplayName("AOT annotation index — METHOD entries are served")
public class AotMethodIndexOracleTest {

    private static final String PROVIDER_PACKAGE = "com.garganttua.core.aot.annotation.scanner.app";

    private static IClass<? extends Annotation> testIndexedClass;
    private ClassLoader originalContextClassLoader;

    @BeforeAll
    static void setUpReflection() throws DslException {
        IReflection reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .build();
        IClass.setReflection(reflection);
        testIndexedClass = IClass.getClass(TestIndexed.class);
    }

    @AfterAll
    static void tearDownReflection() {
        IClass.setReflection(null);
    }

    @BeforeEach
    void rememberClassLoader() {
        originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void restoreClassLoader() {
        Thread.currentThread().setContextClassLoader(originalContextClassLoader);
    }

    @Nested
    @DisplayName("single index URL")
    class SingleUrl {

        @Test
        @DisplayName("getMethodsWithAnnotation(annotation) returns the indexed M: method")
        void servesMethodWithoutPackageFilter() {
            AOTAnnotationScanner scanner = new AOTAnnotationScanner();

            List<IMethod> methods = scanner.getMethodsWithAnnotation(testIndexedClass);

            IMethod fn = findByName(methods, "fn");
            assertNotNull(fn, "the @TestIndexed M: entry must be served by getMethodsWithAnnotation");
            assertEquals(AppExpressionsOne.class.getName(), fn.getDeclaringClass().getName());
            assertEquals(1, fn.getParameterCount());
        }

        @Test
        @DisplayName("getMethodsWithAnnotation(package, annotation) returns the package-filtered M: method")
        void servesMethodWithPackageFilter() {
            AOTAnnotationScanner scanner = new AOTAnnotationScanner();

            List<IMethod> methods = scanner.getMethodsWithAnnotation(PROVIDER_PACKAGE, testIndexedClass);

            assertNotNull(findByName(methods, "fn"),
                    "the @TestIndexed M: entry must be served on the package-filtered path");
        }
    }

    @Nested
    @DisplayName("multiple index URLs at the same resource path")
    class MultiUrl {

        @Test
        @DisplayName("M: entries from every index URL are merged")
        void mergesMethodsAcrossAllUrls(@TempDir Path tempRoot) throws IOException {
            Path indexDir = tempRoot.resolve("META-INF/garganttua/index");
            Files.createDirectories(indexDir);
            Files.writeString(
                    indexDir.resolve(TestIndexed.class.getName()),
                    "M:" + PROVIDER_PACKAGE + ".AppExpressionsTwo#other(String)\n",
                    StandardCharsets.UTF_8);

            URL extraRoot = tempRoot.toUri().toURL();
            ClassLoader merged = new URLClassLoader(new URL[] { extraRoot }, originalContextClassLoader);
            Thread.currentThread().setContextClassLoader(merged);

            AOTAnnotationScanner scanner = new AOTAnnotationScanner();
            List<IMethod> methods = scanner.getMethodsWithAnnotation(testIndexedClass);

            assertNotNull(findByName(methods, "fn"),
                    "method from the first index URL must be present");
            assertNotNull(findByName(methods, "other"),
                    "method from the second index URL must be merged in");
            assertTrue(methods.size() >= 2, "both M: entries must be served");
        }
    }

    @Nested
    @DisplayName("pure-AOT reflection (native scenario, no runtime fallback)")
    class AotOnlyMode {

        @Test
        @DisplayName("M: method is served when only the AOT reflection provider is active")
        void servesMethodUnderAotOnlyReflection() throws DslException {
            IReflection priorReflection = IClass.getReflection();
            IClass.setReflection(null);
            try {
                IReflection aot = ReflectionBuilder.builder()
                        .withProvider(new AOTReflectionProvider(), 20)
                        .build();
                IClass.setReflection(aot);

                IClass<? extends Annotation> ann = IClass.getClass(TestIndexed.class);
                AOTAnnotationScanner scanner = new AOTAnnotationScanner();
                List<IMethod> methods = scanner.getMethodsWithAnnotation(PROVIDER_PACKAGE, ann);

                assertNotNull(findByName(methods, "fn"),
                        "an AOT-indexed @-annotated METHOD must be served under pure-AOT reflection");
            } finally {
                IClass.setReflection(priorReflection);
            }
        }
    }

    private static IMethod findByName(List<IMethod> methods, String name) {
        return methods.stream()
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}
