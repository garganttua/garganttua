package com.garganttua.events.api.dsl;

import com.garganttua.core.dsl.dependency.IDependentBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;
import com.garganttua.events.api.IConnector;
import com.garganttua.events.api.IEvents;

public interface IEventsBuilder extends IDependentBuilder<IEventsBuilder, IEvents> {

	IEventsBuilder asset(String assetId);

	IEventsBuilder withPackage(String pkg);

	IContextBuilder context(String tenantId, String clusterId);

	IEventsBuilder source(String type, String configuration);

	/**
	 * Registers a connector by a core bean-reference URL.
	 *
	 * <p>
	 * The string is interpreted as a garganttua-injection bean reference following the grammar
	 * {@code [provider::]class[!strategy][#name][@qualifier...]}. As a convenience, a single-colon
	 * scheme ({@code provider:rest}, where {@code rest} does not itself start with {@code :}) is
	 * normalized to the core double-colon provider form before parsing — so {@code supplier:com.foo.X}
	 * is treated as {@code supplier::com.foo.X}. The referenced class is resolved to an
	 * {@code IClass} and registered as a (prototype) connector bean, honoring the parsed provider,
	 * name and strategy where present and adding the {@code @Connector} qualifier if absent.
	 * </p>
	 *
	 * <p>Accepted examples:</p>
	 * <ul>
	 *   <li>{@code com.foo.MyConnector} — bare class</li>
	 *   <li>{@code supplier::com.foo.MyConnector} — class via the {@code supplier} provider</li>
	 *   <li>{@code myProvider::com.foo.X#name} — class via a named provider with an explicit name</li>
	 * </ul>
	 *
	 * @param url the connector bean-reference URL
	 * @return this builder for chaining
	 */
	IEventsBuilder connector(String url);

	/**
	 * Registers a connector by its annotated {@code IClass}.
	 *
	 * <p>
	 * The class must carry {@code @Connector}; its {@code type}/{@code version} form the registry
	 * key and the resulting prototype bean name {@code connector:type:version}.
	 * </p>
	 *
	 * @param connectorClass the {@code @Connector}-annotated connector class
	 * @return this builder for chaining
	 */
	IEventsBuilder connector(IClass<? extends IConnector> connectorClass);

	/**
	 * Registers a connector built lazily by a supplier builder.
	 *
	 * <p>
	 * The supplier is built and supplied at bean-registration time; the supplied instance is
	 * registered as a singleton connector bean (its class must carry {@code @Connector}).
	 * </p>
	 *
	 * @param connectorBuilder the supplier builder yielding the connector instance
	 * @return this builder for chaining
	 */
	IEventsBuilder connector(ISupplierBuilder<IConnector, ISupplier<IConnector>> connectorBuilder);

	/**
	 * Registers an already-instantiated connector.
	 *
	 * <p>
	 * The instance's class must carry {@code @Connector}; it is registered as a singleton connector
	 * bean named {@code connector:type:version}.
	 * </p>
	 *
	 * @param connector the connector instance
	 * @return this builder for chaining
	 */
	IEventsBuilder connector(IConnector connector);

}
