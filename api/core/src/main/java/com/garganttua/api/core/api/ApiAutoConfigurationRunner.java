package com.garganttua.api.core.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.GarganttuaConfig;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.core.observability.Logger;

/**
 * Discovers and applies every {@link IApiAutoConfiguration} on the classpath to an
 * {@link IApiBuilder} during the bootstrap CONFIGURATION stage, so persistence and
 * transport (default DAO, default HTTP interface, anonymous access, …) are wired with
 * no application-specific entry point.
 *
 * <p>Implementations are discovered via {@link ServiceLoader}, sorted by
 * {@link IApiAutoConfiguration#order()} (persistence {@code 0}, transport {@code 100}),
 * and applied to a single {@link AutoConfigurationContext} backed by the builder and a
 * {@link GarganttuaConfig} read from {@code application.yaml}/{@code .properties}. Any
 * resources an auto-config opens (e.g. a {@code MongoClient}) are returned so the built
 * {@code IApi} can adopt them into its own lifecycle and close them on stop.
 *
 * @since 3.0.0-ALPHA04
 */
final class ApiAutoConfigurationRunner {

	private static final Logger log = Logger.getLogger(ApiAutoConfigurationRunner.class);

	private ApiAutoConfigurationRunner() {
	}

	/**
	 * Applies all discovered auto-configurations to {@code apiBuilder} and returns the
	 * closeables they opened.
	 *
	 * @param apiBuilder the builder being configured
	 * @return the resources opened by the auto-configurations (to be closed on shutdown)
	 * @throws ApiException if an auto-configuration fails
	 */
	static List<AutoCloseable> apply(IApiBuilder apiBuilder) throws ApiException {
		IConfig config = GarganttuaConfig.load();
		applyTopLevelSettings(apiBuilder, config);
		AutoConfigurationContext context = new AutoConfigurationContext(apiBuilder, config);

		List<IApiAutoConfiguration> autoConfigs = new ArrayList<>();
		ServiceLoader.load(IApiAutoConfiguration.class).forEach(autoConfigs::add);
		autoConfigs.sort(Comparator.comparingInt(IApiAutoConfiguration::order));

		for (IApiAutoConfiguration autoConfig : autoConfigs) {
			log.debug("Applying IApiAutoConfiguration {} (order {})",
					autoConfig.getClass().getName(), autoConfig.order());
			autoConfig.apply(context);
		}
		return context.resources();
	}

	/**
	 * Applies the top-level {@code api.*} settings from the external config to the builder
	 * (mirroring what the legacy {@code GarganttuaApplication} shim did), so the neutral
	 * core runner boots an equally-configured API. Packages declared via {@code api.packages}
	 * are merged into the builder's scan surface (the bootstrap also propagates its own
	 * {@code withPackages(...)}; both are additive).
	 */
	private static void applyTopLevelSettings(IApiBuilder apiBuilder, IConfig config) throws ApiException {
		Optional<String[]> packages = config.getStringList("api.packages");
		if (packages.isPresent()) {
			apiBuilder.packages(packages.get());
		}
		Optional<Boolean> multiTenant = config.getBoolean("api.multiTenant");
		if (multiTenant.isPresent()) {
			apiBuilder.multiTenant(multiTenant.get());
		}
		config.getString("api.superTenantId").ifPresent(apiBuilder::superTenantId);
		Optional<Boolean> autoCreate = config.getBoolean("api.superTenantAutoCreate");
		if (autoCreate.isPresent()) {
			apiBuilder.superTenantAutoCreate(autoCreate.get());
		}
	}
}
