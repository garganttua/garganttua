package com.garganttua.core.expression;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.expression.context.ExpressionVariableContext;
import com.garganttua.core.expression.context.IExpressionVariableResolver;
import com.garganttua.core.expression.context.IScriptFunction;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.SupplyException;

/**
 * Expression node that resolves a function by name from the runtime
 * {@link IExpressionVariableResolver} at evaluation time, rather than from a registered factory.
 *
 * <p>Used as a fallback for user-defined script functions when dynamic function resolution is
 * enabled. The resolved variable must hold (or supply) an {@link IScriptFunction}.
 */
public class DynamicFunctionNode implements IExpressionNode<Object, ISupplier<Object>> {
    private static final Logger log = Logger.getLogger(DynamicFunctionNode.class);

    private final String functionName;
    private final List<IExpressionNode<?, ? extends ISupplier<?>>> argumentNodes;

    /**
     * Creates a dynamic function node.
     *
     * @param functionName the name of the function to resolve at evaluation time
     * @param argumentNodes the argument expression nodes, evaluated when the function is invoked
     */
    public DynamicFunctionNode(String functionName,
                                List<IExpressionNode<?, ? extends ISupplier<?>>> argumentNodes) {
        this.functionName = functionName;
        this.argumentNodes = List.copyOf(argumentNodes);
    }

    @Override
    public ISupplier<Object> evaluate() throws ExpressionException {
        return new DynamicFunctionSupplier();
    }

    /**
     * Resolves and invokes the named function from the active variable resolver at supply time.
     */
    private final class DynamicFunctionSupplier implements ISupplier<Object> {
        @Override
        public Optional<Object> supply() throws SupplyException {
            IExpressionVariableResolver resolver = ExpressionVariableContext.get();
            if (resolver == null) {
                throw new SupplyException("No variable resolver available for function " + functionName);
            }

            IScriptFunction scriptFunc = resolveFunction(resolver);
            Object[] args = evaluateArguments();

            log.trace("Invoking dynamic function '{}' with {} args", functionName, args.length);
            return Optional.ofNullable(scriptFunc.invoke(args));
        }

        @Override
        public Type getSuppliedType() {
            return Object.class;
        }

        @Override
        public IClass<Object> getSuppliedClass() {
            return IClass.getClass(Object.class);
        }
    }

    private IScriptFunction resolveFunction(IExpressionVariableResolver resolver) throws SupplyException {
        Optional<Object> funcObj = resolver.resolve(functionName, IClass.getClass(Object.class));
        if (funcObj.isEmpty()) {
            throw new SupplyException("Undefined function: " + functionName);
        }

        Object func = funcObj.get();
        // If it's an ISupplier wrapping an IScriptFunction, unwrap it
        if (func instanceof ISupplier<?> supplier) {
            func = supplier.supply().orElse(null);
        }

        if (func instanceof IScriptFunction scriptFunc) {
            return scriptFunc;
        }
        throw new SupplyException("Variable '" + functionName
                + "' is not a function (type: " + (func != null ? func.getClass().getSimpleName() : "null") + ")");
    }

    private Object[] evaluateArguments() throws SupplyException {
        Object[] args = new Object[argumentNodes.size()];
        for (int i = 0; i < argumentNodes.size(); i++) {
            ISupplier<?> argSupplier = Expression.evaluateNode(argumentNodes.get(i));
            args[i] = argSupplier.supply().orElse(null);
        }
        return args;
    }

    @Override
    public IClass<Object> getFinalSuppliedClass() {
        return IClass.getClass(Object.class);
    }

    @Override
    public Type getSuppliedType() {
        return Object.class;
    }
}
