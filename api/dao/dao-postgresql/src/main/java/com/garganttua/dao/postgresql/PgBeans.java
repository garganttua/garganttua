package com.garganttua.dao.postgresql;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;

/**
 * Reflection on the DTO side of the read: instantiating DTOs and embedded POJOs, and getting and
 * setting a value along a flattened field path ({@code [address, city]}).
 *
 * <p>
 * The table model hands the reader PATHS, not fields — a flattened column knows it belongs at
 * {@code address.city}, not which {@code IField} that is. Walking the path means resolving one field
 * per segment, on the DECLARED type of the previous one (the schema model only flattens concrete,
 * instantiable types, so the declared type is also the type to instantiate). Field lookups are
 * cached per class because a page of rows asks the same questions once per row.
 * </p>
 */
final class PgBeans {

    private final Map<IClass<?>, Map<String, IField>> fields = new ConcurrentHashMap<>();

    /**
     * A fresh instance through the no-arg constructor — as the MongoDB reader builds its DTOs.
     *
     * @param type the class to instantiate
     * @return the instance
     * @throws ApiException when the class has no usable no-arg constructor
     */
    Object instantiate(IClass<?> type) throws ApiException {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ApiException("Cannot instantiate " + type.getName() + " while reading PostgreSQL rows: "
                    + "a persisted DTO or embedded POJO needs a no-arg constructor (" + e + ")", e);
        }
    }

    /**
     * The persisted field of a class (or of a superclass — subclass first, as the schema model reads
     * them), made accessible.
     *
     * @param owner the class declaring or inheriting the field
     * @param name  the field name
     * @return the field
     * @throws ApiException when no such field exists — the table model and the DTO disagree
     */
    IField field(IClass<?> owner, String name) throws ApiException {
        IField field = fields.computeIfAbsent(owner, PgBeans::scan).get(name);
        if (field == null) {
            throw new ApiException("Field '" + name + "' not found on " + owner.getName()
                    + ": the PostgreSQL table model was built from a different DTO class than the one being read.");
        }
        return field;
    }

    /**
     * The field at the end of a path, starting from {@code root}.
     *
     * @param root the class the path starts from
     * @param path the field names
     * @return the last field
     * @throws ApiException when a segment does not exist
     */
    IField leaf(IClass<?> root, List<String> path) throws ApiException {
        IClass<?> current = root;
        IField field = null;
        for (String segment : path) {
            field = field(current, segment);
            current = field.getType();
        }
        if (field == null) {
            throw new ApiException("An empty field path has no field (root " + root.getName() + ")");
        }
        return field;
    }

    /**
     * Sets the value at the end of a path.
     *
     * <p>
     * With {@code create}, a null intermediate POJO is instantiated on the way down; without it, a
     * null intermediate ends the walk — writing a NULL into an object that does not exist is a no-op,
     * and must not bring the object into existence. A null never reaches a primitive field: it keeps
     * its default rather than failing the whole read.
     * </p>
     *
     * @param root   the object the path starts from
     * @param path   the field names, at least one
     * @param value  the value to set, possibly null
     * @param create whether to instantiate missing intermediate objects
     * @throws ApiException when a field cannot be set
     */
    void set(Object root, List<String> path, Object value, boolean create) throws ApiException {
        Object current = root;
        IClass<?> type = IClass.getClass(root.getClass());
        for (int i = 0; i < path.size() - 1; i++) {
            IField field = field(type, path.get(i));
            Object next = read(field, current);
            if (next == null) {
                if (!create) {
                    return;
                }
                next = instantiate(field.getType());
                write(field, current, next);
            }
            current = next;
            type = field.getType();
        }
        IField last = field(type, path.get(path.size() - 1));
        if (value == null && last.getType().isPrimitive()) {
            return;
        }
        write(last, current, value);
    }

    /**
     * The value at the end of a path.
     *
     * @param root the object the path starts from
     * @param path the field names
     * @return the value, or empty when it or an intermediate object is null
     * @throws ApiException when a field cannot be read
     */
    Optional<Object> get(Object root, List<String> path) throws ApiException {
        Object current = root;
        for (String segment : path) {
            if (current == null) {
                return Optional.empty();
            }
            current = read(field(IClass.getClass(current.getClass()), segment), current);
        }
        return Optional.ofNullable(current);
    }

    private static Object read(IField field, Object owner) throws ApiException {
        try {
            return field.get(owner);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new ApiException("Cannot read field '" + field.getName() + "' of "
                    + owner.getClass().getName() + ": " + e.getMessage(), e);
        }
    }

    private static void write(IField field, Object owner, Object value) throws ApiException {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new ApiException("Cannot set field '" + field.getName() + "' of " + owner.getClass().getName()
                    + " to a " + (value == null ? "null" : value.getClass().getName())
                    + " read from PostgreSQL: " + e.getMessage(), e);
        }
    }

    /** The persisted fields of a class hierarchy, subclass first — the same set the schema model maps. */
    private static Map<String, IField> scan(IClass<?> type) {
        Map<String, IField> found = new ConcurrentHashMap<>();
        IClass<?> current = type;
        while (current != null && !current.represents(Object.class)) {
            for (IField field : current.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || field.isSynthetic() || found.containsKey(field.getName())) {
                    continue;
                }
                field.setAccessible(true);
                found.put(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return found;
    }
}
