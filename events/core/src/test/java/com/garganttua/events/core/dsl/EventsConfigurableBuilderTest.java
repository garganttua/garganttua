package com.garganttua.events.core.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.configuration.binding.BootstrapConfigurationContributor;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Proves {@code EventsBuilder} is configurable from an external config file via its
 * {@code @ConfigurableBuilder("events")} marker: the bootstrap CONFIGURATION-stage
 * contributor discovers {@code garganttua/config/events.json} (shebang
 * {@code "$module":"events"}, {@code asset:"billing-cluster"}) and applies it.
 */
@DisplayName("EventsBuilder — @ConfigurableBuilder(\"events\") population")
class EventsConfigurableBuilderTest {

	@BeforeAll
	static void setUpReflection() {
		// EventsBuilder's static DEPENDENCIES init calls IClass.getClass(...) — install a provider.
		IClass.setReflection(ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.build());
	}

	@AfterAll
	static void tearDownReflection() {
		IClass.setReflection(null);
	}

	@Test
	@DisplayName("events.json (asset:billing-cluster) sets the builder's assetId")
	void configFileSetsAsset() {
		EventsBuilder builder = (EventsBuilder) EventsBuilder.builder();

		new BootstrapConfigurationContributor().contribute(List.of(builder));

		assertEquals("billing-cluster", builder.assetId,
				"events.json should have set the assetId via @ConfigurableBuilder(\"events\")");
	}
}
