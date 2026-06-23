package com.garganttua.core.expression.context;

import java.util.List;
import java.util.Map;
import java.util.Set;


import com.garganttua.core.observability.Logger;
import com.garganttua.core.expression.IExpressionNode;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.supply.ISupplier;

/**
 * Resolves expression-node factory lookup keys and finds the compatible factory for a
 * function name + argument list (parameter-type matching incl. primitive widening).
 * Extracted from {@link ExpressionVisitor} to keep that visitor focused on tree-walking.
 */
final class FactoryKeyResolver {

    private static final Logger log = Logger.getLogger(FactoryKeyResolver.class);

    private static final String LAZY_SUPPLIER_TYPE = "ISupplier";
    private static final int NO_MATCH = -1;

    private final Map<String, IExpressionNodeFactory<?, ? extends ISupplier<?>>> nodeFactories;

    FactoryKeyResolver(Map<String, IExpressionNodeFactory<?, ? extends ISupplier<?>>> nodeFactories) {
        this.nodeFactories = nodeFactories;
    }

    IExpressionNodeFactory<?, ? extends ISupplier<?>> findCompatibleFactoryForDirectParams(
            String functionName, Class<?>... argTypes) {
        String prefix = functionName + "(";
        int arity = argTypes.length;

        for (Map.Entry<String, IExpressionNodeFactory<?, ? extends ISupplier<?>>> entry : nodeFactories.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) continue;

            String paramPart = key.substring(prefix.length(), key.length() - 1);
            String[] paramTypeNames = paramPart.isEmpty() ? new String[0] : paramPart.split(",");
            int keyArity = paramTypeNames.length;

            if (keyArity != arity) continue;

            // Check if all argument types are assignable to factory parameter types
            boolean compatible = true;
            for (int i = 0; i < keyArity && compatible; i++) {
                Class<?> factoryParamType = resolveSimpleTypeName(paramTypeNames[i].trim());
                if (factoryParamType == null) {
                    compatible = false;
                } else if (!factoryParamType.isAssignableFrom(argTypes[i])) {
                    if (!isPrimitiveCompatible(factoryParamType, argTypes[i])) {
                        compatible = false;
                    }
                }
            }

            if (compatible) {
                log.debug("Found compatible factory for direct params: {} for {}({})",
                        key, functionName, java.util.Arrays.toString(argTypes));
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Checks if any registered factory starts with the given function name.
     */
    boolean hasRegisteredFunction(String functionName) {
        String prefix = functionName + "(";
        return nodeFactories.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    /**
     * Builds a function key for direct parameters in the format
     * "functionName(Type1,Type2,...)".
     */
    String buildKey(String functionName, Class<?>... paramTypes) {
        StringBuilder keyBuilder = new StringBuilder(functionName);
        keyBuilder.append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0)
                keyBuilder.append(",");
            keyBuilder.append(paramTypes[i].getSimpleName());
        }
        keyBuilder.append(")");
        String key = keyBuilder.toString();
        log.debug("Built key: {}", key);
        return key;
    }

    /**
     * Builds a function key in the format "functionName(Type1,Type2,...)".
     */
    String buildNodeKey(String functionName, List<Object> arguments) {
        StringBuilder keyBuilder = new StringBuilder(functionName);
        keyBuilder.append("(");

        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0)
                keyBuilder.append(",");

            if (arguments.get(i) instanceof IExpressionNode<?, ?> node) {
                keyBuilder.append(node.getFinalSuppliedClass().getSimpleName());
            } else {
                keyBuilder.append(arguments.get(i).getClass().getSimpleName());
            }

        }

