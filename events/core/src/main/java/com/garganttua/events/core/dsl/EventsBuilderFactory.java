package com.garganttua.events.core.dsl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IBootstrapBuilderFactory;
import com.garganttua.core.dsl.IBuilder;
import com.garganttua.core.observability.Logger;
import com.garganttua.events.api.dsl.IEventsBuilder;
import com.garganttua.events.api.dsl.IEventsTopologyContributor;

/**
 * SPI factory that lets {@code Bootstrap.autoDetect(true)} discover and register
 * an {@link EventsBuilder} without the caller having to declare it via
 * {@code .withBuilder(...)} explicitly — exactly like garganttua-api's
 * {@code ApiBuilderFactory}.
 *
 * <p>
 * Listed in
 * {@code META-INF/services/com.garganttua.core.dsl.IBootstrapBuilderFactory}.
 * On the classpath, garganttua-events therefore wires itself into the bootstrap
 * cold start; its dependencies ({@code IInjectionContextBuilder},
 * {@code IExpressionContextBuilder}) are resolved automatically from the reactor.
 * </p>
 *
 * <p>
 * {@link #create()} is called during the bootstrap REGISTRATION sweep and the builder it returns
 * is the very one the bootstrap builds and publishes as the {@code IEvents} bean. Before returning,
 * it applies every {@link IEventsTopologyContributor} discovered through the {@link ServiceLoader},
 * in ascending {@link IEventsTopologyContributor#order() order}, exception-isolated per contributor.
 * An application therefore contributes a configured topology to the shared bootstrap without
 * launching a second {@code Bootstrap}; the default empty builder is returned when no contributor
 * is present.
 * </p>
 *
 * @since 3.0.0-ALPHA04
 */
public final class EventsBuilderFactory implements IBootstrapBuilderFactory {

    private static final Logger log = Logger.getLogger(EventsBuilderFactory.class);

    @Override
    public IBuilder<?> create() throws DslException {
        EventsBuilder builder = (EventsBuilder) EventsBuilder.builder();
        applyContributors(builder);
        return builder;
    }

    /**
     * Applies every {@link IEventsTopologyContributor} discovered on the classpath to the shared
     * {@code builder}, ordered by {@link IEventsTopologyContributor#order()} and isolated per
     * contributor: a failing contributor is logged and skipped so it never aborts the bootstrap.
     *
     * @param builder the shared events builder to populate
     */
    private static void applyContributors(IEventsBuilder builder) {
        List<IEventsTopologyContributor> contributors = new ArrayList<>();
        ServiceLoader.load(IEventsTopologyContributor.class).forEach(contributors::add);
        contributors.sort(Comparator.comparingInt(IEventsTopologyContributor::order));
        int applied = 0;
        for (IEventsTopologyContributor contributor : contributors) {
            try {
                contributor.contribute(builder);
                applied++;
            } catch (Exception e) {
                log.warn("Events topology contributor {} failed and was skipped: {}",
                        contributor.getClass().getName(), e.getMessage());
            }
        }
        log.info("Applied {} events topology contributor(s) to the shared bootstrap", applied);
    }
}
