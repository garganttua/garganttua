package com.garganttua.core.aot.annotation.processor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Compile-time annotation processor that generates AOT descriptor classes
 * for every type annotated with {@code @Reflected}.
 *
 * <p>Enabled by the compiler option {@code -Agarganttua.direct.binders=true}.
 * If the option is missing or not {@code "true"} the processor does nothing.</p>
 *
 * <p>For each {@code @Reflected} type, the processor emits an
 * {@code AOTClass_<FlatName>} (the flattened nesting path — {@code AOTClass_Outer_Inner}
 * for a nested type — see {@link AOTNaming}) in the type's true package, referencing
 * per-member descriptor singletons ({@code AOTField_*}, {@code AOTMethod_*},
 * {@code AOTConstructor_*} with direct, no-reflection access) plus a listing entry in
 * {@code META-INF/garganttua/aot/classes/<fqn>}. Members may carry an explicit
 * {@code @Reflected} in addition to the class-level
 * {@code queryAll* / allDeclaredFields} flags; the enclosing type must itself be
 * {@code @Reflected} or a member-only annotation is rejected at compile time.</p>
 *
 * <p><strong>Visibility constraint:</strong> an explicitly-{@code @Reflected}
 * {@code private} member is a compile-time error (direct binders cannot bypass Java
 * visibility); privates pulled in by {@code queryAll*} are filtered.</p>
 *
 * @since 2.0.0-ALPHA01
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions("garganttua.direct.binders")
public class DirectBinderGenerator extends AbstractProcessor {

    private static final String REFLECTED_ANNOTATION = "com.garganttua.core.reflection.annotations.Reflected";
    private static final String INDEXED_ANNOTATION = "com.garganttua.core.reflection.annotations.Indexed";
    private static final String AOT_CLASSES_DIR = "META-INF/garganttua/aot/classes/";

    /** Types already processed across all rounds — guards against double-generation
     *  when a class is BOTH @Reflected AND has @Indexed-meta annotations. */
    private final java.util.Set<String> processedTypes = new LinkedHashSet<>();

    private Messager messager;
    private boolean enabled;

    /**
     * Captures the {@link Messager} and reads the {@code garganttua.direct.binders}
     * option, enabling generation only when it is set to {@code "true"}.
     *
     * @param processingEnv the processing environment supplied by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        String option = processingEnv.getOptions().get("garganttua.direct.binders");
        this.enabled = "true".equalsIgnoreCase(option);
        if (enabled) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[garganttua-aot] DirectBinderGenerator enabled");
        }
    }

    /** FQNs of every AOTClass_* generated across rounds; written to the
     *  IAOTSelfRegistering service file so ServiceLoader / native-image find them. */
    private final java.util.Set<String> generatedDescriptorFqns = new LinkedHashSet<>();

    /**
     * Generates AOT descriptors for {@code @Reflected} types (and auto-promoted
     * {@code @Indexed}-meta types) each round, then writes the ServiceLoader
     * descriptor once processing is over. Does nothing when generation is disabled.
     *
     * @param annotations the annotation types requested to be processed this round
     * @param roundEnv     information about the current and prior rounds
     * @return {@code false} — this processor never claims the annotations it reads
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!enabled) {
            return false;
        }
        if (roundEnv.processingOver()) {
            // Final round: emit the ServiceLoader descriptor accumulated across rounds.
            writeSelfRegisteringServiceFile();
            return false;
        }

        for (TypeElement annotation : annotations) {
            if (REFLECTED_ANNOTATION.equals(annotation.getQualifiedName().toString())) {
                processReflectedAnnotation(annotation, roundEnv);
            }
        }

        // Pass 2: auto-promote @Indexed-meta classes lacking @Reflected — closes the
        // "index says yes, descriptor says no" gap that crashes them in pure-AOT.
        for (Element root : roundEnv.getRootElements()) {
            if (root instanceof TypeElement type) {
                autoPromoteIfIndexed(type);
            }
        }

        return false;
    }

    /**
     * Collects every {@code @Reflected} type and member for one annotation round,
     * reports any member whose enclosing type is not itself {@code @Reflected},
     * then generates a descriptor for each {@code @Reflected} type.
     */
    private void processReflectedAnnotation(TypeElement annotation, RoundEnvironment roundEnv) {
        Map<TypeElement, Set<Element>> membersByType = new LinkedHashMap<>();
        Set<TypeElement> reflectedTypes = new LinkedHashSet<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
            collectReflectedElement(element, membersByType, reflectedTypes);
        }

