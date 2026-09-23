package com.garganttua.core.aot.annotation.processor;

import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Generates a typed subclass of {@code AOTConstructor} for one declared
 * constructor.
 *
 * <p>For a non-{@code private} constructor, {@code newInstance} is implemented
 * as a direct {@code new} expression — no {@link java.lang.reflect.Constructor}
 * involved at runtime.</p>
 *
 * <p>A {@code private} constructor gets a <em>reflective</em> descriptor:
 * metadata and real annotations baked in, instantiation left to
 * {@code AOTConstructor}, which resolves the
 * {@link java.lang.reflect.Constructor} once, calls {@code trySetAccessible()}
 * and memoises it. A descriptor generated beside the class cannot call a
 * private constructor directly, so those used to be dropped from the
 * descriptor altogether — which is how a singleton or a builder-only type lost
 * its declared constructors under AOT.</p>
 */
final class AOTConstructorSourceGenerator {

    private final ExecutableElement constructor;
    private final Types types;
    private final String packageName;
    private final String enclosingSimpleName;
    private final String enclosingSourceName;
    private final String enclosingBinaryName;
    private final String generatedSimpleName;
    private final boolean reflective;

    AOTConstructorSourceGenerator(Types types, TypeElement enclosing, String packageName,
            ExecutableElement constructor, String generatedSimpleName) {
        this.types = types;
        this.constructor = constructor;
        this.generatedSimpleName = generatedSimpleName;
        this.enclosingBinaryName = AOTNaming.binaryName(enclosing, packageName);
        this.enclosingSimpleName = enclosing.getSimpleName().toString();
        this.packageName = packageName;
        // Source-form reference to the enclosing type from its own package:
        // "Bar" for top-level, "Outer.Inner" for nested.
        this.enclosingSourceName = AOTNaming.sourceName(enclosing, packageName);
        this.reflective = constructor.getModifiers().contains(Modifier.PRIVATE);
    }

    String getGeneratedQualifiedName() {
        return packageName.isEmpty() ? generatedSimpleName : packageName + "." + generatedSimpleName;
    }

    String generate() {
        List<? extends VariableElement> params = constructor.getParameters();
        StringBuilder src = new StringBuilder();
        if (!packageName.isEmpty()) {
            src.append("package ").append(packageName).append(";\n\n");
        }
        src.append("import com.garganttua.core.aot.reflection.AOTConstructor;\n");
        src.append("import java.lang.annotation.Annotation;\n\n");

        src.append("/** AOT constructor descriptor for {@code ").append(enclosingSimpleName)
           .append("(...)} (")
           .append(reflective ? "reflective access — the constructor is private" : "direct access")
           .append(") — generated, do not edit. */\n");
        src.append("@SuppressWarnings(\"all\")\n");
        src.append("public final class ").append(generatedSimpleName)
           .append(" extends AOTConstructor<").append(enclosingSourceName).append("> {\n\n");
        src.append("    public static final ").append(generatedSimpleName)
           .append(" INSTANCE = new ").append(generatedSimpleName).append("();\n\n");

        appendConstructor(src, params);
        appendNewInstance(src, params);

        src.append("}\n");
        return src.toString();
    }

    /** Appends the private no-arg ctor that forwards the constructor metadata to {@code super(...)}. */
    private void appendConstructor(StringBuilder src, List<? extends VariableElement> params) {
        src.append("    private ").append(generatedSimpleName).append("() {\n");
        src.append("        super(\"").append(enclosingBinaryName).append("\", ")
           .append(AOTMethodSourceGenerator.buildStringArray(typeNames(params))).append(", ")
           .append(AOTMethodSourceGenerator.buildStringArray(paramNames(params))).append(", ")
           .append(TypeNames.toReflectModifiers(constructor.getModifiers())).append(", ")
           .append(buildAnnotationsExpr(params)).append(", ")
           .append(constructor.isVarArgs()).append(", ")
           .append(AOTMethodSourceGenerator.buildStringArray(exceptionTypeNames())).append(");\n");
        src.append("    }\n\n");
    }

    /**
     * Appends the direct {@code newInstance(Object...)} override — but only when
     * the constructor is reachable from the generated class. A private one keeps
     * {@code AOTConstructor}'s memoised reflective path.
     */
    private void appendNewInstance(StringBuilder src, List<? extends VariableElement> params) {
        if (reflective) {
            return;
        }
        src.append("    @Override\n");
        src.append("    public ").append(enclosingSourceName).append(" newInstance(Object... args) {\n");
        src.append("        return new ").append(enclosingSourceName)
           .append("(").append(buildArgCasts(params)).append(");\n");
        src.append("    }\n");
    }

    /**
     * Generated-source expression for the constructor's <em>real</em>
     * annotations.
     *
     * <p>Emits {@code AOTAnnotations.ofConstructor(Owner.class, P1.class, …)}
     * in place of the hardcoded {@code new Annotation[0]}, mirroring what
     * {@code AOTClassSourceGenerator} already does for type annotations — a
     * {@code @Inject} constructor was invisible under AOT for exactly that
     * reason. Parameter types are erased, i.e. the runtime signature
     * {@code getDeclaredConstructor} expects.</p>
     */
    private String buildAnnotationsExpr(List<? extends VariableElement> params) {
        for (VariableElement param : params) {
            if (!TypeNames.isReferenceableFrom(param.asType(), packageName)) {
                // A parameter type the descriptor cannot name from its own
                // package — a private nested class, say. Emitting its class
                // literal would produce a descriptor that does not compile.
                return "new Annotation[0]";
            }
        }
        StringBuilder sb = new StringBuilder(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofConstructor(")
                .append(enclosingSourceName).append(".class");
        for (String typeName : typeNames(params)) {
            sb.append(", ").append(typeName).append(".class");
        }
        return sb.append(')').toString();
    }

    private String[] typeNames(List<? extends VariableElement> params) {
        String[] out = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            out[i] = TypeNames.getTypeName(types, params.get(i).asType());
        }
        return out;
    }

    private String[] paramNames(List<? extends VariableElement> params) {
        String[] out = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            out[i] = params.get(i).getSimpleName().toString();
        }
        return out;
    }

    private String[] exceptionTypeNames() {
        List<? extends TypeMirror> thrown = constructor.getThrownTypes();
        String[] out = new String[thrown.size()];
        for (int i = 0; i < thrown.size(); i++) {
            out[i] = TypeNames.getTypeName(types, thrown.get(i));
        }
        return out;
    }

    private String buildArgCasts(List<? extends VariableElement> params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(AOTMethodSourceGenerator.castArg(types, params.get(i).asType(), i));
        }
        return sb.toString();
    }
}
