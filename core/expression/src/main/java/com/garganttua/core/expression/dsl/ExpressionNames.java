package com.garganttua.core.expression.dsl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import com.garganttua.core.dsl.DslException;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;

/**
 * The rule that one expression name designates one method, enforced while functions are registered.
 *
 * <p>
 * Two {@code @Expression} methods sharing a name AND an arity resolve to both, and the failure lands
 * where nothing explains it: {@code Multiple overloads of method … match the exact signature},
 * thrown from the reflection layer, naming neither the expression nor the module it came from.
 * {@code garganttua-mutex-redis} shipped exactly that for three versions — two
 * {@code syncRedis(…, ISupplier)}, one on {@code String} and one on {@code Object} — so merely
 * putting the jar on an application's classpath stopped that application from starting.
 * </p>
 *
 * <p>
 * Checked on every path that registers functions: the framework's own classes, the ones an
 * application contributes through the SPI, and the ones package auto-detection finds. What the
 * refusal then does depends on the caller — the framework path lets it abort, the SPI path isolates
 * it into a warning — but the message always names the offending pair.
 * </p>
 */
final class ExpressionNames {

    @SuppressWarnings("unchecked")
    private static final IClass<? extends Annotation> EXPRESSION_ANNOTATION =
            (IClass<? extends Annotation>) (IClass<?>) IClass.getClass(Expression.class);

    private ExpressionNames() {
        // Static helpers
    }

    /**
     * Refuses a set of methods in which one expression name is carried by two of the same arity.
     *
     * <p>
     * The test is not "same name, same arity" — that would condemn perfectly resolvable pairs like
     * {@code Beans.bean(Optional,BeanReference)} and {@code Beans.bean(IClass,String)}, whose
     * parameter types tell them apart. It is the condition the resolver actually chokes on: one
     * overload SHADOWS the other, its parameters being assignable from the other's in every
     * position, so a call written for the specific one matches both. {@code syncRedis(String,…)} and
     * {@code syncRedis(Object,…)} are exactly that.
     * </p>
     *
     * <p>
     * Different arities are left alone too: they resolve unambiguously, and are a legitimate way to
     * offer an optional argument.
     * </p>
     *
     * @param source  what is being registered, named in the message so the reader knows where to
     *                look
     * @param methods the candidate methods; non-static and unannotated ones are ignored
     * @throws DslException if one name is carried by two methods of the same arity
     */
    static void refuseAmbiguous(String source, Iterable<IMethod> methods) {
        Map<String, IMethod> seen = new HashMap<>();
        for (IMethod method : methods) {
            String name = expressionNameOf(method);
            if (name == null) {
                continue;
            }
            String key = name + "/" + method.getParameterCount();
            IMethod previous = seen.put(key, method);
            if (previous != null && (shadows(previous, method) || shadows(method, previous))) {
                throw new DslException("Expression name '" + name + "' is carried by two methods of "
                        + source + " that resolution cannot tell apart — one takes the other's "
                        + "parameters, so a call written for either matches both and fails when the "
                        + "function is used. One name must designate one method. Offending pair: "
                        + previous.toGenericString() + " and " + method.toGenericString());
            }
        }
    }

    /**
     * Whether {@code general} accepts everything {@code specific} does — the shape that makes the
     * resolver find two matches for one call.
     */
    private static boolean shadows(IMethod general, IMethod specific) {
        IClass<?>[] wide = general.getParameterTypes();
        IClass<?>[] narrow = specific.getParameterTypes();
        if (wide.length != narrow.length) {
            return false;
        }
        for (int i = 0; i < wide.length; i++) {
            if (!wide[i].isAssignableFrom(narrow[i])) {
                return false;
            }
        }
        return true;
    }

    /** The expression name a static method declares, or null when it declares none. */
    private static String expressionNameOf(IMethod method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        Annotation anno = method.getAnnotation(EXPRESSION_ANNOTATION);
        if (!(anno instanceof Expression expression)) {
            return null;
        }
        String name = expression.name().isEmpty() ? expression.value() : expression.name();
        return name.isEmpty() ? null : name;
    }
}
