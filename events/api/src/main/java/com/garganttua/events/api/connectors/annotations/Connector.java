package com.garganttua.events.api.connectors.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Qualifier;

import com.garganttua.core.reflection.annotations.Indexed;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Marks an {@code IConnector} implementation as an auto-detectable event connector and a
 * dependency-injection qualifier.
 *
 * <p>
 * The annotated class must implement {@code com.garganttua.events.api.IConnector}. During
 * auto-detection, the events {@code EventsBuilder} discovers classes annotated with
 * {@code @Connector} (via {@code IReflection.getClassesWithAnnotation}), and registers each one
 * into the connector registry under the key {@code type:version}. The engine later resolves a
 * connector instance from that registry by {@code type} and {@code version} when initializing a
 * route's subscriptions.
 * </p>
 *
 * <p>
 * It is additionally meta-annotated with {@link Qualifier} (JSR-330), so connectors are registered
 * into the garganttua-injection context as qualified beans (named {@code connector:type:version})
 * and resolvable via {@code IInjectionContext.queryBean(...)} using {@code @Connector} as a
 * {@code BeanReference} qualifier. The engine resolves connectors as beans first, falling back to
 * the reflective registry path when no injection context is wired.
 * </p>
 *
 * <p>
 * This mirrors the platform's other annotation-driven auto-detection markers
 * ({@code @BeanProvider} in injection, {@code @Serializer}/{@code @Protocol}/{@code @Entity} in
 * api): it is meta-annotated with {@link Indexed} so the annotation processor emits an index file
 * under {@code META-INF/garganttua/index/}, and with {@link Reflected} so the AOT reflection layer
 * retains the type. Simply having a connector JAR on the classpath therefore auto-registers it
 * ("batteries-included"), with no manual wiring required.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * @Connector(type = "kafka")
 * @Reflected
 * public class KafkaConnector extends AbstractLifecycle implements IConnector {
 *     // ...
 * }
 * }</pre>
 *
 * @see com.garganttua.core.injection.annotations.BeanProvider
 */
@Indexed
@Reflected
@Qualifier
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Connector {

    /**
     * The connector type identifier (e.g. {@code "kafka"}, {@code "bus"}, {@code "mail"}).
     * Forms the first segment of the {@code type:version} registry key.
     *
     * @return the connector type
     */
    String type();

    /**
     * The connector version. Forms the second segment of the {@code type:version} registry key.
     *
     * @return the connector version, defaulting to {@code "1.0"}
     */
    String version() default "1.0";

    /**
     * An optional human-readable connector name. Currently informational; the registry key is
     * derived from {@link #type()} and {@link #version()}.
     *
     * @return the connector name, empty by default
     */
    String name() default "";

}
