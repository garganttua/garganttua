package com.garganttua.core.reflection.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Methods invoked by name are resolved once per call shape — receiver type, name, return type, argument types — and
 * kept. Each shape must still reach the method a fresh resolution picks.
 */
@DisplayName("Method calls by name — resolved once per call shape")
class MethodCallCacheTest {

    private IReflection reflection;

    public static class Greeter {
        public String greet(String name) {
            return "hello " + name;
        }

        public String greet(Integer count) {
            return "hello x" + count;
        }
    }

    public static class LoudGreeter extends Greeter {
        @Override
        public String greet(String name) {
            return "HELLO " + name;
        }
    }

    @BeforeEach
    void setUp() throws DslException {
        this.reflection = ReflectionBuilder.builder().withProvider(new RuntimeReflectionProvider()).build();
    }

    @Test
    @DisplayName("the overload follows the argument types, call after call")
    void overloads() throws ReflectionException {
        IClass<String> string = IClass.getClass(String.class);
        Greeter greeter = new Greeter();
        for (int i = 0; i < 3; i++) {
            assertEquals("hello ada", this.reflection.invokeMethod(greeter, "greet", string, "ada"));
            assertEquals("hello x" + i, this.reflection.invokeMethod(greeter, "greet", string, i));
        }
    }

    @Test
    @DisplayName("a subclass receiver gets its own override, the parent keeps its method")
    void subclassReceiver() throws ReflectionException {
        IClass<String> string = IClass.getClass(String.class);
        assertEquals("HELLO ada", this.reflection.invokeMethod(new LoudGreeter(), "greet", string, "ada"));
        assertEquals("hello ada", this.reflection.invokeMethod(new Greeter(), "greet", string, "ada"));
        assertEquals("HELLO bob", this.reflection.invokeMethod(new LoudGreeter(), "greet", string, "bob"));
    }

    @Test
    @DisplayName("a missing method fails at every call — a failure is never kept")
    void missingMethodFailsEveryTime() {
        IClass<String> string = IClass.getClass(String.class);
        Greeter greeter = new Greeter();
        assertThrows(ReflectionException.class, () -> this.reflection.invokeMethod(greeter, "shout", string, "ada"));
        assertThrows(ReflectionException.class, () -> this.reflection.invokeMethod(greeter, "shout", string, "ada"));
    }
}
