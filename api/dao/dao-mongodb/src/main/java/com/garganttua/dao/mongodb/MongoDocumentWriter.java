package com.garganttua.dao.mongodb;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.mongodb.DBRef;

/**
 * Write side of the {@code MongoDao} mapping: converts a domain DTO (and any embedded POJOs /
 * compositions it carries) into the {@link Document} actually persisted. Split out of
 * {@code MongoDao} so the conversion cluster stays under the god-class size gate; the public DAO
 * delegates to it and keeps its API unchanged.
 *
 * <p><b>PMD note:</b> {@code org.bson.Document} (a {@code Map} subtype) and {@code IdentityHashMap}
 * are surfaced deliberately — {@code Document} is the MongoDB driver's storage type (not an
 * interchangeable {@code Map}), and {@code IdentityHashMap}'s reference-identity semantics are the
 * whole point of the embed-cycle guard. Both are intentional, hence the narrow suppression.
 */
@SuppressWarnings({ "PMD.LooseCoupling" })
final class MongoDocumentWriter {

	private final MongoDaoConfig config;

	MongoDocumentWriter(MongoDaoConfig config) {
		this.config = config;
	}

	/**
	 * Converts a root DTO to its persistable {@link Document}: a declared-field walk up the
	 * superclass chain (skipping static/transient/null), with composition fields written as
	 * DBRef(s) and the domain uuid projected onto {@code _id} so {@code save()} upserts.
	 */
	Document dtoToDocument(Object dto) throws ApiException {
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			IClass<?> clazz = config.dtoClass();
			while (clazz != null) {
				for (IField field : clazz.getDeclaredFields()) {
					writeField(map, dto, field);
				}
				clazz = clazz.getSuperclass();
			}
			// Project the domain uuid onto _id so save() upserts (rather than always inserting →
			// duplicate rows) and delete() has a key. The uuid is kept under its own field name so
			// uuid-keyed filters (readOne / readAll by uuid) still match — _id is an added projection.
			Object uuidValue = map.get(config.uuidFieldName());
			if (uuidValue != null) {
				map.put(MongoDaoConfig.MONGO_ID, uuidValue);
			}
			return new Document(map);
		} catch (IllegalAccessException e) {
			throw new ApiException("Failed to convert DTO to MongoDB Document", e);
		}
	}

	/** Writes one root-DTO field into {@code map} (composition → DBRef, else storable value). */
	private void writeField(Map<String, Object> map, Object dto, IField field)
			throws ApiException, IllegalAccessException {
		int mods = field.getModifiers();
		if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
			return;
		}
		field.setAccessible(true);
		Object value = field.get(dto);
		if (value == null) {
			return;
		}
		String collection = config.compositions().get(field.getName());
		if (collection != null) {
			map.put(field.getName(), toReference(value, collection));
		} else {
			map.put(field.getName(), toStorable(value));
		}
	}

	/**
	 * Normalises a plain (non-composition) field value into a BSON-friendly form before storage.
	 * An {@code enum} is written as its {@code name()} so the wire shape is codec-independent and
	 * human-readable; the read side rebuilds it from the field's declared enum type. An {@code IKey}
	 * is persisted as a self-describing sub-document. Collections, arrays and maps are converted
	 * element-by-element; an <em>embedded</em> POJO (anything that is neither a native BSON scalar
	 * nor a declared {@code @Composed} DBRef) becomes a sub-{@link Document} via {@link #pojoToDocument}.
	 * The native scalars (temporal types, numbers, strings, {@code byte[]}) are handed to the driver
	 * verbatim.
	 */
	private Object toStorable(Object value) throws ApiException {
		return toStorable(value, new IdentityHashMap<>());
	}

	private Object toStorable(Object value, IdentityHashMap<Object, Boolean> visited) throws ApiException {
		if (value instanceof Enum<?> e) {
			return e.name();
		}
		if (value instanceof IKey key) {
			// No BSON codec exists for IKey — persist it as a self-describing sub-document.
			return IKeyBsonBridge.toDocument(key);
		}
		// Map/Collection are themselves in java.util.* — they must be recursed BEFORE the
		// "native by package" test below, else they would be handed to the driver raw.
		if (value instanceof Map<?, ?> map) {
			return mapToDocument(map, visited);
		}
		if (value instanceof Collection<?> elements) {
			return collectionToList(elements, visited);
		}
		if (value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
			return arrayToList(value, visited);
		}
		if (isNativeBson(value)) {
			return value;
		}
		// An embedded POJO: persist it as a sub-document (NOT a DBRef — compositions are handled
		// earlier, by field name, in dtoToDocument).
		return pojoToDocument(value, visited);
	}

	private Document mapToDocument(Map<?, ?> map, IdentityHashMap<Object, Boolean> visited) throws ApiException {
		Document sub = new Document();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			sub.put(String.valueOf(entry.getKey()), toStorable(entry.getValue(), visited));
		}
		return sub;
	}

	private List<Object> collectionToList(Collection<?> elements, IdentityHashMap<Object, Boolean> visited)
			throws ApiException {
		List<Object> converted = new ArrayList<>(elements.size());
		for (Object element : elements) {
			converted.add(toStorable(element, visited));
		}
		return converted;
	}

	private List<Object> arrayToList(Object value, IdentityHashMap<Object, Boolean> visited) throws ApiException {
		// Array of reference types (POJO[], String[]…). Primitive arrays (byte[], int[]) are
		// left native by the caller so binary/key material survives untouched.
		int length = Array.getLength(value);
		List<Object> converted = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			converted.add(toStorable(Array.get(value, i), visited));
		}
		return converted;
	}

	/**
	 * A value the MongoDB driver can store as-is: a primitive wrapper / {@link String}, an array of
	 * primitives ({@code byte[]}, {@code int[]}…), or any type from the {@code java.*}/{@code javax.*}/
	 * {@code jdk.*}/{@code org.bson.*} packages (covers {@code java.util.Date}, {@code java.time.*},
	 * {@code org.bson.types.*}). Everything else is treated as an embedded POJO to be recursed.
	 */
	private boolean isNativeBson(Object v) {
		if (v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) {
			return true;
		}
		Class<?> c = v.getClass();
		if (c.isArray()) {
			return c.getComponentType().isPrimitive();
		}
		String name = c.getName();
		return name.startsWith("java.") || name.startsWith("javax.")
				|| name.startsWith("jdk.") || name.startsWith("org.bson.");
	}

	/**
	 * Converts an embedded POJO into a sub-{@link Document} — the same declared-field walk as
	 * {@link #dtoToDocument} (up the superclass chain, skipping static/transient/null), but with NO
	 * {@code _id} projection (only the root carries {@code _id}) and NO composition handling (an
	 * embedded POJO is a pure value object). Cycles are refused with a parlant {@link ApiException}:
	 * embedded sub-documents must form a tree; a back-reference belongs in a {@code @Composed} DBRef.
	 */
	private Document pojoToDocument(Object pojo, IdentityHashMap<Object, Boolean> visited)
			throws ApiException {
		if (visited.containsKey(pojo)) {
			throw new ApiException("Cycle detected while embedding POJO of type " + pojo.getClass().getName()
					+ " — embedded sub-documents must form a tree; use a DBRef composition for back-references");
		}
		visited.put(pojo, Boolean.TRUE);
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			IClass<?> clazz = IClass.getClass(pojo.getClass());
			while (clazz != null) {
				for (IField field : clazz.getDeclaredFields()) {
					writeEmbeddedField(map, pojo, field, visited);
				}
				clazz = clazz.getSuperclass();
			}
			return new Document(map);
		} catch (IllegalAccessException e) {
			throw new ApiException("Failed to convert embedded POJO " + pojo.getClass().getName() + " to sub-document", e);
		} finally {
			// Pop: a shared instance reachable through two sibling branches of a DAG stays legal;
			// only a true ancestor cycle (still on the stack) is refused above.
			visited.remove(pojo);
		}
	}

	private void writeEmbeddedField(Map<String, Object> map, Object pojo, IField field,
			IdentityHashMap<Object, Boolean> visited) throws ApiException, IllegalAccessException {
		int mods = field.getModifiers();
		if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
			return;
		}
		field.setAccessible(true);
		Object value = field.get(pojo);
		if (value == null) {
			return;
		}
		map.put(field.getName(), toStorable(value, visited));
	}

	/**
	 * Turns a composition field value into the reference(s) actually persisted: a single
	 * {@link DBRef} for a 1-1 field, or a {@code List<DBRef>} for a 1-N ({@link Collection}) field.
	 * Only the reference is stored — the composed DTO itself lives in its own collection.
	 */
	private Object toReference(Object value, String collection) throws ApiException {
		if (value instanceof Collection<?> elements) {
			List<DBRef> refs = new ArrayList<>(elements.size());
			for (Object element : elements) {
				if (element != null) {
					refs.add(new DBRef(collection, uuidOf(element)));
				}
			}
			return refs;
		}
		return new DBRef(collection, uuidOf(value));
	}

	/** Reads the uuid carried by a composed DTO — it becomes the {@code $id} of its DBRef. */
	private Object uuidOf(Object dto) throws ApiException {
		IClass<?> clazz = IClass.getClass(dto.getClass());
		while (clazz != null) {
			try {
				IField field = clazz.getDeclaredField(config.uuidFieldName());
				field.setAccessible(true);
				return field.get(dto);
			} catch (NoSuchFieldException e) {
				clazz = clazz.getSuperclass();
			} catch (IllegalAccessException e) {
				throw new ApiException("Failed to read uuid field '" + config.uuidFieldName() + "' on composed DTO "
						+ dto.getClass().getName(), e);
			}
		}
		throw new ApiException("Composed DTO " + dto.getClass().getName() + " has no uuid field '"
				+ config.uuidFieldName() + "' to reference");
	}
}
