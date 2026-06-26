package com.garganttua.events.api.dsl;

import com.garganttua.events.api.exceptions.EventsException;

/**
 * SPI letting an application contribute its events topology (asset, cluster contexts,
 * connectors, routes) to the <b>shared</b> garganttua-events bootstrap.
 *
 * <p>
 * The garganttua {@code Bootstrap} dedups builders by class name and requires every builder
 * to be registered <i>before</i> {@code load()} — registration is closed by the time the
 * CONFIGURATION stage (and {@code IApiAutoConfiguration}) runs. The clean seam is therefore the
 * events builder factory itself: it is invoked during the bootstrap REGISTRATION sweep and the
 * builder it returns is the very one the bootstrap builds and publishes as the {@code IEvents}
 * bean. The factory applies every discovered {@code IEventsTopologyContributor} to that builder,
 * so an application supplies a configured topology without launching a second {@code Bootstrap}
 * and without a static mutable holder.
 * </p>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Implement this interface, populating the supplied {@link IEventsBuilder} in
 *       {@link #contribute(IEventsBuilder)} via its topology DSL ({@code asset(...)},
 *       {@code context(...)}, {@code connector(...)}, routes).</li>
 *   <li>Register the implementation in
 *       {@code META-INF/services/com.garganttua.events.api.dsl.IEventsTopologyContributor}.</li>
 * </ol>
 *
 * <p>
 * Multiple contributors <b>compose</b>: each adds to the same shared builder, applied in
 * ascending {@link #order()}. A contributor failure is isolated by the factory (logged and
 * skipped) so a misbehaving application never aborts the shared bootstrap — the events subsystem
 * stays isolated from the rest of the platform.
 * </p>
 *
 * @since 3.0.0-ALPHA04
 */
@FunctionalInterface
public interface IEventsTopologyContributor {

    /**
     * Relative ordering across contributors; lower values run first. Defaults to {@code 0}.
     *
     * @return the application's contribution order
     */
    default int order() {
        return 0;
    }

    /**
     * Configures the shared events builder with this application's topology (asset, cluster
     * contexts, connectors, routes). Called once, at bootstrap REGISTRATION, on the same builder
     * that the bootstrap then builds and publishes as the {@code IEvents} bean.
     *
     * @param events the shared events builder to populate; never {@code null}
     * @throws EventsException if the topology cannot be contributed; the factory isolates and
     *                         logs the failure so it does not abort the shared bootstrap
     */
    void contribute(IEventsBuilder events) throws EventsException;
}
