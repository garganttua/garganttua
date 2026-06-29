package com.garganttua.core.aot.annotation.scanner;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only marker annotation standing in for an application-level
 * {@code @Expression}-style method annotation. It exists solely so the oracle
 * test can place a {@code M:} entry in an AOT index file and assert it is
 * served through {@code getMethodsWithAnnotation}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestIndexed {
}
