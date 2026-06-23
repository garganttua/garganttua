package com.garganttua.core.aot.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;

import com.garganttua.core.aot.commons.AOTRegistry;

import com.garganttua.core.bootstrap.annotations.Bootstrap;
import com.garganttua.core.condition.dsl.IConditionBuilder;
import com.garganttua.core.crypto.IHash;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.IKeyRealmBuilder;
import com.garganttua.core.dsl.annotations.Scan;
import com.garganttua.core.dsl.dependency.DependsOn;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.annotations.BeanProvider;
import com.garganttua.core.injection.annotations.ChildContext;
import com.garganttua.core.injection.annotations.Fixed;
import com.garganttua.core.injection.annotations.Null;
import com.garganttua.core.injection.annotations.Property;
import com.garganttua.core.injection.annotations.PropertyProvider;
import com.garganttua.core.injection.annotations.Prototype;
import com.garganttua.core.injection.annotations.Provider;
import com.garganttua.core.injection.annotations.Resolver;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.mapper.annotations.FieldMappingRules;
import com.garganttua.core.mapper.annotations.MappingIgnore;
import com.garganttua.core.mapper.annotations.ObjectMappingRule;
import com.garganttua.core.mapper.annotations.ObjectMappingRules;
import com.garganttua.core.mutex.annotations.Mutex;
import com.garganttua.core.mutex.annotations.MutexFactory;
import com.garganttua.core.reflection.annotations.IAnnotationIndex;
import com.garganttua.core.reflection.annotations.Indexed;
import com.garganttua.core.reflection.annotations.Reflected;
import com.garganttua.core.reflection.annotations.ReflectedBuilder;
import com.garganttua.core.runtime.annotations.Catch;
import com.garganttua.core.runtime.annotations.Code;
import com.garganttua.core.runtime.annotations.Condition;
import com.garganttua.core.runtime.annotations.Context;
import com.garganttua.core.runtime.annotations.ExceptionMessage;
import com.garganttua.core.runtime.annotations.FallBack;
import com.garganttua.core.runtime.annotations.Input;
import com.garganttua.core.runtime.annotations.OnException;
import com.garganttua.core.runtime.annotations.Operation;
import com.garganttua.core.runtime.annotations.Output;
import com.garganttua.core.runtime.annotations.RuntimeDefinition;
import com.garganttua.core.runtime.annotations.Step;
import com.garganttua.core.runtime.annotations.Steps;
import com.garganttua.core.runtime.annotations.Synchronized;
import com.garganttua.core.runtime.annotations.Variable;
import com.garganttua.core.runtime.annotations.Variables;
import com.garganttua.core.script.annotations.ScriptDefinition;
import com.garganttua.core.workflow.annotations.WorkflowDefinition;
import com.garganttua.core.injection.IBeanFactory;
import com.garganttua.core.injection.IBeanProvider;
import com.garganttua.core.injection.IBeanQuery;
import com.garganttua.core.injection.IBeanQueryBuilder;
import com.garganttua.core.injection.IBeanSupplier;
import com.garganttua.core.injection.IContextualBeanSupplier;
import com.garganttua.core.injection.IElementResolver;
import com.garganttua.core.injection.IInjectableElementResolver;
import com.garganttua.core.injection.IInjectableElementResolverBuilder;
import com.garganttua.core.injection.IInjectionChildContextFactory;
import com.garganttua.core.injection.IInjectionContext;
import com.garganttua.core.injection.IInjectionContextSupply;
import com.garganttua.core.injection.IPropertyProvider;
import com.garganttua.core.injection.IPropertySupplier;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.observability.dsl.IObservabilityBuilder;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.runtime.IDomainRuntime;
import com.garganttua.core.runtime.IEventRuntime;
import com.garganttua.core.runtime.IRuntime;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.runtime.IRuntimeExecutor;
import com.garganttua.core.runtime.IRuntimeResult;
import com.garganttua.core.runtime.IRuntimeStep;
import com.garganttua.core.runtime.IRuntimeStepCatch;
import com.garganttua.core.runtime.IRuntimeStepFallbackBinder;
import com.garganttua.core.runtime.IRuntimeStepMethodBinder;
import com.garganttua.core.runtime.IRuntimeStepOnException;
import com.garganttua.core.runtime.IRuntimeStepPipe;
import com.garganttua.core.runtime.IRuntimes;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.workflow.dsl.IWorkflowsBuilder;

