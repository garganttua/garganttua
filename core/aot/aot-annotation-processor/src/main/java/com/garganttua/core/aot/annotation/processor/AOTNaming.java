package com.garganttua.core.aot.annotation.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Naming conventions for the AOT-generated classes.
 *
 * <p>Descriptor names embed the type's <em>flattened nesting path</em> — the
 * part of the qualified name after the package, with dots replaced by
 * underscores. For a top-level {@code com.foo.Bar} the flattened path is just
 * {@code Bar}; for a nested {@code com.foo.Outer.Inner} it is
 * {@code Outer_Inner}. This keeps every descriptor a flat, top-level class in
 * the enclosing type's <em>true</em> package, so a nested {@code @Reflected}
 * type no longer produces a binder in a package named after the enclosing
 * class (which {@code javac} rejects as "class clashes with package").</p>
 *
 * <ul>
 *   <li>Class descriptor:      {@code AOTClass_<FlatPath>}</li>
 *   <li>Field descriptor:      {@code AOTField_<FlatPath>_<fieldName>}</li>
 *   <li>Method descriptor:     {@code AOTMethod_<FlatPath>_<methodName>_<overloadIndex>}</li>
 *   <li>Constructor descriptor:{@code AOTConstructor_<FlatPath>_<index>}</li>
 * </ul>
 *
 * <p>Method overloads are differentiated by their position in the source-order
 * list of methods sharing the same name. Constructors are differentiated by
 * their position in the source-order list of constructors.</p>
 */
final class AOTNaming {

    private AOTNaming() {}

    /**
     * The source-form name of {@code type} as referenced from its own package:
     * the qualified name minus the package prefix. Top-level {@code com.foo.Bar}
     * yields {@code Bar}; nested {@code com.foo.Outer.Inner} yields the dotted
     * {@code Outer.Inner} (a valid source reference to the nested type).
     */
    static String sourceName(TypeElement type, String packageName) {
        String qualified = type.getQualifiedName().toString();
        if (packageName.isEmpty()) {
            return qualified;
        }
        return qualified.substring(packageName.length() + 1);
    }

    /**
     * The flattened nesting path of {@code type} within its package: the
     * {@link #sourceName(TypeElement, String) source name} with dots replaced
     * by underscores. Used as the disambiguating segment of every descriptor
     * class name so nested types get flat, top-level-style binder names in the
     * parent package.
     */
    static String flatName(TypeElement type, String packageName) {
        return sourceName(type, packageName).replace('.', '_');
    }

    static String classDescriptorName(TypeElement type, String packageName) {
        return "AOTClass_" + flatName(type, packageName);
    }

    static String fieldDescriptorName(TypeElement enclosing, String packageName, VariableElement field) {
        return "AOTField_" + flatName(enclosing, packageName) + "_" + field.getSimpleName();
    }

    /**
     * Returns method descriptor names indexed by their {@link ExecutableElement}.
     * Overloads of the same method name receive incrementing indices in source order.
     */
    static Map<ExecutableElement, String> methodDescriptorNames(TypeElement enclosing, String packageName,
                                                                List<ExecutableElement> methods) {
        String flat = flatName(enclosing, packageName);
        Map<String, Integer> counts = new HashMap<>();
        Map<ExecutableElement, String> result = new HashMap<>();
        for (ExecutableElement method : methods) {
            String name = method.getSimpleName().toString();
            int idx = counts.merge(name, 1, Integer::sum) - 1;
            result.put(method, "AOTMethod_" + flat + "_" + name + "_" + idx);
        }
        return result;
    }

    /** Returns constructor descriptor names indexed by their declaration order. */
    static Map<ExecutableElement, String> constructorDescriptorNames(TypeElement enclosing, String packageName,
                                                                     List<ExecutableElement> constructors) {
        String flat = flatName(enclosing, packageName);
        Map<ExecutableElement, String> result = new HashMap<>();
        int i = 0;
        for (ExecutableElement ctor : constructors) {
            result.put(ctor, "AOTConstructor_" + flat + "_" + i);
            i++;
        }
        return result;
    }
}
