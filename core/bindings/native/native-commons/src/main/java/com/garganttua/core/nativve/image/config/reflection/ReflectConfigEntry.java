package com.garganttua.core.nativve.image.config.reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.nativve.IReflectionConfigurationEntry;
import com.garganttua.core.reflection.IClass;

/**
 * A single reflection entry in a GraalVM {@code reflect-config.json}, mapping a
 * class name to the reflective members and bulk-registration flags it requires.
 * Serialized via Jackson, omitting default-valued fields.
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ReflectConfigEntry implements IReflectionConfigurationEntry {
    /** Creates an empty entry (used by Jackson deserialization). */
    public ReflectConfigEntry() {
    }

    private static final Logger log = Logger.getLogger(ReflectConfigEntry.class);

    private String name;
    private boolean queryAllDeclaredConstructors = false;
    private boolean queryAllPublicConstructors = false;
    private boolean queryAllConstructors = false;
    private boolean queryAllDeclaredMethods = false;
    private boolean queryAllPublicMethods = false;
    private boolean queryAllMethods = false;
    private boolean allDeclaredClasses = false;
    private boolean allDeclaredFields = false;
    private boolean allPublicFields = false;
    private boolean allPublicClasses = false;
    private boolean allDeclaredConstructors = false;
    private boolean allConstructors = false;
    private boolean allDeclaredMethods = false;
    private List<Field> fields;
    private List<Method> methods;

    /**
     * Creates an entry for the given fully-qualified class name.
     *
     * @param name the fully-qualified name of the class to register for reflection
     */
    public ReflectConfigEntry(String name) {
        log.trace("Creating ReflectConfigEntry for: {}", name);
        this.name = name;
    }

    /**
     * Resolves and wraps the entry's class using the default runtime reflection provider.
     *
     * @return the {@link IClass} for this entry's {@link #getName() name}
     * @throws ClassNotFoundException if the named class is not on the classpath
     */
    @Override
    @JsonIgnore
    public IClass<?> getEntryClass() throws ClassNotFoundException {
        log.trace("Getting entry class for: {}", this.name);
        return DefaultReflectionProviders.get().getClass(Class.forName(this.name));
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean isQueryAllConstructors() {
        return queryAllConstructors;
    }

    public void setQueryAllConstructors(boolean queryAllConstructors) {
        this.queryAllConstructors = queryAllConstructors;
    }

    public boolean isQueryAllMethods() {
        return queryAllMethods;
    }

    public void setQueryAllMethods(boolean queryAllMethods) {
        this.queryAllMethods = queryAllMethods;
    }

    public boolean isAllDeclaredMethods() {
        return allDeclaredMethods;
    }

    public void setAllDeclaredMethods(boolean allDeclaredMethods) {
        this.allDeclaredMethods = allDeclaredMethods;
    }

    public boolean isQueryAllDeclaredConstructors() {
        return queryAllDeclaredConstructors;
    }

    @Override
    public void setQueryAllDeclaredConstructors(boolean queryAllDeclaredConstructors) {
        this.queryAllDeclaredConstructors = queryAllDeclaredConstructors;
    }

    public boolean isQueryAllPublicConstructors() {
        return queryAllPublicConstructors;
    }

    @Override
    public void setQueryAllPublicConstructors(boolean queryAllPublicConstructors) {
        this.queryAllPublicConstructors = queryAllPublicConstructors;
    }

    public boolean isQueryAllDeclaredMethods() {
        return queryAllDeclaredMethods;
    }

    @Override
    public void setQueryAllDeclaredMethods(boolean queryAllDeclaredMethods) {
        this.queryAllDeclaredMethods = queryAllDeclaredMethods;
    }

    public boolean isQueryAllPublicMethods() {
        return queryAllPublicMethods;
    }

    @Override
    public void setQueryAllPublicMethods(boolean queryAllPublicMethods) {
        this.queryAllPublicMethods = queryAllPublicMethods;
    }

    public boolean isAllDeclaredClasses() {
        return allDeclaredClasses;
    }

    @Override
    public void setAllDeclaredClasses(boolean allDeclaredClasses) {
        this.allDeclaredClasses = allDeclaredClasses;
    }

    public boolean isAllPublicClasses() {
        return allPublicClasses;
    }

    @Override
    public void setAllPublicClasses(boolean allPublicClasses) {
        this.allPublicClasses = allPublicClasses;
    }

    public boolean isAllDeclaredFields() {
        return allDeclaredFields;
    }

    @Override
    public void setAllDeclaredFields(boolean allDeclaredFields) {
        this.allDeclaredFields = allDeclaredFields;
    }

    public boolean isAllPublicFields() {
        return allPublicFields;
    }

    public void setAllPublicFields(boolean allPublicFields) {
        this.allPublicFields = allPublicFields;
    }

    public boolean isAllConstructors() {
        return allConstructors;
    }

    public void setAllConstructors(boolean allConstructors) {
        this.allConstructors = allConstructors;
    }

    public boolean isAllDeclaredConstructors() {
        return allDeclaredConstructors;
    }

    public void setAllDeclaredConstructors(boolean allDeclaredConstructors) {
        this.allDeclaredConstructors = allDeclaredConstructors;
    }

    @Override
    public List<Field> getFields() {
        return copyOrNull(fields);
    }

    @Override
    public void setFields(List<Field> fields) {
        this.fields = copyOrNull(fields);
    }

    @Override
    public List<Method> getMethods() {
        return copyOrNull(methods);
    }

    @Override
    public void setMethods(List<Method> methods) {
        this.methods = copyOrNull(methods);
    }

    /** Defensive shallow copy that preserves a {@code null} source (no element copying). */
    // ReturnEmptyCollectionRatherThanNull: null is contractual here — an unset fields/methods
    // list deserializes as null and is asserted as such (Jackson NON_DEFAULT round-trip).
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private static <T> List<T> copyOrNull(List<T> source) {
        if (source == null) {
            return null;
        }
        return new ArrayList<>(source);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ReflectConfigEntry that = (ReflectConfigEntry) o;
        // return queryAllDeclaredConstructors == that.queryAllDeclaredConstructors &&
        // queryAllPublicConstructors == that.queryAllPublicConstructors &&
        // queryAllDeclaredMethods == that.queryAllDeclaredMethods &&
        // queryAllPublicMethods == that.queryAllPublicMethods &&
        // allDeclaredClasses == that.allDeclaredClasses &&
        // allDeclaredFields == that.allDeclaredFields &&
        // allPublicFields == that.allPublicFields &&
        // allPublicClasses == that.allPublicClasses &&
        // allDeclaredConstructors == that.allDeclaredConstructors &&
        // allConstructors == that.allConstructors &&
        return Objects.equals(name, that.name);// &&
        // Objects.equals(fields, that.fields) &&
        // Objects.equals(methods, that.methods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, queryAllDeclaredConstructors, queryAllPublicConstructors, queryAllDeclaredMethods,
                queryAllPublicMethods,
                allDeclaredClasses, allDeclaredFields, allPublicFields, allPublicClasses, allDeclaredConstructors,
                allConstructors, fields, methods);
    }

}