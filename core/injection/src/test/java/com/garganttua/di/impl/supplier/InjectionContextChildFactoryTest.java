package com.garganttua.di.impl.supplier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.injection.DiException;
import com.garganttua.core.injection.IInjectionChildContextFactory;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.context.InjectionContext;
import com.garganttua.core.injection.dummies.DummyChildContext;
import com.garganttua.core.lifecycle.LifecycleException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;

/**
 * Child-context factory resolution in {@link InjectionContext#newChildContext}. Guards the
 * native-image fix: {@link InjectionContext} used to identify a factory's produced context type
 * purely by reflecting on its generic interface signature, which GraalVM closed-world erases —
 * making runtime child contexts fail to open with "No child context factory registered". A factory
 * now declares its type via {@link IInjectionChildContextFactory#contextType()}; these tests use a
 * <b>raw</b> factory (no parameterized signature, exactly like the erased native case) to prove the
 * declared type resolves it and that omitting it reproduces the failure.
 */
public class InjectionContextChildFactoryTest {

    private IInjectionContext ctx;

    @BeforeEach
    void setUp() throws DslException, LifecycleException {
        IReflectionBuilder rb = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner());
        rb.build();
        ctx = InjectionContext.builder().provide(rb).withPackage("com.garganttua")
                .autoDetect(true)
                .build();
        ctx.onInit().onStart();
    }

    /**
     * Implements the <b>raw</b> factory interface, so {@code getClass().getGenericInterfaces()}
     * yields no parameterized type — the same situation as native-image erasure. It can therefore
     * only be matched via the explicit {@link #contextType()} declaration.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class ErasedFactoryWithType implements IInjectionChildContextFactory {
        @Override
        public IInjectionContext createChildContext(IInjectionContext parent, Object... args) {
            return new DummyChildContext();
        }

        @Override
        public IClass<? extends IInjectionContext> contextType() {
            return IClass.getClass(DummyChildContext.class);
        }
    }

    /** Same erased signature but without declaring {@link #contextType()} — the pre-fix failure. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class ErasedFactoryNoType implements IInjectionChildContextFactory {
        @Override
        public IInjectionContext createChildContext(IInjectionContext parent, Object... args) {
            return new DummyChildContext();
        }
    }

    @Test
    @DisplayName("declared contextType() resolves a factory whose generic signature is erased")
    void declaredContextTypeResolvesErasedFactory() throws DiException {
        ctx.registerChildContextFactory(new ErasedFactoryWithType());
        IInjectionContext child = ctx.newChildContext(IClass.getClass(DummyChildContext.class));
        assertNotNull(child);
        assertInstanceOf(DummyChildContext.class, child);
    }

    @Test
    @DisplayName("erased factory without contextType() reproduces the native failure")
    void erasedFactoryWithoutContextTypeIsNotResolved() {
        ctx.registerChildContextFactory(new ErasedFactoryNoType());
        DiException ex = assertThrows(DiException.class,
                () -> ctx.newChildContext(IClass.getClass(DummyChildContext.class)));
        assertTrue(ex.getMessage().contains("No child context factory registered"));
    }
}
