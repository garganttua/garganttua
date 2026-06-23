package com.garganttua.events.core.dsl;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.dsl.IBootstrapBuilderFactory;
import com.garganttua.core.dsl.IBuilder;

/**
 * SPI factory that lets {@code Bootstrap.autoDetect(true)} discover and register
 * an {@link EventsBuilder} without the caller having to declare it via
 * {@code .withBuilder(...)} explicitly — exactly like garganttua-api's
 * {@code ApiBuilderFactory}.
 *
 * <p>Listed in
 * {@code META-INF/services/com.garganttua.core.dsl.IBootstrapBuilderFactory}.
 * On the classpath, garganttua-events therefore wires itself into the bootstrap
 * cold start; its dependencies ({@code IInjectionContextBuilder},
 * {@code IExpressionContextBuilder}) are resolved automatically from the reactor.
 *
 * @since 3.0.0-ALPHA04
 */
public final class EventsBuilderFactory implements IBootstrapBuilderFactory {

    @Override
    public IBuilder<?> create() throws DslException {
        return (EventsBuilder) EventsBuilder.builder();
    }
}
