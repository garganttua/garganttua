package com.garganttua.core.script.context;

import java.util.Optional;

import com.garganttua.core.expression.ForLoopExpressionNode;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.runtime.RuntimeExpressionContext;

/**
 * Bridges script variable references to the runtime context.
 *
 * <p>
 * Resolves special script variables:
 * <ul>
 *   <li>{@code $0, $1, ...} — positional arguments from the runtime input</li>
 *   <li>{@code code} — the current exit code</li>
 *   <li>{@code output} — the current output value</li>
 *   <li>All other names — runtime context variables</li>
 * </ul>
 *
 * <p>
 * This resolver reads the current {@link IRuntimeContext} from
 * {@link RuntimeExpressionContext} at resolution time, so it does not need
 * a context reference at construction time.
 * </p>
 *
 * @since 2.0.0-ALPHA01
 */
public class ScriptVariableResolver implements ForLoopExpressionNode.VariableSettableResolver {

    private static final IClass<Object> OBJECT_CLASS = IClass.getClass(Object.class);
    private static final IClass<Integer> INTEGER_CLASS = IClass.getClass(Integer.class);
    /** Special script variable name exposing the current runtime exit code. */
    private static final String VAR_CODE = "code";
    /** Special script variable name exposing/setting the current runtime output. */
    private static final String VAR_OUTPUT = "output";
    /** Shared {@code unchecked} suppression token (kept single to satisfy AvoidDuplicateLiterals). */
    private static final String UNCHECKED = "unchecked";
    /** Minimum length of a {@code $N} positional reference (the {@code $} plus one digit). */
    private static final int MIN_POSITIONAL_LENGTH = 1;

    /**
     * Resolves a script variable reference against the current runtime context.
     * Handles positional arguments ({@code $0, $1, ...}), the special
     * {@code code} and {@code output} names, and ordinary context variables.
     *
     * @param name the variable name (without the {@code @}/{@code .} sigil)
     * @param type the expected value type used for casting/assignability checks
     * @param <T>  the resolved value type
     * @return the resolved value, or {@link Optional#empty()} when absent, the
     *         context is missing, or the value is not assignable to {@code type}
     */
    @Override
    @SuppressWarnings(UNCHECKED)
    public <T> Optional<T> resolve(String name, IClass<T> type) {
        IRuntimeContext<?, ?> context = RuntimeExpressionContext.get();
        if (context == null) {
            return Optional.empty();
        }
        if (name.startsWith("$") && isPositionalIndex(name)) {
            return resolvePositional(context, name, type);
        }
        if (VAR_CODE.equals(name)) {
            Optional<Integer> code = context.getCode();
            if (code.isPresent() && type.isAssignableFrom(INTEGER_CLASS)) {
                return Optional.of((T) code.get());
            }
            return Optional.empty();
        }
        if (VAR_OUTPUT.equals(name)) {
            return resolveOutput(context.getOutput(), type);
        }
        return context.getVariable(name, type);
    }

    /** @return {@code true} if {@code name} (with leading {@code $}) is a numeric positional ref. */
    private static boolean isPositionalIndex(String name) {
        if (name.length() <= MIN_POSITIONAL_LENGTH) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Resolves positional arguments {@code $0, $1, ...} from the runtime input array. */
    @SuppressWarnings(UNCHECKED)
    private <T> Optional<T> resolvePositional(IRuntimeContext<?, ?> context, String name, IClass<T> type) {
        int index = Integer.parseInt(name.substring(1));
        Object input = context.getInput().orElse(null);
        if (input instanceof Object[] args && index >= 0 && index < args.length) {
            Object value = args[index];
            if (value == null) {
                return Optional.empty();
            }
            if (type.isInstance(value)) {
                return Optional.of(type.cast(value));
            }
            if (type.equals(OBJECT_CLASS)) {
                return Optional.of((T) value);
            }
        }
        return Optional.empty();
    }

    /** Resolves the special {@code output} variable, casting against the requested type. */
    @SuppressWarnings(UNCHECKED)
    private <T> Optional<T> resolveOutput(Object output, IClass<T> type) {
        if (output != null && type.isInstance(output)) {
            return Optional.of(type.cast(output));
        }
        if (output != null && type.equals(OBJECT_CLASS)) {
            return Optional.of((T) output);
        }
        return Optional.empty();
    }

    /**
     * Assigns a value to a script variable in the current runtime context.
     * The special name {@code output} sets the context output instead of a
     * named variable. No-op when there is no active context.
     *
     * @param name  the variable name (or {@code output})
     * @param value the value to assign; a {@code null} {@code output} value is ignored
     */
    @Override
    @SuppressWarnings({UNCHECKED, "rawtypes"})
    public void setVariable(String name, Object value) {
        IRuntimeContext context = RuntimeExpressionContext.get();
        if (context == null) {
            return;
        }

        if (VAR_OUTPUT.equals(name)) {
            if (value != null) {
                context.setOutput(value);
            }
            return;
        }
        context.setVariable(name, value);
    }
}