/**
 * Pre-populates {@link AOTRegistry} with descriptors for the framework's
 * "infrastructure" interfaces — the ones the framework's own classes
 * resolve via {@code IClass.getClass(...)} at static-init time (most
 * notably {@code Bootstrap.buildDependencies()} which needs
 * {@link IReflectionBuilder} and {@link IObservabilityBuilder} before any
 * user code runs).
 *
 * <p>Without this seed, an application running in AOT-only mode (no
 * {@code garganttua-runtime-reflection} on the classpath) crashes at
 * {@code new Bootstrap()} because no user-side AOT processor ever sees
 * these interfaces (annotation processors only see types compiled in the
 * current compilation unit, never types from JAR dependencies).
 *
 * <p>The seed is triggered by the static initialiser of
 * {@link AOTReflectionProvider} — the first time the SPI ServiceLoader
 * instantiates the provider, this class is loaded and its {@code <clinit>}
 * registers the descriptors.
 *
 * <p>Implementation note: building an {@link AOTClass} for an interface
 * does NOT require reflection in the GraalVM sense — {@code Class.getName()},
 * {@code .getSimpleName()}, {@code .getCanonicalName()}, {@code .getPackageName()},
 * {@code .getModifiers()} and {@code .getInterfaces()} are class-metadata
 * accessors that work even on a fully-AOT'ed JVM. Annotations on the
 * interface are intentionally NOT exposed here — the dep system only needs
 * the type identifier.
 *
 * @since 2.0.0-ALPHA02
 */
public final class CoreInfrastructureSeed {


    private static volatile boolean seeded = false;

    private CoreInfrastructureSeed() {
    }

    /**
     * Idempotent. Safe to call multiple times — only the first call writes
     * to the registry, subsequent calls are a no-op.
     */
    public static synchronized void bootstrap() {
        if (seeded) {
            return;
        }
        seedFrameworkInterfaces();
        seedFrameworkAnnotations();
        seedJdkTypes();
        // Discover higher-layer-framework seeds (garganttua-api, -events, …)
        // BEFORE the user-generated AOTClass_* descriptors load — those
        // descriptors may reference framework-public types whose descriptors
        // come from these seeds.
        GeneratedDescriptorLoader.runExtensionSeeds();
        // Force-load every AOTClass_* generated by the annotation processor so
        // their static initialisers self-register into AOTRegistry.
        GeneratedDescriptorLoader.loadGeneratedDescriptors();
        seeded = true;
    }

    private static void seedFrameworkInterfaces() {
        // Framework infrastructure builder interfaces — resolved at static-init
        // time by the framework's own classes.
        registerInterfaces(IReflectionBuilder.class, IObservabilityBuilder.class,
                IInjectionContextBuilder.class, IExpressionContextBuilder.class,
                IRuntimesBuilder.class, IScriptsBuilder.class, IWorkflowsBuilder.class,
                IConditionBuilder.class, IInjectableElementResolverBuilder.class);
        // Full injection.* public surface — DI infrastructure interfaces
        // routinely resolved by framework wiring at static-init time.
        registerInterfaces(IBeanFactory.class, IBeanProvider.class, IBeanQuery.class,
                IBeanQueryBuilder.class, IBeanSupplier.class, IContextualBeanSupplier.class,
                IElementResolver.class, IInjectableElementResolver.class,
                IInjectionChildContextFactory.class, IInjectionContext.class,
                IInjectionContextSupply.class, IPropertyProvider.class, IPropertySupplier.class);
        // Full runtime.* public surface — workflow-engine interfaces referenced
        // by user @Reflected runtime definitions, fallback binders, etc.
        registerInterfaces(IRuntime.class, IRuntimes.class, IRuntimeContext.class,
                IRuntimeExecutor.class, IRuntimeResult.class, IRuntimeStep.class,
                IRuntimeStepCatch.class, IRuntimeStepFallbackBinder.class,
                IRuntimeStepMethodBinder.class, IRuntimeStepOnException.class,
                IRuntimeStepPipe.class, IDomainRuntime.class, IEventRuntime.class);
        // Crypto API surface (commons-level) — user code resolves IKey at
        // static-init time for signing/encrypting fields, plus the sibling
        // interfaces of the same realm.
        registerInterfaces(IKey.class, IHash.class, IKeyAlgorithm.class,
                IKeyRealm.class, IKeyRealmBuilder.class);
    }

