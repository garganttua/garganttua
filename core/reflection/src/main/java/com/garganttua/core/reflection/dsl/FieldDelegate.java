package com.garganttua.core.reflection.dsl;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.core.cache.StructureCache;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IFieldValue;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.fields.FieldAccessor;
import com.garganttua.core.reflection.fields.FieldResolver;
import com.garganttua.core.reflection.fields.Fields;
import com.garganttua.core.reflection.fields.ResolvedField;
import com.garganttua.core.reflection.fields.SingleFieldValue;

/**
 * Field lookup, access, and address-resolution delegate for {@link CompositeReflection}.
 *
 * <p>
 * Walks the class hierarchy (and nested complex fields) to find fields by name,
 * by annotation, or by {@link ObjectAddress}, and reads/writes their values via
 * {@link FieldAccessor}.
 * </p>
 */
class FieldDelegate {
    private static final Logger log = Logger.getLogger(FieldDelegate.class);

    private final IReflectionProvider provider;

    /** A field resolved once for a type: its accessor, and the declared type a written value is wrapped in. */
    private record Resolved(FieldAccessor<Object> accessor, IClass<?> fieldType) {
    }

    /**
     * The fields read or written by name, per type, resolved once. Resolution is a pure function of the type and
     * the name, and types do not change; a by-name access in a hot path (the token verification, a repository)
     * used to resolve the class, parse the address, query the members and build an accessor on every call. A
     * failed resolution is not kept: it fails again, with the same exception, at the next call.
     */
    private final StructureCache<NameKey, Resolved> resolvedByName = StructureCache.named("reflection.fields-by-name");

    /** Likewise for fields read or written by address. */
    private final StructureCache<AddressKey, Resolved> resolvedByAddress =
            StructureCache.named("reflection.fields-by-address");

    private record NameKey(IClass<?> type, String name) {
    }

    private record AddressKey(IClass<?> type, ObjectAddress address) {
    }

    FieldDelegate(IReflectionProvider provider) {
        this.provider = provider;
    }

    Optional<IField> findField(IClass<?> clazz, String fieldName) {
        log.trace("Finding field {} in class: {}", fieldName, clazz.getName());
        for (IField f : clazz.getDeclaredFields()) {
            if (f.getName().equals(fieldName)) {
                return Optional.of(f);
            }
        }
        if (clazz.getSuperclass() != null) {
            return findField(clazz.getSuperclass(), fieldName);
        }
        return Optional.empty();
    }

