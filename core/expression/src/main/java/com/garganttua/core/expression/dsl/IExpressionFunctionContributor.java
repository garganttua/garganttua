package com.garganttua.core.expression.dsl;

import java.util.Collection;

/**
 * SPI letting an application make its own {@code @Expression}-annotated provider classes
 * resolvable in <b>every</b> expression context (scripts, workflows, events route stages),
 * independent of the runtime / AOT package scanner.
 *
 * <p>
 * The framework's built-in {@code @Expression} functions are registered by fully-qualified
 * class name through a proven, scanner-independent path
 * ({@code Class.forName} → enumerate static {@code @Expression} methods → register into the
 * builder). The package-scan path used for arbitrary consumer packages is unreliable for an
 * application's own packages (runtime-scanner asymmetry; the AOT index is not consulted by that
 * path), so an application otherwise has no way to register its functions. This SPI exposes the
 * same FQN-registration path to applications.
 * </p>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Implement this interface, returning the fully-qualified names of your
 *       {@code @Expression}-annotated provider classes from {@link #functionClassNames()}.</li>
 *   <li>Register the implementation in
 *       {@code META-INF/services/com.garganttua.core.expression.dsl.IExpressionFunctionContributor}.</li>
 * </ol>
 *
 * <p>
 * Modeled on the events {@code IEventsTopologyContributor} pattern: the descriptor survives
 * shading (SPI), and the listed FQNs are loaded reflectively, so a contributor adds no compile-time
 * dependency on the expression module beyond this interface. Each contributor and each named class
 * is applied exception-isolated: a missing or throwing class is logged and skipped, never aborting
 * context construction.
 * </p>
 *
 * @since 3.0.0-ALPHA04
 */
@FunctionalInterface
public interface IExpressionFunctionContributor {

    /**
     * Fully-qualified names of {@code @Expression}-annotated provider classes to register into
     * every expression context. Each named class is resolved via {@code Class.forName} and its
     * static {@code @Expression} methods are registered; a name that does not resolve (e.g. an
     * optional module absent from the classpath) is silently skipped.
     *
     * @return the provider class FQNs to register; never {@code null} (return an empty collection
     *         to contribute nothing)
     */
    Collection<String> functionClassNames();
}