    private static void seedFrameworkAnnotations() {
        // Framework-public annotation surface (commons) — user-side @Reflected
        // descriptors reference these by class literal when materialising
        // their declared-annotation arrays.
        registerClasses(Expression.class, Reflected.class, Indexed.class, ReflectedBuilder.class,
                BeanProvider.class, ChildContext.class, Fixed.class, Null.class, Property.class,
                PropertyProvider.class, Prototype.class, Provider.class, Resolver.class);
        // Reflection-side helper interface, surfaces in some indexed-discovery paths.
        registerInterface(IAnnotationIndex.class);
        // Jakarta nullability markers — referenced by user @Reflected classes
        // as parameter / field / method annotations.
        registerClasses(jakarta.annotation.Nullable.class, jakarta.annotation.Nonnull.class);
        // JSR-330 (javax.inject) — DI annotation surface. The framework
        // indexes these by default; user code uses them on constructors,
        // fields, methods, and as meta-annotations for custom qualifiers.
        registerClasses(javax.inject.Inject.class, javax.inject.Named.class,
                javax.inject.Qualifier.class, javax.inject.Scope.class, javax.inject.Singleton.class);
        registerInterface(javax.inject.Provider.class);
        // Bootstrap / DSL / dependency annotations
        registerClasses(Bootstrap.class, Scan.class, DependsOn.class);
        // Mapper annotations — user DTOs frequently carry these.
        registerClasses(FieldMappingRule.class, FieldMappingRules.class, MappingIgnore.class,
                ObjectMappingRule.class, ObjectMappingRules.class);
        // Mutex annotations
        registerClasses(Mutex.class, MutexFactory.class);
        // Runtime annotations — full workflow-engine annotation set.
        registerClasses(Catch.class, Code.class, Condition.class, Context.class,
                com.garganttua.core.runtime.annotations.Exception.class, ExceptionMessage.class,
                FallBack.class, Input.class, OnException.class, Operation.class, Output.class,
                RuntimeDefinition.class, Step.class, Steps.class, Synchronized.class,
                Variable.class, Variables.class);
        // Script / Workflow definition markers
        registerClasses(ScriptDefinition.class, WorkflowDefinition.class);
    }

    private static void seedJdkTypes() {
        // JDK collection interfaces used by framework builder return types
        // (e.g. RuntimesBuilder produces a Map<String, IRuntime<?,?>>, etc.).
        registerInterfaces(java.util.Map.class, java.util.List.class, java.util.Set.class,
                java.util.Collection.class, java.lang.Iterable.class);
        // JDK common types resolved as IClass at framework-level wiring + wrappers.
        registerClasses(java.lang.String.class, java.lang.Object.class, java.lang.Integer.class,
                java.lang.Long.class, java.lang.Boolean.class, java.lang.Double.class,
                java.lang.Float.class, java.lang.Void.class, java.util.Optional.class,
                java.util.UUID.class, java.lang.Character.class, java.lang.Short.class,
                java.lang.Byte.class);
        // JDK number — domain-model essentials
        registerClasses(java.math.BigDecimal.class, java.math.BigInteger.class);
        // JDK time — user DTOs and entities pervasively use these
        registerClasses(java.time.Instant.class, java.time.LocalDate.class,
                java.time.LocalDateTime.class, java.time.LocalTime.class,
                java.time.OffsetDateTime.class, java.time.ZonedDateTime.class,
                java.time.Duration.class, java.time.Period.class);
        // JDK util
        registerClasses(java.util.Date.class, java.util.Locale.class, java.util.TimeZone.class);
        // JDK collection interface completion
        registerInterfaces(java.util.Queue.class, java.util.Deque.class, java.util.SortedSet.class,
                java.util.NavigableSet.class, java.util.SortedMap.class, java.util.NavigableMap.class);
        // JDK collection implementations — user DTOs frequently declare these
        // as concrete types (List<X> field with new ArrayList<>() default, etc.).
        registerClasses(java.util.ArrayList.class, java.util.LinkedList.class, java.util.HashMap.class,
                java.util.LinkedHashMap.class, java.util.TreeMap.class, java.util.HashSet.class,
                java.util.LinkedHashSet.class, java.util.TreeSet.class, java.util.ArrayDeque.class);
        // JDK meta-annotation surface — IAnnotatedElement.getDeclaredAnnotationsByType
        // walks @Repeatable to find container annotations.
        registerClass(java.lang.annotation.Repeatable.class);
    }


