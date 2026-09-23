package com.garganttua.core.observability.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IBootstrapBuilderFactory;
import com.garganttua.core.dsl.IBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Cold-start verification that {@link ObservabilityBuilder} is discoverable
 * via the {@code IBootstrapBuilderFactory} SPI. Bootstrap reads the same
 * descriptor in production; here we exercise the descriptor directly so the
 * test stays free of the rest of the Bootstrap orchestration.
 *
 * @since 2.0.0-ALPHA02
 */
@DisplayName("Observability SPI auto-discovery")
class ObservabilityBootstrapSpiTest {

    /**
     * A builder needs a reflection provider, which the bootstrap installs in a real application.
     * This suite calls the factory directly, so it installs one itself — without it the test only
     * passed when another test class in the same fork happened to run first, which is how it broke
     * on CI the day the class order changed.
     */
    @BeforeAll
    static void wireReflection() throws DslException {
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider(), 0)
                .withScanner(new ReflectionsAnnotationScanner(), 0)
                .build());
    }


    @Test
    @DisplayName("META-INF/services/IBootstrapBuilderFactory yields an ObservabilityBuilder")
    void spiYieldsObservabilityBuilder() {
        boolean found = false;
        for (IBootstrapBuilderFactory factory : ServiceLoader.load(IBootstrapBuilderFactory.class)) {
            if (!(factory instanceof ObservabilityBuilderFactory)) {
                continue;
            }
            found = true;

            IBuilder<?> builder = assertSucceeds(factory);
            assertNotNull(builder, "factory.create() returned null");
            assertTrue(builder instanceof ObservabilityBuilder,
                    "factory should produce an ObservabilityBuilder, got " + builder.getClass());
        }
        assertTrue(found,
                "ObservabilityBuilderFactory not visible via ServiceLoader — META-INF/services descriptor missing?");
    }

    @Test
    @DisplayName("Each call to factory.create() produces a fresh builder")
    void factoryProducesFreshInstances() {
        ObservabilityBuilderFactory factory = new ObservabilityBuilderFactory();
        IBuilder<?> a = assertSucceeds(factory);
        IBuilder<?> b = assertSucceeds(factory);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(false, a == b, "factory must not return a singleton");
    }

    private static IBuilder<?> assertSucceeds(IBootstrapBuilderFactory factory) {
        try {
            return factory.create();
        } catch (Exception e) {
            throw new AssertionError("factory.create() should not throw, got " + e, e);
        }
    }
}
