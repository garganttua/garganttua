package com.garganttua.api.core.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.core.configuration.binding.BootstrapConfigurationContributor;

/**
 * Proves {@code ApiBuilder} is configurable from an external config file via its
 * {@code @ConfigurableBuilder("api")} marker: the bootstrap CONFIGURATION-stage
 * contributor discovers {@code garganttua/config/api.json} (shebang
 * {@code "$module":"api"}, {@code multiTenant:false}) and applies it to the builder.
 */
@DisplayName("ApiBuilder — @ConfigurableBuilder(\"api\") population")
class ApiConfigurableBuilderTest {

	@Test
	@DisplayName("garganttua/config/api.json (multiTenant:false) flips the builder's multi-tenancy off")
	void configFileFlipsMultiTenant() {
		IApiBuilder builder = ApiBuilder.builder();
		// Multi-tenancy is on by default before any external configuration is applied.
		assertTrue(((ApiBuilder) builder).isMultiTenant(),
				"ApiBuilder should default to multi-tenant enabled");

		new BootstrapConfigurationContributor().contribute(List.of((ApiBuilder) builder));

		assertFalse(((ApiBuilder) builder).isMultiTenant(),
				"api.json (multiTenant:false) should have been applied via @ConfigurableBuilder(\"api\")");
	}
}