        reportOrphanMembers(membersByType, reflectedTypes);

        for (TypeElement type : reflectedTypes) {
            processReflectedType(type, membersByType.getOrDefault(type, Set.of()));
        }
    }

    /** Sorts a {@code @Reflected} element into the type set or its enclosing type's member set. */
    private void collectReflectedElement(Element element,
            Map<TypeElement, Set<Element>> membersByType, Set<TypeElement> reflectedTypes) {
        if (element instanceof TypeElement typeElement) {
            reflectedTypes.add(typeElement);
            membersByType.computeIfAbsent(typeElement, k -> new LinkedHashSet<>());
        } else if (isMemberKind(element.getKind())) {
            Element enclosing = element.getEnclosingElement();
            if (enclosing instanceof TypeElement enclosingType) {
                membersByType
                        .computeIfAbsent(enclosingType, k -> new LinkedHashSet<>())
                        .add(element);
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "[garganttua-aot] @Reflected member is not enclosed by a class/interface",
                        element);
            }
        }
    }

    /** Emits an error for each {@code @Reflected} member whose enclosing type lacks {@code @Reflected}. */
    private void reportOrphanMembers(Map<TypeElement, Set<Element>> membersByType,
            Set<TypeElement> reflectedTypes) {
        for (Map.Entry<TypeElement, Set<Element>> entry : membersByType.entrySet()) {
            TypeElement type = entry.getKey();
            Set<Element> members = entry.getValue();
            if (!reflectedTypes.contains(type) && !members.isEmpty()) {
                for (Element member : members) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                            "[garganttua-aot] @Reflected on a member requires its enclosing type "
                                    + type.getQualifiedName() + " to also be annotated with @Reflected",
                            member);
                }
            }
        }
    }

    /**
     * If {@code type} bears any {@code @Indexed}-meta annotation (class- or
     * member-level) and was not already processed, generates a full descriptor
     * with broad-coverage defaults (all declared constructors and methods).
     */
    private void autoPromoteIfIndexed(TypeElement type) {
        String fqn = type.getQualifiedName().toString();
        if (processedTypes.contains(fqn) || !hasIndexedMetaAnnotation(type)) {
            return;
        }
        MemberInclusion.Flags flags = new MemberInclusion.Flags(
                true,   // queryAllDeclaredConstructors — needed for @ChildContext etc.
                false,  // queryAllPublicConstructors
                true,   // queryAllDeclaredMethods    — needed for @Expression etc.
                false,  // queryAllPublicMethods
                false   // allDeclaredFields           — conservative, opt-in via @Reflected
        );
        try {
            // strict=false: auto-promote filters privates silently.
            processTypeWithFlags(type, Set.of(), flags, false);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[garganttua-aot] Failed to auto-promote AOT class for " + fqn + ": " + e.getMessage(),
                    type);
        }
    }

    private boolean hasIndexedMetaAnnotation(TypeElement type) {
        // Class-level
        for (AnnotationMirror am : type.getAnnotationMirrors()) {
            if (isIndexedMetaAnnotated(am)) {
                return true;
            }
        }
        // Member-level (methods, fields, constructors)
        for (Element member : type.getEnclosedElements()) {
            ElementKind kind = member.getKind();
            if (kind == ElementKind.METHOD || kind == ElementKind.FIELD
                    || kind == ElementKind.CONSTRUCTOR) {
                for (AnnotationMirror am : member.getAnnotationMirrors()) {
                    if (isIndexedMetaAnnotated(am)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True if the annotation type itself bears {@code @Indexed}. */
    private boolean isIndexedMetaAnnotated(AnnotationMirror am) {
        Element annoElement = am.getAnnotationType().asElement();
        if (!(annoElement instanceof TypeElement annoTypeElement)) {
            return false;
        }
        // Don't auto-promote on the @Reflected marker itself — it's already
        // handled by pass 1 with whatever flags the user explicitly chose.
        String annoName = annoTypeElement.getQualifiedName().toString();
        if (REFLECTED_ANNOTATION.equals(annoName)) {
            return false;
        }
        for (AnnotationMirror meta : annoTypeElement.getAnnotationMirrors()) {
            Element metaElement = meta.getAnnotationType().asElement();
            if (metaElement instanceof TypeElement metaTypeElement
                    && INDEXED_ANNOTATION.equals(metaTypeElement.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMemberKind(ElementKind kind) {
        return kind == ElementKind.METHOD
                || kind == ElementKind.CONSTRUCTOR
                || kind == ElementKind.FIELD;
    }

    private void processReflectedType(TypeElement typeElement, Set<Element> explicitMembers) {
        MemberInclusion.Flags flags = new MemberInclusion.Flags(
                getAnnotationBooleanValue(typeElement, "queryAllDeclaredConstructors"),
                getAnnotationBooleanValue(typeElement, "queryAllPublicConstructors"),
                getAnnotationBooleanValue(typeElement, "queryAllDeclaredMethods"),
                getAnnotationBooleanValue(typeElement, "queryAllPublicMethods"),
                getAnnotationBooleanValue(typeElement, "allDeclaredFields"));
        try {
            // strict=true: explicit @Reflected with a private member is a user
            // intent we must enforce — emit an error.
            processTypeWithFlags(typeElement, explicitMembers, flags, true);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[garganttua-aot] Failed to generate AOT class for "
                            + typeElement.getQualifiedName() + ": " + e.getMessage(),
                    typeElement);
        }
    }

    /**
     * Shared body used by both the explicit {@code @Reflected} pass and the
     * auto-promotion pass (Indexed-meta annotations). Idempotent across rounds
     * via {@link #processedTypes}.
     *
     * @param strict when true, private members trigger an error (explicit
     *               {@code @Reflected} path). When false, private members are
     *               silently filtered out (auto-promote path — the user did
     *               not opt-in to direct binders on this type).
     */
    private void processTypeWithFlags(TypeElement typeElement, Set<Element> explicitMembers,
            MemberInclusion.Flags flags, boolean strict) throws IOException {
        String qualifiedName = typeElement.getQualifiedName().toString();
        if (!processedTypes.add(qualifiedName)) {
            return; // already generated in a previous round
        }
        List<VariableElement> fields = MemberInclusion.includedFields(typeElement, flags, explicitMembers);
        List<ExecutableElement> methods = MemberInclusion.includedMethods(typeElement, flags, explicitMembers);
        List<ExecutableElement> constructors = MemberInclusion.includedConstructors(typeElement, flags, explicitMembers);
        warnOnRedundantMembers(explicitMembers, flags);
        if (strict && rejectExplicitPrivateMembers(explicitMembers)) {
            return;
        }
        // Drop privates silently (queryAll*-pulled and auto-promote paths).
        fields = filterNonPrivate(fields);
        methods = filterNonPrivate(methods);
        constructors = filterNonPrivate(constructors);
        // TRUE package via Elements — never a string-stripped qualified name (which
        // for a nested type names the enclosing class → "clashes with package").
        String packageName = processingEnv.getElementUtils()
                .getPackageOf(typeElement).getQualifiedName().toString();

        Map<ExecutableElement, String> methodNames =
                AOTNaming.methodDescriptorNames(typeElement, packageName, methods);
        Map<ExecutableElement, String> constructorNames =
                AOTNaming.constructorDescriptorNames(typeElement, packageName, constructors);
        javax.lang.model.util.Types types = processingEnv.getTypeUtils();
        writeMemberDescriptors(typeElement, packageName, fields, methods, constructors,
                methodNames, constructorNames, types);
        // The class descriptor refers to the per-member descriptors above.
        AOTClassSourceGenerator classGen = new AOTClassSourceGenerator(
                types, typeElement, packageName, fields, methods, methodNames, constructors, constructorNames);
        writeSource(classGen.getGeneratedQualifiedName(), classGen.generate(), typeElement);
        messager.printMessage(Diagnostic.Kind.NOTE,
                "[garganttua-aot] Generated AOT descriptor: " + classGen.getGeneratedQualifiedName());
        writeListingEntry(qualifiedName, classGen.getGeneratedQualifiedName());
        this.generatedDescriptorFqns.add(classGen.getGeneratedQualifiedName());
    }

    /** Drops {@code private} members — direct binders cannot bypass Java visibility. */
    private static <E extends Element> List<E> filterNonPrivate(List<E> members) {
        return members.stream()
                .filter(m -> !m.getModifiers().contains(Modifier.PRIVATE))
                .toList();
    }

    /**
     * In the strict (explicit {@code @Reflected}) path, emits an error for each
     * explicitly-annotated private member — a clear user mistake, since direct
     * binders cannot bypass Java visibility.
     *
     * @return {@code true} if any private member was rejected (caller must abort).
     */
    private boolean rejectExplicitPrivateMembers(Set<Element> explicitMembers) {
        List<Element> explicitPrivate = new ArrayList<>();
        for (Element member : explicitMembers) {
            if (member.getModifiers().contains(Modifier.PRIVATE)) {
                explicitPrivate.add(member);
            }
        }
        for (Element offender : explicitPrivate) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[garganttua-aot] AOT direct binders cannot access private members. "
                            + "Promote this member to package-private, or remove its @Reflected annotation.",
                    offender);
        }
        return !explicitPrivate.isEmpty();
    }

    /** Emits the per-field, per-method and per-constructor descriptor source files. */
    private void writeMemberDescriptors(TypeElement typeElement, String packageName, List<VariableElement> fields,
            List<ExecutableElement> methods, List<ExecutableElement> constructors,
            Map<ExecutableElement, String> methodNames, Map<ExecutableElement, String> constructorNames,
            javax.lang.model.util.Types types) throws IOException {
        for (VariableElement field : fields) {
            AOTFieldSourceGenerator gen = new AOTFieldSourceGenerator(types, typeElement, packageName, field);
            writeSource(gen.getGeneratedQualifiedName(), gen.generate(), typeElement);
        }
        for (ExecutableElement method : methods) {
            AOTMethodSourceGenerator gen = new AOTMethodSourceGenerator(
                    types, typeElement, packageName, method, methodNames.get(method));
            writeSource(gen.getGeneratedQualifiedName(), gen.generate(), typeElement);
        }
        for (ExecutableElement ctor : constructors) {
            AOTConstructorSourceGenerator gen = new AOTConstructorSourceGenerator(
                    types, typeElement, packageName, ctor, constructorNames.get(ctor));
            writeSource(gen.getGeneratedQualifiedName(), gen.generate(), typeElement);
        }
    }

    private void warnOnRedundantMembers(Set<Element> explicitMembers, MemberInclusion.Flags flags) {
        for (Element member : explicitMembers) {
            boolean redundant = switch (member.getKind()) {
                case FIELD -> flags.allDeclaredFields();
                case METHOD -> flags.queryAllDeclaredMethods()
                        || (flags.queryAllPublicMethods() && member.getModifiers().contains(Modifier.PUBLIC));
                case CONSTRUCTOR -> flags.queryAllDeclaredConstructors()
                        || (flags.queryAllPublicConstructors() && member.getModifiers().contains(Modifier.PUBLIC));
                default -> false;
            };
            if (redundant) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        "[garganttua-aot] @Reflected on this member is redundant: "
                                + "the enclosing class already includes it via a queryAll* / allDeclaredFields flag",
                        member);
            }
        }
    }

    private void writeSource(String generatedFqn, String sourceCode, TypeElement originator) throws IOException {
        JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(generatedFqn, originator);
        try (Writer writer = sourceFile.openWriter()) {
            writer.write(sourceCode);
        }
    }

    private void writeListingEntry(String originalFqn, String generatedFqn) throws IOException {
        String resourcePath = AOT_CLASSES_DIR + originalFqn;
        FileObject fileObject = processingEnv.getFiler().createResource(
                StandardLocation.CLASS_OUTPUT, "", resourcePath);
        try (Writer writer = fileObject.openWriter();
             BufferedWriter bw = new BufferedWriter(writer)) {
            bw.write(generatedFqn);
            bw.newLine();
        }
    }

    /**
     * Writes {@code META-INF/services/com.garganttua.core.aot.commons.IAOTSelfRegistering}
     * listing every {@code AOTClass_*} generated across all rounds. This is
     * the GraalVM-native-image-friendly entry point: ServiceLoader picks the
     * service descriptor up automatically without reachability-metadata
     * configuration, triggers each class's static init, which self-registers
     * into the AOTRegistry.
     */
    private void writeSelfRegisteringServiceFile() {
        if (this.generatedDescriptorFqns.isEmpty()) {
            return;
        }
        String resourcePath = "META-INF/services/com.garganttua.core.aot.commons.IAOTSelfRegistering";
        try {
            // Merge with whatever a previous incremental round may have written.
            java.util.Set<String> all = new LinkedHashSet<>(this.generatedDescriptorFqns);
            mergeExistingServiceEntries(resourcePath, all);

            FileObject fileObject = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", resourcePath);
            try (Writer writer = fileObject.openWriter();
                 BufferedWriter bw = new BufferedWriter(writer)) {
                for (String fqn : all) {
                    bw.write(fqn);
                    bw.newLine();
                }
            }
            messager.printMessage(Diagnostic.Kind.NOTE,
                    "[garganttua-aot] Wrote ServiceLoader descriptor with "
                            + all.size() + " AOT classes");
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "[garganttua-aot] Failed to write " + resourcePath + ": " + e.getMessage());
        }
    }

    /** Adds any pre-existing, non-comment service entries from {@code resourcePath} into {@code all}. */
    private void mergeExistingServiceEntries(String resourcePath, Set<String> all) {
        FileObject existing;
        try {
            existing = processingEnv.getFiler().getResource(
                    StandardLocation.CLASS_OUTPUT, "", resourcePath);
        } catch (IOException ignored) {
            // first round; no existing file
            return;
        }
        try (BufferedReader br = new BufferedReader(existing.openReader(true))) {
            String line = br.readLine();
            while (line != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    all.add(t);
                }
                line = br.readLine();
            }
        } catch (IOException ignored) {
            // file didn't exist or unreadable — proceed with our set
        }
    }

    private boolean getAnnotationBooleanValue(TypeElement typeElement, String attributeName) {
        for (AnnotationMirror mirror : typeElement.getAnnotationMirrors()) {
            TypeElement annoElement = (TypeElement) mirror.getAnnotationType().asElement();
            if (REFLECTED_ANNOTATION.equals(annoElement.getQualifiedName().toString())) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    ExecutableElement key = entry.getKey();
                    AnnotationValue value = entry.getValue();
                    if (attributeName.equals(key.getSimpleName().toString())
                            && value.getValue() instanceof Boolean b) {
                        return b;
                    }
                }
                return false;
            }
        }
        return false;
    }
}
