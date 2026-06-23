package com.garganttua.core.bootstrap.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Verifies that {@link IBuiltRegistry#request(IClass)} and
 * {@link IBuiltRegistry#requestAll(IClass)} match built objects by
 * <b>assignability</b> (honoring the interface javadoc), not by exact class —
 * so {@code request(IClass.getClass(SomeInterface.class))} resolves an engine
 * registered under its concrete implementation class.
 */
@DisplayName("BuiltRegistry assignability Tests")
class BuiltRegistryAssignabilityTest {

    private static IReflection reflection;

    @BeforeAll
    static void setUpReflection() throws Exception {
        reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .build();
        IClass.setReflection(reflection);
    }

    @AfterAll
    static void tearDownReflection() {
        IClass.setReflection(null);
    }

    /** Marker interface a built object implements (mirrors IApi / IEvents). */
    interface IEngineMarker {
        String name();
    }

    /** Concrete impl registered in the registry under its concrete class. */
    static final class EngineImpl implements IEngineMarker {
        @Override
        public String name() {
            return "engine";
        }
    }

    /** Builder producing an interface-implementing object. */
    static final class EngineBuilder implements IBuilder<IEngineMarker> {
        @Override
        public IEngineMarker build() throws DslException {
            return new EngineImpl();
        }
    }

    @Test
    @DisplayName("request(IClass<Interface>) returns the assignable impl")
    void requestMatchesByAssignability() throws DslException {
        IBuiltRegistry registry = new Bootstrap()
                .withBuilder(new EngineBuilder())
                .build();

        IEngineMarker found = registry.request(IClass.getClass(IEngineMarker.class)).orElseThrow();
        assertSame(EngineImpl.class, found.getClass());
        assertEquals("engine", found.name());

        // exact concrete-class lookup still works too
        assertTrue(registry.request(IClass.getClass(EngineImpl.class)).isPresent());
        // an unrelated interface does not match
        assertFalse(registry.request(IClass.getClass(Runnable.class)).isPresent());
    }

    @Test
    @DisplayName("requestAll(IClass<Interface>) returns the assignable impl in a list")
    void requestAllMatchesByAssignability() throws DslException {
        IBuiltRegistry registry = new Bootstrap()
                .withBuilder(new EngineBuilder())
                .build();

        List<IEngineMarker> all = registry.requestAll(IClass.getClass(IEngineMarker.class));
        assertEquals(1, all.size());
        assertSame(EngineImpl.class, all.get(0).getClass());

        assertTrue(registry.requestAll(IClass.getClass(Runnable.class)).isEmpty());
    }
}
