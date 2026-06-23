package com.garganttua.core.runtime.dsl;

import java.util.HashSet;
import java.util.Set;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.dsl.AbstractAutomaticBuilder;
import com.garganttua.core.dsl.DslException;
import com.garganttua.core.nativve.INativeBuilder;
import com.garganttua.core.nativve.IReflectionConfigurationEntryBuilder;
import com.garganttua.core.reflection.IReflectionUsageReporter;
import com.garganttua.core.reflection.annotations.ReflectedBuilder;
import com.garganttua.core.nativve.image.config.reflection.ReflectConfigEntryBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.runtime.MethodBinderExpression;
import com.garganttua.core.runtime.Runtime;
import com.garganttua.core.runtime.RuntimeContext;
import com.garganttua.core.runtime.RuntimeContextFactory;
import com.garganttua.core.runtime.RuntimeExpressionContext;
import com.garganttua.core.runtime.RuntimeProcess;
import com.garganttua.core.runtime.RuntimeResult;
import com.garganttua.core.runtime.RuntimeStep;
import com.garganttua.core.runtime.RuntimeStepCatch;
import com.garganttua.core.runtime.RuntimeStepExecutionTools;
import com.garganttua.core.runtime.RuntimeStepFallbackBinder;
import com.garganttua.core.runtime.RuntimeStepMethodBinder;
import com.garganttua.core.runtime.RuntimeStepOnException;
import com.garganttua.core.runtime.RuntimesRegistry;
import com.garganttua.core.runtime.resolver.CodeElementResolver;
import com.garganttua.core.runtime.resolver.ContextElementResolver;
import com.garganttua.core.runtime.resolver.ExceptionElementResolver;
import com.garganttua.core.runtime.resolver.ExceptionMessageElementResolver;
import com.garganttua.core.runtime.resolver.InputElementResolver;
import com.garganttua.core.runtime.resolver.VariableElementResolver;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Native image configuration builder for the garganttua-runtime module.
 *
 * <p>
 * This builder provides GraalVM native image reflection configuration for all
 * runtime-related classes including:
 * </p>
 * <ul>
 *   <li>Core runtime classes (Runtime, RuntimeContext, RuntimeStep, etc.)</li>
 *   <li>Element resolvers for parameter injection</li>
 *   <li>DSL builder classes</li>
 *   <li>Runtime execution tools</li>
 * </ul>
 *
 * @since 2.0.0-ALPHA01
 */
@ReflectedBuilder
@Reflected
public class RuntimeNativeConfigurationBuilder
        extends AbstractAutomaticBuilder<RuntimeNativeConfigurationBuilder, IReflectionUsageReporter>
        implements INativeBuilder<RuntimeNativeConfigurationBuilder, IReflectionUsageReporter> {
    private static final Logger log = Logger.getLogger(RuntimeNativeConfigurationBuilder.class);

    private final Set<String> packages = new HashSet<>();

    /**
     * Adds several packages to scan for native reflection configuration.
     *
     * @param packageNames the package names to add
     * @return this builder for chaining
     */
    @Override
    public RuntimeNativeConfigurationBuilder withPackages(String[] packageNames) {
        log.trace("Adding {} packages to runtime native configuration", packageNames.length);
        this.packages.addAll(Set.of(packageNames));
        return this;
    }

    /**
     * Adds a single package to scan for native reflection configuration.
     *
     * @param packageName the package name to add
     * @return this builder for chaining
     */
    @Override
    public RuntimeNativeConfigurationBuilder withPackage(String packageName) {
        log.trace("Adding package to runtime native configuration: {}", packageName);
        this.packages.add(packageName);
        return this;
    }

    /**
     * Returns the packages registered for native reflection configuration.
     *
     * @return the configured package names
     */
    @Override
    public String[] getPackages() {
        return this.packages.toArray(new String[0]);
    }

    @Override
    protected IReflectionUsageReporter doBuild() throws DslException {
        log.trace("Building runtime native configuration");

        Set<IReflectionConfigurationEntryBuilder> entries = new HashSet<>();

        // Core runtime classes
        registerFullReflection(entries, Runtime.class, RuntimeContext.class, RuntimeContextFactory.class,
                RuntimeResult.class, RuntimeExpressionContext.class, RuntimeProcess.class, RuntimesRegistry.class);

        // Step-related classes
        registerFullReflection(entries, RuntimeStep.class, RuntimeStepMethodBinder.class,
                RuntimeStepFallbackBinder.class, RuntimeStepExecutionTools.class, RuntimeStepCatch.class,
                RuntimeStepOnException.class, MethodBinderExpression.class);

        // Element resolvers (registered via @Resolver annotation)
        registerFullReflection(entries, InputElementResolver.class, VariableElementResolver.class,
                ContextElementResolver.class, CodeElementResolver.class, ExceptionElementResolver.class,
                ExceptionMessageElementResolver.class);

        // DSL builders
        registerFullReflection(entries, RuntimeBuilder.class, RuntimesBuilder.class, RuntimeStepBuilder.class,
                RuntimeStepMethodBuilder.class, RuntimeStepFallbackBuilder.class, RuntimeStepCatchBuilder.class,
                RuntimeStepOnExceptionBuilder.class);

        log.debug("Runtime native configuration built with {} entries", entries.size());

        final Set<IReflectionConfigurationEntryBuilder> finalEntries = entries;
        return () -> finalEntries;
    }

    /**
     * Registers each given class for full reflection access (all declared
     * constructors, methods, and fields) by adding an entry to {@code entries}.
     *
     * @param entries the entry set to populate
     * @param classes the classes to register for native reflection
     */
    private static void registerFullReflection(Set<IReflectionConfigurationEntryBuilder> entries,
            Class<?>... classes) {
        for (Class<?> clazz : classes) {
            entries.add(new ReflectConfigEntryBuilder(IClass.getClass(clazz))
                    .queryAllDeclaredConstructors(true)
                    .queryAllDeclaredMethods(true)
                    .allDeclaredFields(true));
        }
    }

    @Override
    protected void doAutoDetection() throws DslException {
        log.trace("Auto-detection not required for runtime native configuration");
    }
}
