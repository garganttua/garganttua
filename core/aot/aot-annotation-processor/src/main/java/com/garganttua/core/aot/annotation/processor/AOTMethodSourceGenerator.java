package com.garganttua.core.aot.annotation.processor;

import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Generates a typed subclass of {@code AOTMethod} for one declared method.
 *
 * <p>For a non-{@code private} method, {@code invoke} is implemented as a
 * direct call — no {@link java.lang.reflect.Method} involved at runtime.</p>
 *
 * <p>A {@code private} method gets a <em>reflective</em> descriptor: metadata
 * and real annotations baked in, invocation left to {@code AOTMethod}, which
 * resolves the {@link java.lang.reflect.Method} once, calls
 * {@code trySetAccessible()} and memoises it. A descriptor generated beside the
 * class cannot call a private method directly, so those methods used to be
 * dropped from the descriptor altogether.</p>
 */
final class AOTMethodSourceGenerator {

    private final ExecutableElement method;
    private final Types types;
    private final String packageName;
    private final String enclosingSimpleName;
    private final String enclosingSourceName;
    private final String enclosingBinaryName;
    private final String generatedSimpleName;
    private final boolean isStatic;
    private final boolean isVoid;
    private final boolean reflective;

    AOTMethodSourceGenerator(Types types, TypeElement enclosing, String packageName,
            ExecutableElement method, String generatedSimpleName) {
        this.types = types;
        this.method = method;
        this.generatedSimpleName = generatedSimpleName;
        this.enclosingBinaryName = AOTNaming.binaryName(enclosing, packageName);
        this.enclosingSimpleName = enclosing.getSimpleName().toString();
        this.packageName = packageName;
        // Source-form reference to the enclosing type from its own package.
        this.enclosingSourceName = AOTNaming.sourceName(enclosing, packageName);
        this.isStatic = method.getModifiers().contains(Modifier.STATIC);
        this.isVoid = method.getReturnType().getKind() == TypeKind.VOID;
        this.reflective = method.getModifiers().contains(Modifier.PRIVATE);
    }

    String getGeneratedQualifiedName() {
        return packageName.isEmpty() ? generatedSimpleName : packageName + "." + generatedSimpleName;
    }

    String generate() {
        List<? extends VariableElement> params = method.getParameters();
        StringBuilder src = new StringBuilder();
        if (!packageName.isEmpty()) {
            src.append("package ").append(packageName).append(";\n\n");
        }
        src.append("import com.garganttua.core.aot.reflection.AOTMethod;\n");
        src.append("import java.lang.annotation.Annotation;\n\n");

        src.append("/** AOT method descriptor for {@code ").append(enclosingSimpleName)
           .append('.').append(method.getSimpleName()).append("(...)} (")
           .append(reflective ? "reflective access — the method is private" : "direct access")
           .append(") — generated, do not edit. */\n");
        src.append("@SuppressWarnings(\"all\")\n");
        src.append("public final class ").append(generatedSimpleName).append(" extends AOTMethod {\n\n");
        src.append("    public static final ").append(generatedSimpleName)
           .append(" INSTANCE = new ").append(generatedSimpleName).append("();\n\n");

        appendConstructor(src, params);
        if (!reflective) {
            boolean hasChecked = !method.getThrownTypes().isEmpty();
            appendInvoke(src, params, hasChecked);
            if (hasChecked) {
                appendSneakyThrow(src);
            }
        }

        src.append("}\n");
        return src.toString();
    }

    /** Appends the private no-arg ctor that forwards method metadata to {@code super(...)}. */
    private void appendConstructor(StringBuilder src, List<? extends VariableElement> params) {
        src.append("    private ").append(generatedSimpleName).append("() {\n");
        src.append("        super(\"").append(method.getSimpleName()).append("\", \"")
           .append(enclosingBinaryName).append("\", \"")
           .append(TypeNames.getTypeName(types, method.getReturnType())).append("\", ")
           .append(buildStringArray(typeNames(params))).append(", ")
           .append(buildStringArray(paramNames(params))).append(", ")
           .append(TypeNames.toReflectModifiers(method.getModifiers())).append(", ")
           .append(buildAnnotationsExpr(params)).append(", false, ")
           .append(method.getModifiers().contains(Modifier.DEFAULT)).append(", ")
           .append(method.isVarArgs()).append(", ")
           .append(buildStringArray(exceptionTypeNames())).append(");\n");
        src.append("    }\n\n");
    }

