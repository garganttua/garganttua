package com.garganttua.core.reflection.query;

import java.util.List;

import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.fields.Fields;

/**
 * Descends into a field to look for an element NESTED inside it, one shape of field at a time:
 * a map (key side then value side), an array, a collection, or a plain object.
 *
 * <p>
 * Extracted from {@link ObjectQuery} to keep that class within the size gate. The two halves mirror
 * each other exactly — one answers the FIRST address found, the other every address — which is why
 * they belong together and away from the resolution logic that calls them: what varies between them
 * is the arity of the answer, not the traversal.
 * </p>
 *
 * <p>
 * Each method recurses back through the owning {@link ObjectQuery}, since descending into a nested
 * type is the same resolution applied to another class with a longer base address.
 * </p>
 *
 * <p>
 * "Nothing found" is an empty list on the multi-address half and {@code null} on the single-address
 * half — the callers test both the same way, and a single address has no empty form to return.
 * </p>
 */
final class NestedFieldScan {

    private static final Logger log = Logger.getLogger(NestedFieldScan.class);

    private final ObjectQuery<?> query;
    private final IReflectionProvider provider;
    private final IClass<?> collectionIClass;
    private final IClass<?> mapIClass;

    NestedFieldScan(ObjectQuery<?> query, IReflectionProvider provider, IClass<?> collectionIClass,
            IClass<?> mapIClass) {
        this.query = query;
        this.provider = provider;
        this.collectionIClass = collectionIClass;
        this.mapIClass = mapIClass;
    }

    ObjectAddress doIfIsMap(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (mapIClass.isAssignableFrom(f.getType())) {
            log.trace("doIfIsMap checking field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> keyClass = Fields.getGenericType(f, 0, provider);
            IClass<?> valueClass = Fields.getGenericType(f, 1, provider);
            if (keyClass != null && Fields.isNotPrimitive(keyClass) && !Fields.BlackList.isBlackListed(keyClass)) {
                ObjectAddress keyAddress = address == null ? new ObjectAddress(f.getName(), true)
                        : address.addElement(f.getName());
                keyAddress = keyAddress.addElement(ObjectAddress.MAP_KEY_INDICATOR);
                ObjectAddress a = query.address(keyClass, elementName, keyAddress);
                if (a != null)
                    return a;
            }
            if (valueClass != null && Fields.isNotPrimitive(valueClass) && !Fields.BlackList.isBlackListed(valueClass)) {
                ObjectAddress valueAddress = address == null ? new ObjectAddress(f.getName(), true)
                        : address.addElement(f.getName());
                valueAddress = valueAddress.addElement(ObjectAddress.MAP_VALUE_INDICATOR);
                ObjectAddress a = query.address(valueClass, elementName, valueAddress);
                if (a != null)
                    return a;
            }
        }
        return null;
    }

    ObjectAddress doIfIsArray(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (f.getType().isArray()) {
            log.trace("doIfIsArray checking array field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> componentType = f.getType().getComponentType();
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.address(componentType, elementName, newAddress);
        }
        return null;
    }

    ObjectAddress doIfIsCollection(IField f, String elementName, ObjectAddress address)
            throws ReflectionException {
        if (collectionIClass.isAssignableFrom(f.getType())) {
            log.trace("doIfIsCollection checking field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> t = Fields.getGenericType(f, 0, provider);
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.address(t, elementName, newAddress);
        }
        return null;
    }

    ObjectAddress doIfNotEnum(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (!f.getType().isEnum() && Fields.isNotPrimitiveOrInternal(f.getType())) {
            log.trace("doIfNotEnum checking field '{}' for element '{}'", f.getName(), elementName);
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.address(f.getType(), elementName, newAddress);
        }
        return null;
    }

    List<ObjectAddress> doIfIsMapForAddresses(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (mapIClass.isAssignableFrom(f.getType())) {
            log.trace("doIfIsMapForAddresses checking field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> keyClass = Fields.getGenericType(f, 0, provider);
            IClass<?> valueClass = Fields.getGenericType(f, 1, provider);
            if (keyClass != null && Fields.isNotPrimitive(keyClass) && !Fields.BlackList.isBlackListed(keyClass)) {
                ObjectAddress keyAddress = address == null ? new ObjectAddress(f.getName(), true)
                        : address.addElement(f.getName());
                keyAddress = keyAddress.addElement(ObjectAddress.MAP_KEY_INDICATOR);
                List<ObjectAddress> a = query.addresses(keyClass, elementName, keyAddress);
                if (!a.isEmpty())
                    return a;
            }
            if (valueClass != null && Fields.isNotPrimitive(valueClass) && !Fields.BlackList.isBlackListed(valueClass)) {
                ObjectAddress valueAddress = address == null ? new ObjectAddress(f.getName(), true)
                        : address.addElement(f.getName());
                valueAddress = valueAddress.addElement(ObjectAddress.MAP_VALUE_INDICATOR);
                List<ObjectAddress> a = query.addresses(valueClass, elementName, valueAddress);
                if (!a.isEmpty())
                    return a;
            }
        }
        return List.of();
    }

    List<ObjectAddress> doIfIsArrayForAddresses(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (f.getType().isArray()) {
            log.trace("doIfIsArrayForAddresses checking array field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> componentType = f.getType().getComponentType();
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.addresses(componentType, elementName, newAddress);
        }
        return List.of();
    }

    List<ObjectAddress> doIfIsCollectionForAddresses(IField f, String elementName, ObjectAddress address)
            throws ReflectionException {
        if (collectionIClass.isAssignableFrom(f.getType())) {
            log.trace("doIfIsCollectionForAddresses checking field '{}' for element '{}'", f.getName(), elementName);
            IClass<?> t = Fields.getGenericType(f, 0, provider);
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.addresses(t, elementName, newAddress);
        }
        return List.of();
    }

    List<ObjectAddress> doIfNotEnumForAddresses(IField f, String elementName, ObjectAddress address) throws ReflectionException {
        if (!f.getType().isEnum() && Fields.isNotPrimitiveOrInternal(f.getType())) {
            log.trace("doIfNotEnumForAddresses checking field '{}' for element '{}'", f.getName(), elementName);
            ObjectAddress newAddress = address == null ? new ObjectAddress(f.getName(), true)
                    : address.addElement(f.getName());
            return query.addresses(f.getType(), elementName, newAddress);
        }
        return List.of();
    }
}