    // Package-private: invoked by GeneratedDescriptorLoader.SeedContext to adapt
    // extension seeds to the IAOTSeedContext contract.
    static <T> void registerInterface(Class<T> iface) {
        registerType(iface, true);
    }

    static <T> void registerClass(Class<T> clazz) {
        registerType(clazz, false);
    }

    private static void registerInterfaces(Class<?>... ifaces) {
        for (Class<?> iface : ifaces) {
            registerType(iface, true);
        }
    }

    private static void registerClasses(Class<?>... classes) {
        for (Class<?> clazz : classes) {
            registerType(clazz, false);
        }
    }

    private static <T> void registerType(Class<T> type, boolean forceInterfaceFlag) {
        if (AOTRegistry.getInstance().contains(type.getName())) {
            return;
        }
        AOTRegistry.getInstance().register(type.getName(), synthesizeWithFlag(type, forceInterfaceFlag));
    }

    /**
     * Builds a minimal {@link AOTClass} descriptor for {@code type} using only
     * class-metadata accessors (no reflection in the GraalVM sense). Public
     * entry point used by {@code AOTReflectionProvider} to synthesize
     * intrinsic-JVM-type descriptors on the fly (primitives, arrays, void)
     * that the consumer-side annotation processor doesn't (and shouldn't)
     * generate descriptors for.
     */
    public static <T> AOTClass<T> synthesize(Class<T> type) {
        return synthesizeWithFlag(type, false);
    }

    private static <T> AOTClass<T> synthesizeWithFlag(Class<T> type, boolean forceInterfaceFlag) {
        String[] superNames = interfaceNames(type);
        Class<?> superclass = type.getSuperclass();
        // Defensive: primitives / anonymous / local types can return null for
        // some of these accessors. AOTClass treats them as non-null strings.
        String canonicalName = type.getCanonicalName() != null ? type.getCanonicalName() : type.getName();
        String packageName = type.getPackageName() != null ? type.getPackageName() : "";
        AOTConstructor<?>[] constructors = synthesizeNoArgConstructor(type);
        Annotation[] annotations = safeAnnotations(type);
        return new AOTClass<>(
                type.getName(),
                type.getSimpleName(),
                canonicalName,
                packageName,
                forceInterfaceFlag ? type.getModifiers() | Modifier.INTERFACE : type.getModifiers(),
                superclass != null ? superclass.getName() : null,
                superNames,
                new AOTField[0],
                new AOTMethod[0],
                constructors,
                annotations,
                type.isInterface(),
                type.isArray(),
                type.isPrimitive(),
                type.isAnnotation(),
                type.isEnum(),
                type.isRecord(),
                type.isSealed(),
                type.isHidden(),
                type.isMemberClass(),
                type.isLocalClass(),
                type.isAnonymousClass(),
                type.isSynthetic()
        );
    }

    private static String[] interfaceNames(Class<?> type) {
        Class<?>[] supers = type.getInterfaces();
        String[] superNames = new String[supers.length];
        for (int i = 0; i < supers.length; i++) {
            superNames[i] = supers[i].getName();
        }
        return superNames;
    }

    /**
     * Synthesise the no-arg constructor descriptor when one exists — covers the
     * dominant "framework instantiates the type by reflection via no-arg ctor"
     * pattern (factories annotated @ChildContext, @MutexFactory, …). Empty array
     * when none / for interfaces / primitives / arrays / void.
     */
    private static <T> AOTConstructor<?>[] synthesizeNoArgConstructor(Class<T> type) {
        try {
            java.lang.reflect.Constructor<T> noArg = type.getDeclaredConstructor();
            return new AOTConstructor<?>[] {
                    new AOTConstructor<>(type.getName(), new String[0], new String[0],
                            noArg.getModifiers(), new Annotation[0], false, new String[0])
            };
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            return new AOTConstructor<?>[0];
        }
    }

    /**
     * Class-level annotations from the live class (so framework wiring that reads
     * e.g. @Resolver sees them); empty array if introspection fails.
     */
    private static Annotation[] safeAnnotations(Class<?> type) {
        try {
            return type.getAnnotations();
        } catch (RuntimeException | LinkageError ignored) {
            return new Annotation[0];
        }
    }
}