    /**
     * Generated-source expression for the method's <em>real</em> annotations.
     *
     * <p>Emits {@code AOTAnnotations.ofMethod(Owner.class, "name", P1.class, …)}
     * in place of the hardcoded {@code new Annotation[0]}, mirroring what
     * {@code AOTClassSourceGenerator} already does for type annotations. The
     * parameter types are the erased ones ({@link TypeNames#getTypeName}), i.e.
     * exactly the runtime signature {@code getDeclaredMethod} expects.</p>
     */
    private String buildAnnotationsExpr(List<? extends VariableElement> params) {
        if (!parameterTypesAreReferenceable(params)) {
            // A parameter type the descriptor cannot name from its own package —
            // a private nested class, say. Emitting its class literal would
            // produce a descriptor that does not compile, so this one member
            // keeps the previous annotation-less behaviour.
            return "new Annotation[0]";
        }
        StringBuilder sb = new StringBuilder(
                "com.garganttua.core.aot.reflection.AOTAnnotations.ofMethod(")
                .append(enclosingSourceName).append(".class, \"")
                .append(method.getSimpleName()).append('"');
        for (String typeName : typeNames(params)) {
            sb.append(", ").append(typeName).append(".class");
        }
        return sb.append(')').toString();
    }

    /**
     * Appends {@code invoke(Object, Object...)}. When the method declares
     * checked exceptions, the call is wrapped in a try/catch that sneaky-throws
     * them (the {@code invoke} signature does not declare them), so the
     * framework still sees the original exception type rather than a wrapper.
     */
    // AvoidDuplicateLiterals: ";\n" is a code-generation statement terminator emitted into
    // the produced source, not a magic constant worth hoisting.
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    private void appendInvoke(StringBuilder src, List<? extends VariableElement> params, boolean hasChecked) {
        src.append("    @Override\n");
        src.append("    public Object invoke(Object obj, Object... args) {\n");
        String receiver = isStatic
                ? enclosingSourceName
                : "((" + enclosingSourceName + ") obj)";
        String call = receiver + "." + method.getSimpleName() + "(" + buildArgCasts(params) + ")";
        if (hasChecked) {
            src.append("        try {\n");
            if (isVoid) {
                src.append("            ").append(call).append(";\n");
                src.append("            return null;\n");
            } else {
                src.append("            return ").append(call).append(";\n");
            }
            src.append("        } catch (RuntimeException | Error __e) {\n");
            src.append("            throw __e;\n");
            src.append("        } catch (Throwable __t) {\n");
            src.append("            throw ").append(generatedSimpleName).append(".__sneakyThrow(__t);\n");
            src.append("        }\n");
        } else if (isVoid) {
            src.append("        ").append(call).append(";\n");
            src.append("        return null;\n");
        } else {
            src.append("        return ").append(call).append(";\n");
        }
        src.append("    }\n");
    }

    /** Appends the generic-erasure sneaky-throw helper used by {@code invoke}. */
    private void appendSneakyThrow(StringBuilder src) {
        src.append("\n");
        src.append("    @SuppressWarnings(\"unchecked\")\n");
        src.append("    private static <E extends Throwable> RuntimeException __sneakyThrow(Throwable t) throws E {\n");
        src.append("        throw (E) t;\n");
        src.append("    }\n");
    }

    /** Whether every parameter type can be named from the descriptor's package. */
    private boolean parameterTypesAreReferenceable(List<? extends VariableElement> params) {
        for (VariableElement param : params) {
            if (!TypeNames.isReferenceableFrom(param.asType(), packageName)) {
                return false;
            }
        }
        return true;
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
        List<? extends TypeMirror> thrown = method.getThrownTypes();
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
            sb.append(castArg(types, params.get(i).asType(), i));
        }
        return sb.toString();
    }

    static String castArg(Types types, TypeMirror type, int index) {
        String primitive = TypeNames.primitiveKind(type);
        if (primitive != null) {
            String wrapper = TypeNames.primitiveWrapper(primitive);
            return "(" + wrapper + ") args[" + index + "]";
        }
        return "(" + TypeNames.getTypeName(types, type) + ") args[" + index + "]";
    }

    static String buildStringArray(String... values) {
        if (values.length == 0) return "new String[0]";
        StringBuilder sb = new StringBuilder("new String[]{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(values[i]).append('"');
        }
        sb.append('}');
        return sb.toString();
    }
}
