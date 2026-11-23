package com.garganttua.core.configuration.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.garganttua.core.dsl.IBuilder;
import com.garganttua.core.reflection.JdkReflectionProvider;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;

/**
 * Verifies the bootstrap CONFIGURATION-stage contributor: it discovers a classpath
 * configuration file ({@code garganttua/config/demo.json}, shebang {@code $module:demo}),
 * matches it to the {@code @ConfigurableBuilder("demo")} instance among the supplied
 * builders, and applies it — leaving non-matching builders untouched.
 */
class BootstrapConfigurationContributorTest {

    /**
     * The contributor resolves the configuration formats through {@code IClass}: this test installs its own
     * reflection, instead of relying on a test that happened to run before it in the same JVM.
     */
    @BeforeAll
    static void setUpReflection() throws Exception {
        ReflectionBuilder.builder()
            .withProvider(new JdkReflectionProvider())
            .build();
    }

    @Test
    void appliesDiscoveredConfigToTheMatchingConfigurableBuilder() {
        DemoConfigurableBuilder demo = new DemoConfigurableBuilder();
        UnrelatedBuilder other = new UnrelatedBuilder();

        new BootstrapConfigurationContributor().contribute(List.of(demo, other));

        // demo.json -> name=booted, count=3, enabled=true
        assertEquals("booted:3:true", demo.build());
        // a builder without @ConfigurableBuilder is left untouched
        assertEquals("untouched", other.build());
    }

    @Test
    void noMatchingBuilderIsANoOp() {
        UnrelatedBuilder other = new UnrelatedBuilder();
        new BootstrapConfigurationContributor().contribute(List.of(other));
        assertEquals("untouched", other.build());
    }

    /** A builder with no {@code @ConfigurableBuilder} marker — must never be configured. */
    static final class UnrelatedBuilder implements IBuilder<String> {
        @Override
        public String build() {
            return "untouched";
        }
    }
}
