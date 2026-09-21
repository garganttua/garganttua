package com.garganttua.dao.mongodb;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;

/**
 * Builds the container a DTO field DECLARES from what BSON gave back. BSON has one array type and one
 * document type: the driver decodes every array as an {@code ArrayList} and every sub-document as a
 * {@code Document}, whatever the Java field was. Handing those to a {@code Set}, {@code SortedSet},
 * {@code String[]} or {@code TreeMap} field fails, so values that were saved could not be read back.
 *
 * <p>
 * The choice of implementation mirrors the PostgreSQL DAO's {@code PgCollections} on purpose, so both
 * stores return the same Java types: a concrete declared class is instantiated; {@code SortedSet} /
 * {@code NavigableSet} become a {@link TreeSet}, any other {@code Set} a {@link LinkedHashSet}
 * (stored order kept), {@code Queue} / {@code Deque} an {@link ArrayDeque}, anything else an
 * {@link ArrayList}; {@code SortedMap} / {@code NavigableMap} a {@link TreeMap}, any other map a
 * {@link LinkedHashMap}.
 * </p>
 */
final class MongoContainers {

	private MongoContainers() {
	}

	/**
	 * The elements in a collection of the declared type.
	 *
	 * @param declared the field's raw declared type ({@code null} or {@code Object} for an untyped field)
	 * @param elements the elements, already converted, in stored order
	 * @return the collection
	 * @throws ApiException when a concrete declared type cannot be instantiated or refuses an element
	 */
	@SuppressWarnings("unchecked")
	static Collection<Object> collection(Class<?> declared, List<Object> elements) throws ApiException {
		Collection<Object> out = isConcrete(declared, Collection.class)
				? (Collection<Object>) instantiate(declared)
				: emptyCollection(declared);
		if (elements.contains(null) && refusesNull(out)) {
			throw new ApiException("A stored null element cannot be put in a " + declared.getName()
					+ ", which refuses null elements");
		}
		out.addAll(elements);
		return out;
	}

	/**
	 * The elements in an array of {@code component}.
	 *
	 * @param component the declared component type
	 * @param elements  the elements, already converted
	 * @return the array
	 * @throws ApiException when a null element meets a primitive component
	 */
	static Object array(Class<?> component, List<Object> elements) throws ApiException {
		Object array = Array.newInstance(component, elements.size());
		for (int i = 0; i < elements.size(); i++) {
			Object element = elements.get(i);
			if (element == null && component.isPrimitive()) {
				throw new ApiException("A stored null element cannot be read into a " + component.getName()
						+ "[] at position " + i + ": primitive arrays hold no null");
			}
			Array.set(array, i, element);
		}
		return array;
	}

	/**
	 * An empty map of the declared type.
	 *
	 * @param declared the field's raw declared type ({@code null} or {@code Object} for an untyped field)
	 * @return the map
	 * @throws ApiException when a concrete declared type cannot be instantiated
	 */
	@SuppressWarnings("unchecked")
	static Map<Object, Object> map(Class<?> declared) throws ApiException {
		if (isConcrete(declared, Map.class)) {
			return (Map<Object, Object>) instantiate(declared);
		}
		if (declared == SortedMap.class || declared == NavigableMap.class) {
			return new TreeMap<>();
		}
		return new LinkedHashMap<>();
	}

	private static Collection<Object> emptyCollection(Class<?> declared) {
		if (declared == SortedSet.class || declared == NavigableSet.class) {
			return new TreeSet<>();
		}
		if (declared == Set.class) {
			return new LinkedHashSet<>();
		}
		if (declared == Queue.class || declared == Deque.class) {
			return new ArrayDeque<>();
		}
		return new ArrayList<>();
	}

	/**
	 * Whether the container is one of the JDK families that reject a {@code null} element: sorted
	 * sets in natural order, array deques, priority queues, enum sets and the concurrent queues /
	 * skip-list sets. Tested up front so a stored null is reported as an {@link ApiException}
	 * instead of surfacing as a {@code NullPointerException} from {@code addAll}.
	 */
	private static boolean refusesNull(Collection<Object> out) {
		return out instanceof SortedSet<?> sorted && sorted.comparator() == null
				|| out instanceof ArrayDeque<?> || out instanceof PriorityQueue<?> || out instanceof EnumSet<?>
				|| out instanceof ConcurrentSkipListSet<?> || out instanceof BlockingQueue<?>
				|| out instanceof ConcurrentLinkedQueue<?> || out instanceof ConcurrentLinkedDeque<?>;
	}

	private static boolean isConcrete(Class<?> declared, Class<?> family) {
		return declared != null && family.isAssignableFrom(declared) && !declared.isInterface()
				&& !Modifier.isAbstract(declared.getModifiers());
	}

	private static Object instantiate(Class<?> declared) throws ApiException {
		try {
			return IClass.getClass(declared).getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new ApiException("Cannot instantiate the declared container type " + declared.getName()
					+ " (a public no-argument constructor is required)", e);
		}
	}
}
