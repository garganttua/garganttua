package com.garganttua.api.starter.javalin;

import java.util.List;

import com.garganttua.api.binding.javalin.JavalinInterface;
import com.garganttua.api.binding.javalin.JavalinProtocol;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;

/**
 * Auto-configures the Javalin HTTP transport: reads {@code server.port}
 * (default {@link JavalinInterface#DEFAULT_PORT}), creates a single shared
 * {@link JavalinInterface} registered as the default interface for every domain
 * (its {@code handle(domain)} is per-domain and {@code onStart()} is idempotent,
 * so one server serves them all), and registers the {@link JavalinProtocol}
 * adapter (which is not {@code @Protocol}-annotated, hence not auto-detected).
 *
 * <p>{@code server.trustedProxies} — addresses or CIDR ranges, comma-separated — declares the reverse
 * proxies whose {@code X-Forwarded-For} is believed when an operation's origin is recorded
 * ({@link JavalinProtocol#trustedProxies}). Unset: the address of the connection, whatever the headers.
 *
 * <p>Runs at {@code order() = 100} (transport after persistence). Discovered via
 * {@link java.util.ServiceLoader}.
 */
public final class JavalinAutoConfiguration implements IApiAutoConfiguration {

	/** The reverse proxies whose {@code X-Forwarded-For} is believed: addresses or CIDR ranges. */
	static final String TRUSTED_PROXIES = "server.trustedProxies";

	@Override
	public int order() {
		return 100;
	}

	@Override
	public void apply(AutoConfigurationContext context) throws ApiException {
		int port = context.config().getInt("server.port").orElse(JavalinInterface.DEFAULT_PORT);

		JavalinInterface shared = new JavalinInterface(port);
		context.registerDefaultInterface(FixedSupplierBuilder.of(shared));

		JavalinProtocol protocol = new JavalinProtocol();
		context.config().getStringList(TRUSTED_PROXIES).ifPresent(proxies -> protocol.trustedProxies(List.of(proxies)));
		context.apiBuilder().protocol(protocol);
	}
}