        keyBuilder.append(")");
        String key = keyBuilder.toString();
        log.debug("Built node key: {}", key);
        return key;
    }

    IExpressionNodeFactory<?, ? extends ISupplier<?>> findCompatibleFactory(String functionName, List<Object> arguments) {
        String prefix = functionName + "(";
        int arity = arguments.size();
        Class<?>[] argTypes = extractArgumentTypes(arguments);

        // First pass: look for exact matches or narrowing (argType extends factoryParamType)
        IExpressionNodeFactory<?, ? extends ISupplier<?>> bestMatch = null;
        int bestScore = NO_MATCH;

        for (Map.Entry<String, IExpressionNodeFactory<?, ? extends ISupplier<?>>> entry : nodeFactories.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            String[] paramTypeNames = parseParamTypeNames(key, prefix);
            if (paramTypeNames.length != arity) {
                continue;
            }
            int score = scoreMatch(paramTypeNames, argTypes);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry.getValue();
                log.debug("Found compatible factory via type matching: {} (score={}) for {}({})",
                        key, score, functionName, java.util.Arrays.toString(argTypes));
            }
        }
        return bestMatch;
    }

    private Class<?>[] extractArgumentTypes(List<Object> arguments) {
        Class<?>[] argTypes = new Class<?>[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof IExpressionNode<?, ?> node) {
                argTypes[i] = (Class<?>) node.getFinalSuppliedClass().getType();
            } else {
                argTypes[i] = arguments.get(i).getClass();
            }
        }
        return argTypes;
    }

    private String[] parseParamTypeNames(String key, String prefix) {
        String paramPart = key.substring(prefix.length(), key.length() - 1);
        return paramPart.isEmpty() ? new String[0] : paramPart.split(",");
    }

    /**
     * Computes a compatibility score for a candidate factory signature, or {@link #NO_MATCH} when
     * any argument is incompatible. Higher scores indicate a better match.
     */
    // PMD.UseVarargs: private scorer pairs two parallel arrays by index; varargs would be misleading.
    @SuppressWarnings("PMD.UseVarargs")
    private int scoreMatch(String[] paramTypeNames, Class<?>[] argTypes) {
        int score = 0;
        for (int i = 0; i < paramTypeNames.length; i++) {
            int paramScore = scoreParameter(paramTypeNames[i].trim(), argTypes[i]);
            if (paramScore == NO_MATCH) {
                return NO_MATCH;
            }
            score += paramScore;
        }
        return score;
    }

    private int scoreParameter(String paramTypeName, Class<?> argType) {
        // ISupplier (lazy) parameters accept any argument type (they'll be wrapped lazily)
        if (LAZY_SUPPLIER_TYPE.equals(paramTypeName)) {
            return 3;
        }
        Class<?> factoryParamType = resolveSimpleTypeName(paramTypeName);
        if (factoryParamType == null) {
            return NO_MATCH;
        }
        if (argType == Object.class || factoryParamType == Object.class) {
            return 0;
        }
        if (factoryParamType.isAssignableFrom(argType)) {
            return factoryParamType == argType ? 2 : 1;
        }
        if (isPrimitiveCompatible(factoryParamType, argType)) {
            return 2;
        }
        return NO_MATCH;
    }

    /**
     * Resolves a simple type name to its Class.
     * Handles primitives and common types using simple names.
     */
    Class<?> resolveSimpleTypeName(String simpleName) {
        return switch (simpleName) {
            case "Object" -> Object.class;
            case "String" -> String.class;
            case "Integer", "int" -> Integer.class;
            case "Long", "long" -> Long.class;
            case "Double", "double" -> Double.class;
            case "Float", "float" -> Float.class;
            case "Boolean", "boolean" -> Boolean.class;
            case "Byte", "byte" -> Byte.class;
            case "Short", "short" -> Short.class;
            case "Character", "char" -> Character.class;
            case "Class" -> Class.class;
            case "IClass" -> IClass.class;
            case "Set" -> java.util.Set.class;
            case "List" -> java.util.List.class;
            case "Map" -> java.util.Map.class;
            case "Optional" -> java.util.Optional.class;
            case "BeanReference" -> com.garganttua.core.injection.BeanReference.class;
            default -> {
                // Try to load class by simple name in common packages
                try {
                    yield Class.forName("java.lang." + simpleName);
                } catch (ClassNotFoundException e1) {
                    try {
                        yield Class.forName("java.util." + simpleName);
                    } catch (ClassNotFoundException e2) {
                        log.trace("Could not resolve type name: {}", simpleName);
                        yield null;
                    }
                }
            }
        };
    }

    /**
     * Checks if the argument type is compatible with the parameter type
     * considering primitive/wrapper conversions.
     */
    boolean isPrimitiveCompatible(Class<?> paramType, Class<?> argType) {
        if (paramType == int.class || paramType == Integer.class) {
            return argType == int.class || argType == Integer.class;
        }
        if (paramType == long.class || paramType == Long.class) {
            return argType == long.class || argType == Long.class;
        }
        if (paramType == double.class || paramType == Double.class) {
            return argType == double.class || argType == Double.class;
        }
        if (paramType == float.class || paramType == Float.class) {
            return argType == float.class || argType == Float.class;
        }
        if (paramType == boolean.class || paramType == Boolean.class) {
            return argType == boolean.class || argType == Boolean.class;
        }
        if (paramType == byte.class || paramType == Byte.class) {
            return argType == byte.class || argType == Byte.class;
        }
        if (paramType == short.class || paramType == Short.class) {
            return argType == short.class || argType == Short.class;
        }
        if (paramType == char.class || paramType == Character.class) {
            return argType == char.class || argType == Character.class;
        }
        return false;
    }
}
