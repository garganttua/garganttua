package com.garganttua.api.starter;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.core.application.GarganttuaApplicationException;
import com.garganttua.core.bootstrap.dsl.IBuiltRegistry;
import com.garganttua.core.reflection.IClass;

/**
 * Thin, Spring-Boot-style entry point that boots a fully wired, running API.
 *
 * <pre>{@code
 * public final class MyApp {
 *     public static void main(String[] args) {
 *         GarganttuaApplication.run(MyApp.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>This shim now delegates the whole bootstrap dance to the engine-neutral core
 * runner {@link com.garganttua.core.application.GarganttuaApplication} and simply
 * pulls the {@link IApi} out of the assembled registry. garganttua-api configures
 * itself during the bootstrap CONFIGURATION stage — {@code ApiBuilder} runs every
 * {@code IApiAutoConfiguration} on the classpath (persistence/transport) and reads
 * the externalized {@code api.*} config — so no api-specific wiring is needed here.</p>
 *
 * <p>Domains, DAOs and HTTP interfaces are discovered from {@code @Entity}/{@code @Dto}
 * annotations and from the starters on the classpath, so a typical app writes no
 * {@code ApiBuilder} DSL.</p>
 *
 * @deprecated prefer the engine-neutral
 *     {@link com.garganttua.core.application.GarganttuaApplication#run(Class, String...)},
 *     which boots api and events together and returns the full
 *     {@link IBuiltRegistry}; pull the {@code IApi} with
 *     {@code registry.request(IClass.getClass(IApi.class))}. This shim is kept for
 *     source compatibility with apps that depend on a returned {@code IApi}.
 */
@Deprecated(since = "3.0.0-ALPHA04")
public final class GarganttuaApplication {

	private GarganttuaApplication() {
	}

	/**
	 * Builds, initializes and starts the API via the neutral core runner, then returns
	 * it without blocking. The core runner installs a JVM shutdown hook that stops every
	 * {@code ILifecycle} (the {@code IApi} included, which closes its auto-configuration
	 * resources on stop); a second {@code onStop()} here would be a no-op, so none is added.
	 *
	 * @param source the application's main class; its package is scanned by default
	 * @param args   process arguments (reserved for parity)
	 * @return the running {@link IApi}
	 * @throws ApiException if the bootstrap produced no {@code IApi}
	 */
	public static IApi run(Class<?> source, String... args) {
		try {
			IBuiltRegistry registry =
					com.garganttua.core.application.GarganttuaApplication.run(source, args);
			return registry.request(IClass.getClass(IApi.class))
					.orElseThrow(() -> new ApiException(
							"Bootstrap produced no IApi — is garganttua-api-core on the classpath?"));
		} catch (GarganttuaApplicationException e) {
			throw new ApiException("Bootstrap failed to assemble the API: " + e.getMessage(), e);
		}
	}

	/** Same as {@link #run} but blocks the calling thread until JVM shutdown. */
	public static void runAndWait(Class<?> source, String... args) {
		run(source, args);
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
