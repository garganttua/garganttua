package com.garganttua.core.expression.dsl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ServiceLoader;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.supply.dsl.NullSupplierBuilder;

/**
 * Hard-guarantee registration of every framework built-in {@code @Expression}
 * function by FQN, plus the signature key used to deduplicate scanned methods.
 *
 * <p>Extracted from {@link ExpressionContextBuilder}: the package-scan path is the
 * "soft" version of this; resolving these classes by name and registering every
 * static {@code @Expression} method guarantees the framework built-ins are always
 * present even when a consumer's shade configuration drops an annotation index.
 * Binding-module classes absent from the classpath are silently skipped.
 */
final class FrameworkBuiltinRegistrar {

    private static final Logger log = Logger.getLogger(FrameworkBuiltinRegistrar.class);

    private FrameworkBuiltinRegistrar() {
    }

    /**
     * Framework function classes whose every {@code @Expression}-annotated static
     * method is registered directly by FQN. When splitting a {@code *Functions}
     * class, add the new class here so its functions keep registering.
     */
    static final String[] FRAMEWORK_FUNCTION_CLASSES = {
            // expression
            "com.garganttua.core.expression.functions.Expressions",
            // script
            "com.garganttua.core.script.functions.ScriptFunctions",
            "com.garganttua.core.script.functions.ScriptTimingFunctions",
            "com.garganttua.core.script.functions.ScriptResilienceFunctions",
            "com.garganttua.core.script.functions.ScriptConcurrencyFunctions",
            "com.garganttua.core.script.functions.LogFunctions",
            "com.garganttua.core.script.functions.ControlFlowFunctions",
            // runtime
            "com.garganttua.core.runtime.functions.RuntimeFunctions",
            // injection
            "com.garganttua.core.injection.functions.InjectionFunctions",
            "com.garganttua.core.injection.functions.BeanMutationFunctions",
            "com.garganttua.core.injection.context.beans.Beans",
            // mutex (+ binding-conditional redis)
            "com.garganttua.core.mutex.functions.MutexFunctions",
            "com.garganttua.core.mutex.redis.functions.RedisMutexFunctions",
            // console & observability
            "com.garganttua.core.console.ConsoleFunctions",
            "com.garganttua.core.observability.ObservabilityExpressions",
            // conditions — and / or / nor / nand / xor / not / null / notNull /
            // equals / notEquals / greater / greaterOrEquals / lower / lowerOrEquals
            "com.garganttua.core.condition.AndCondition",
            "com.garganttua.core.condition.OrCondition",
            "com.garganttua.core.condition.NorCondition",
            "com.garganttua.core.condition.NandCondition",
            "com.garganttua.core.condition.XorCondition",
            "com.garganttua.core.condition.NullCondition",
            "com.garganttua.core.condition.NotNullCondition",
            "com.garganttua.core.condition.EqualsCondition",
            "com.garganttua.core.condition.NotEqualsCondition",
            "com.garganttua.core.condition.GreaterCondition",
            "com.garganttua.core.condition.GreaterOrEqualsCondition",
            "com.garganttua.core.condition.LowerCondition",
            "com.garganttua.core.condition.LowerOrEqualsCondition",
            // events — built-in route-stage functions (log / produce / protocol_in/out /
            // filter_in/out / set_header / get_header / json_path / route_to_error / not_null).
            // Loaded reflectively by FQN: a harmless no-op when garganttua-events-expressions is
            // not on the classpath, so events built-ins resolve without -Dgarganttua.packages.
            "com.garganttua.events.expressions.EventExpressions"
    };

    @SuppressWarnings({ "unchecked" })
    static void registerAll(ExpressionContextBuilder builder) {
        IClass<? extends Annotation> exprAnnoCls =
                (IClass<? extends Annotation>) (IClass<?>) IClass.getClass(Expression.class);
        for (String fqn : FRAMEWORK_FUNCTION_CLASSES) {
            registerFunctionClass(builder, fqn, exprAnnoCls);
        }
        registerContributedFunctions(builder, exprAnnoCls);
    }

    /**
     * Resolves one provider class by FQN and registers every static {@code @Expression} method on
     * it into {@code builder}. A class absent from the classpath (or otherwise failing to link) is
     * silently skipped; a single failing method does not poison the rest.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerFunctionClass(
            ExpressionContextBuilder builder, String fqn, IClass<? extends Annotation> exprAnnoCls) {
        Class<?> cls;
        try {
            cls = Class.forName(fqn, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            // Binding / optional module not on classpath — fine.
            return;
        }
        IClass<?> ownerCls = IClass.getClass(cls);
        for (IMethod m : ownerCls.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getAnnotation(exprAnnoCls) == null) {
                continue;
            }
            try {
                builder.expression(new NullSupplierBuilder<>(ownerCls), (IClass) m.getReturnType())
                        .method(m)
                        .autoDetect(true);
            } catch (DslException ignored) {
                // One bad function should not poison the whole context.
            }
        }
    }

    /**
     * Discovers {@link IExpressionFunctionContributor} implementations via {@link ServiceLoader} and
     * registers every contributed provider-class FQN through the same path as the framework
     * built-ins, so an application's own {@code @Expression} functions resolve in every expression
     * context. Exception-isolated per contributor and per class: a misbehaving or absent class is
     * logged and skipped, never aborting context construction.
     */
    private static void registerContributedFunctions(
            ExpressionContextBuilder builder, IClass<? extends Annotation> exprAnnoCls) {
        int registered = 0;
        for (IExpressionFunctionContributor contributor
                : ServiceLoader.load(IExpressionFunctionContributor.class)) {
            try {
                for (String fqn : contributor.functionClassNames()) {
                    registerFunctionClass(builder, fqn, exprAnnoCls);
                    registered++;
                }
            } catch (RuntimeException e) {
                log.warn("Expression function contributor {} failed and was skipped: {}",
                        contributor.getClass().getName(), e.getMessage());
            }
        }
        if (registered > 0) {
            log.info("Registered {} application @Expression provider class(es) via SPI", registered);
        }
    }

    /** Build a dedup signature (declaring class + method name + parameter types) for a scanned method. */
    static String buildMethodSignature(IMethod method) {
        StringBuilder signature = new StringBuilder();
        signature.append(method.getDeclaringClass().getName());
        signature.append(".");
        signature.append(method.getName());
        signature.append("(");
        IClass<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                signature.append(",");
            }
            signature.append(paramTypes[i].getName());
        }
        signature.append(")");
        return signature.toString();
    }
}
