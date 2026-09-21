package com.garganttua.dao.postgresql;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;

/**
 * Builds the collection a field DECLARES from the elements read out of a child table.
 *
 * <p>
 * Child rows come back as a flat, ordered list; the DTO field may be a {@code List}, a
 * {@code SortedSet}, an {@code int[]} or a concrete {@code LinkedList}. Assigning an
 * {@code ArrayList} to a {@code Set} field fails at {@code field.set}, so the declared type decides:
 * a concrete class is instantiated as is, an interface gets the JDK implementation that keeps the
 * read order ({@code LinkedHashSet}, {@code LinkedHashMap}) or the one its contract demands
 * ({@code TreeSet} for a {@code SortedSet}).
 * </p>
 */
final class PgCollections {

    private PgCollections() {
        // Static helpers
    }

    /**
     * A collection or array of the declared type holding {@code elements}, in order.
     *
     * @param declared    the field's declared type
     * @param elementType the element type (the component type for arrays)
     * @param elements    the elements
     * @return the collection, or the array
     * @throws ApiException when the declared type cannot hold the elements
     */
    static Object collection(IClass<?> declared, IClass<?> elementType, List<Object> elements) throws ApiException {
        if (declared.isArray()) {
            return array(declared.getComponentType() == null ? elementType : declared.getComponentType(), elements);
        }
        Collection<Object> out = newCollection(declared);
        out.addAll(elements);
        return out;
    }

    /**
     * A map of the declared type holding {@code entries}, in order.
     *
     * @param declared the field's declared type
     * @param entries  the entries, keys decoded
     * @return the map
     * @throws ApiException when the declared type cannot be instantiated
     */
    static Map<Object, Object> map(IClass<?> declared, List<Map.Entry<Object, Object>> entries) throws ApiException {
        Map<Object, Object> out = newMap(declared);
        for (Map.Entry<Object, Object> entry : entries) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static Object array(IClass<?> component, List<Object> elements) throws ApiException {
        Object array = Array.newInstance((Class<?>) component.getType(), elements.size());
        for (int i = 0; i < elements.size(); i++) {
            Object element = elements.get(i);
            if (element == null && component.isPrimitive()) {
                throw new ApiException("A NULL element cannot be read into a " + component.getName()
                        + "[] at position " + i + ": primitive arrays hold no null.");
            }
            Array.set(array, i, element);
        }
        return array;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(IClass<?> declared) throws ApiException {
        if (isConcrete(declared)) {
            return (Collection<Object>) instantiate(declared);
        }
        if (declared.represents(SortedSet.class) || declared.represents(NavigableSet.class)) {
            return new TreeSet<>();
        }
        if (declared.represents(Set.class)) {
            return new LinkedHashSet<>();
        }
        if (declared.represents(Queue.class) || declared.represents(Deque.class)) {
            return new ArrayDeque<>();
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMap(IClass<?> declared) throws ApiException {
        if (isConcrete(declared)) {
            return (Map<Object, Object>) instantiate(declared);
        }
        if (declared.represents(SortedMap.class) || declared.represents(NavigableMap.class)) {
            return new TreeMap<>();
        }
        return new LinkedHashMap<>();
    }

    private static boolean isConcrete(IClass<?> type) {
        return !type.isInterface() && !Modifier.isAbstract(type.getModifiers());
    }

    private static Object instantiate(IClass<?> type) throws ApiException {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ApiException("Cannot instantiate the declared collection type " + type.getName()
                    + " to hold rows of a PostgreSQL child table: " + e.getMessage(), e);
        }
    }
}
