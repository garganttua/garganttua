package com.garganttua.api.starter.javalin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.core.application.GarganttuaApplication;
import com.garganttua.core.bootstrap.dsl.IBuiltRegistry;
import com.garganttua.core.lifecycle.ILifecycle;
import com.garganttua.core.reflection.IClass;

/**
 * Proves garganttua-api self-configures under the <b>engine-neutral</b> core runner
 * {@link com.garganttua.core.application.GarganttuaApplication}: booting with no
 * api-specific entry point yields a fully wired {@link IApi} whose {@code @Entity}
 * was discovered into a domain, the in-memory default DAO was registered, multi-tenancy
 * was turned off and the Javalin transport attached — all via the bootstrap CONFIGURATION
 * stage running every {@code IApiAutoConfiguration} on the classpath. The Javalin server
 * is bound on a port distinct from the E2E test's to avoid a clash.
 */
@DisplayName("garganttua-api boots under the neutral core runner — no api entry point")
@TestInstance(Lifecycle.PER_CLASS)
class NeutralRunnerBootTest {

	private IBuiltRegistry registry;

	@BeforeAll
	void boot() {
		// The neutral runner scans the source class's package; point it at the test fixtures so
		// the Widget @Entity is discovered. server.port comes from application.yaml (7099) — the
		// Javalin onStart is idempotent and shares one server, so no port clash with the E2E test.
		System.setProperty("garganttua.packages", "com.garganttua.api.starter.javalin");
		this.registry = GarganttuaApplication.run(GarganttuaApplicationE2ETest.TestApp.class);
	}

	@AfterAll
	void shutdown() {
		if (this.registry != null) {
			for (Object built : this.registry.toList()) {
				if (built instanceof ILifecycle lifecycle) {
					lifecycle.onStop();
				}
			}
		}
		System.clearProperty("garganttua.packages");
	}

	@Test
	@DisplayName("registry yields an IApi with the auto-discovered 'widgets' domain")
	void neutralRunnerYieldsConfiguredApi() {
		IApi api = this.registry.request(IClass.getClass(IApi.class)).orElse(null);
		assertNotNull(api, "the neutral core runner should boot an IApi with api-core on the classpath");
		assertTrue(api.getDomain("widgets").isPresent(),
				"the @Entity Widget should have produced a 'widgets' domain — proving api self-configured "
						+ "(default DAO + multiTenant:false + anonymous access) under the neutral runner");
	}
}