    Optional<IField> findFieldAnnotatedWith(IClass<?> clazz, IClass<? extends Annotation> annotation) {
        log.trace("Finding field annotated with {} in class: {}", annotation.getName(), clazz.getName());
        for (IField f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(annotation)) {
                return Optional.of(f);
            }
            if (Fields.isNotPrimitiveOrInternal(f.getType()) && !clazz.equals(f.getType())) {
                Optional<IField> found = findFieldAnnotatedWith(f.getType(), annotation);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        if (clazz.getSuperclass() != null) {
            return findFieldAnnotatedWith(clazz.getSuperclass(), annotation);
        }
        return Optional.empty();
    }

    List<String> findFieldAddressesWithAnnotation(IClass<?> clazz, IClass<? extends Annotation> annotation,
            boolean linked) {
        log.trace("Finding field addresses with annotation {} in class: {}", annotation.getName(), clazz.getName());
        List<String> addresses = new ArrayList<>();
        findFieldAddressesRecursively(addresses, clazz, annotation, linked);
        return addresses;
    }

    private void findFieldAddressesRecursively(List<String> addresses, IClass<?> clazz,
            IClass<? extends Annotation> annotation, boolean linked) {
        for (IField field : clazz.getDeclaredFields()) {
            boolean found = false;
            if (field.isAnnotationPresent(annotation)) {
                addresses.add(field.getName());
                found = true;
            }
            if (((found && linked) || !linked)
                    && Fields.isNotPrimitiveOrInternal(field.getType())
                    && !clazz.equals(field.getType())) {
                findFieldAddressesRecursively(addresses, field.getType(), annotation, linked);
            }
            if (((found && linked) || !linked)
                    && Fields.isArrayOrMapOrCollectionField(field, provider)) {
                int i = 0;
                IClass<?> genericType = Fields.getGenericType(field.getType(), i, provider);
                while (genericType != null) {
                    findFieldAddressesRecursively(addresses, genericType, annotation, linked);
                    i++;
                    genericType = Fields.getGenericType(field.getType(), i, provider);
                }
            }
        }
        if (clazz.getSuperclass() != null) {
            findFieldAddressesRecursively(addresses, clazz.getSuperclass(), annotation, linked);
        }
    }

    Object getFieldValue(Object object, String fieldName) throws ReflectionException {
        return getFieldValue(object, fieldName, false);
    }

    Object getFieldValue(Object object, String fieldName, boolean force) throws ReflectionException {
        IClass<?> objectClass = provider.getClass(object.getClass());
        FieldAccessor<?> accessor = force
                ? new FieldAccessor<>(FieldResolver.fieldByFieldName(objectClass, provider, fieldName), true)
                : byName(objectClass, fieldName).accessor();
        IFieldValue<?> result = accessor.getValue(object);
        if (result.hasException()) {
            throw new ReflectionException(
                    "Cannot get field " + fieldName + " of object " + object.getClass().getName(), result.getException());
        }
        return result.first();
    }

    Object getFieldValue(Object object, IField field) throws ReflectionException {
        return getFieldValue(object, field, false);
    }

    Object getFieldValue(Object object, IField field, boolean force) throws ReflectionException {
        IClass<?> objectClass = provider.getClass(object.getClass());
        ResolvedField resolved = FieldResolver.fieldByField(objectClass, provider, field);
        var accessor = new FieldAccessor<>(resolved, force);
        IFieldValue<?> result = accessor.getValue(object);
        if (result.hasException()) {
            throw new ReflectionException(
                    "Cannot get field " + field.getName() + " of object " + object.getClass().getName(), result.getException());
        }
        return result.first();
    }

    Object getFieldValue(Object object, ObjectAddress address) throws ReflectionException {
        return getFieldValue(object, address, false);
    }

    Object getFieldValue(Object object, ObjectAddress address, boolean force) throws ReflectionException {
        IClass<?> objectClass = provider.getClass(object.getClass());
        FieldAccessor<?> accessor = force
                ? new FieldAccessor<>(FieldResolver.fieldByAddress(objectClass, provider, address), true)
                : byAddress(objectClass, address).accessor();
        IFieldValue<?> result = accessor.getValue(object);
        if (result.hasException()) {
            throw new ReflectionException(
                    "Cannot get field at address " + address + " of object " + object.getClass().getName(), result.getException());
        }
        return result.first();
    }

    void setFieldValue(Object object, ObjectAddress address, Object value) throws ReflectionException {
        setFieldValue(object, address, value, false);
    }

    void setFieldValue(Object object, ObjectAddress address, Object value, boolean force) throws ReflectionException {
        IClass<?> objectClass = provider.getClass(object.getClass());
        if (force) {
            ResolvedField resolved = FieldResolver.fieldByAddress(objectClass, provider, address);
            new FieldAccessor<>(resolved, true).setValue(object, singleValue(value, resolved.fieldType()));
            return;
        }
        Resolved resolved = byAddress(objectClass, address);
        resolved.accessor().setValue(object, singleValue(value, resolved.fieldType()));
    }

    void setFieldValue(Object object, String fieldName, Object value) throws ReflectionException {
        setFieldValue(object, fieldName, value, false);
    }

    void setFieldValue(Object object, String fieldName, Object value, boolean force) throws ReflectionException {
        IClass<?> objectClass = provider.getClass(object.getClass());
        if (force) {
            ResolvedField resolved = FieldResolver.fieldByFieldName(objectClass, provider, fieldName);
            new FieldAccessor<>(resolved, true).setValue(object, singleValue(value, resolved.fieldType()));
            return;
        }
        Resolved resolved = byName(objectClass, fieldName);
        resolved.accessor().setValue(object, singleValue(value, resolved.fieldType()));
    }

    void setFieldValue(Object object, IField field, Object value) throws ReflectionException {
        setFieldValue(object, field, value, false);
    }

    void setFieldValue(Object object, IField field, Object value, boolean force) throws ReflectionException {
        if (field == null) {
            throw new ReflectionException("Cannot set null field of object " + object.getClass().getName());
        }
        IClass<?> objectClass = provider.getClass(object.getClass());
        ResolvedField resolved = FieldResolver.fieldByField(objectClass, provider, field);
        var accessor = new FieldAccessor<>(resolved, force);
        accessor.setValue(object, singleValue(value, resolved.fieldType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> SingleFieldValue<T> singleValue(Object value, IClass<?> fieldType) {
        return SingleFieldValue.of((T) value, (IClass<T>) fieldType);
    }

    private Resolved byName(IClass<?> objectClass, String fieldName) throws ReflectionException {
        return this.resolvedByName.get(new NameKey(objectClass, fieldName),
                key -> resolved(FieldResolver.fieldByFieldName(key.type(), provider, key.name())));
    }

    private Resolved byAddress(IClass<?> objectClass, ObjectAddress address) throws ReflectionException {
        return this.resolvedByAddress.get(new AddressKey(objectClass, address),
                key -> resolved(FieldResolver.fieldByAddress(key.type(), provider, key.address())));
    }

    @SuppressWarnings("unchecked")
    private static Resolved resolved(ResolvedField field) throws ReflectionException {
        return new Resolved((FieldAccessor<Object>) (FieldAccessor<?>) new FieldAccessor<>(field, false), field.fieldType());
    }

    Optional<ObjectAddress> resolveFieldAddress(String fieldName, IClass<?> entityClass) throws ReflectionException {
        try {
            ResolvedField resolved = FieldResolver.fieldByFieldName(entityClass, provider, fieldName);
            return Optional.ofNullable(resolved.address());
        } catch (ReflectionException e) {
            return Optional.empty();
        }
    }

    Optional<ObjectAddress> resolveFieldAddress(String fieldName, IClass<?> entityClass, IClass<?> fieldType)
            throws ReflectionException {
        try {
            ResolvedField resolved = FieldResolver.fieldByFieldName(entityClass, provider, fieldName, fieldType);
            return Optional.ofNullable(resolved.address());
        } catch (ReflectionException e) {
            return Optional.empty();
        }
    }

    Optional<ObjectAddress> resolveFieldAddress(ObjectAddress address, IClass<?> entityClass) throws ReflectionException {
        try {
            ResolvedField resolved = FieldResolver.fieldByAddress(entityClass, provider, address);
            return Optional.ofNullable(resolved.address());
        } catch (ReflectionException e) {
            return Optional.empty();
        }
    }
}
