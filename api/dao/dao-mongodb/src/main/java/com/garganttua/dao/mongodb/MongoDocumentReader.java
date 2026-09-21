package com.garganttua.dao.mongodb;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.bson.Document;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.mongodb.DBRef;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

/**
 * Read side of the {@code MongoDao} mapping: reconstructs a domain DTO (and its embedded POJOs /
 * eagerly-resolved compositions) from a persisted {@link Document}. Split out of {@code MongoDao}
 * so the conversion cluster stays under the god-class size gate; the public DAO delegates to it and
 * keeps its API unchanged. The symmetric inverse of {@link MongoDocumentWriter}.
 *
 * <p><b>PMD note:</b> {@code org.bson.Document} (a {@code Map} subtype) is the MongoDB driver's
 * decoding type, surfaced deliberately rather than via a {@code Map} interface — hence the narrow
 * {@code LooseCoupling} suppression. Scalar conversions live in {@link MongoValueCoercer}, container
 * construction in {@link MongoContainers}.
 */
@SuppressWarnings({ "PMD.LooseCoupling" })
final class MongoDocumentReader {

	private final MongoDatabase database;
	private final MongoDaoConfig config;

	MongoDocumentReader(MongoDatabase database, MongoDaoConfig config) {
		this.database = database;
		this.config = config;
	}

	/** Maps a root {@link Document} onto a fresh DTO, resolving the DAO's declared compositions. */
	Object documentToDto(Document doc) throws ApiException {
		return documentToDto(doc, config.dtoClass(), config.compositions());
	}

	/**
	 * Maps a Mongo {@link Document} onto a fresh instance of {@code clazz}. The {@code compositions}
	 * map drives DBRef resolution: each composition field is eagerly resolved one level deep — the
	 * referenced document(s) are read and mapped with NO further composition resolution (anti-cycle),
	 * so a graph of references can never loop.
	 */
	private Object documentToDto(Document doc, IClass<?> clazz, Map<String, String> comps) throws ApiException {
		try {
			Object instance = clazz.getDeclaredConstructor().newInstance();
			IClass<?> current = clazz;
			while (current != null) {
				for (IField field : current.getDeclaredFields()) {
					applyField(instance, field, doc, comps);
				}
				current = current.getSuperclass();
			}
			return instance;
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to convert MongoDB Document to DTO", e);
		}
	}

	/** Maps a single document field onto {@code instance}, resolving DBRefs for composition fields. */
	private void applyField(Object instance, IField field, Document doc, Map<String, String> comps)
			throws ApiException, IllegalAccessException {
		int mods = field.getModifiers();
		if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
			return;
		}
		String fieldName = field.getName();
		Object value = readRawValue(doc, fieldName);
		if (value == null && !carriesValue(doc, fieldName)) {
			return;
		}
		field.setAccessible(true);
		if (comps.containsKey(fieldName)) {
			field.set(instance, resolveReference(field, value));
		} else if (!isReference(value)) {
			// A reference reaching a non-composition field means we are one level too deep
			// (a composed DTO that itself composes): leave it null rather than mis-set it.
			setScalarField(instance, field, value, fieldName);
		}
	}

	/** Whether {@code doc} carries a value for {@code fieldName} (directly or via the {@code _id} uuid alias). */
	private boolean carriesValue(Document doc, String fieldName) {
		return doc.containsKey(fieldName)
				|| (fieldName.equals(config.uuidFieldName()) && doc.containsKey(MongoDaoConfig.MONGO_ID));
	}

	/**
	 * The stored value for {@code fieldName}: the field itself, or — for the uuid field of a document
	 * carrying only {@code _id} (written by another tool, or an _id-only projection) — {@code _id}.
	 * Returns {@code null} when neither is present (caller distinguishes "absent" via {@link #carriesValue}).
	 */
	private Object readRawValue(Document doc, String fieldName) {
		if (doc.containsKey(fieldName)) {
			return doc.get(fieldName);
		}
		if (fieldName.equals(config.uuidFieldName()) && doc.containsKey(MongoDaoConfig.MONGO_ID)) {
			return doc.get(MongoDaoConfig.MONGO_ID);
		}
		return null;
	}

	/** Coerces {@code value} to the (non-composition) field's declared type and assigns it. */
	private void setScalarField(Object instance, IField field, Object value, String fieldName)
			throws ApiException, IllegalAccessException {
		Class<?> target = rawType(field);
		Object coerced = mapValue(field.getGenericType(), value);
		try {
			field.set(instance, coerced);
		} catch (IllegalArgumentException e) {
			throw new ApiException("Cannot map field '" + fieldName + "' of " + field.getDeclaringClass().getName()
					+ ": stored " + describeType(value) + " is not assignable to declared "
					+ (target == null ? "type" : target.getName()), e);
		}
	}

	/** The declared raw type of a non-generic field, or {@code null} when it is parameterized (e.g. a {@code List<X>}). */
	private Class<?> rawType(IField field) {
		Type generic = field.getGenericType();
		return generic instanceof Class<?> raw ? raw : null;
	}

	/**
	 * Reconstructs a value decoded from BSON onto its declared (possibly generic) Java type — the
	 * symmetric read of {@code MongoDocumentWriter}'s storable conversion. A sub-{@link Document}
	 * becomes a {@link Map} (for a {@code Map<…>} or an untyped field) or an embedded POJO; a BSON array
	 * becomes the declared collection or array type ({@link MongoContainers}); scalars go through
	 * {@link MongoValueCoercer}. The recursion carries the generic {@link Type} so nested
	 * {@code List<POJO>} / {@code Map<K,POJO>} recover their concrete element and key types. A stored
	 * null (a null element, a null map value) stays null.
	 */
	private Object mapValue(Type type, Object value) throws ApiException {
		if (value == null) {
			return null;
		}
		// A persisted IKey sub-document is reconstructed regardless of the (interface) target type.
		if (IKeyBsonBridge.isKeyDocument(value)) {
			return IKeyBsonBridge.fromDocument((Document) value);
		}
		Class<?> raw = rawClass(type);
		if (value instanceof Document doc) {
			return mapDocument(type, raw, doc);
		}
		if (value instanceof List<?> list) {
			return mapList(type, raw, list);
		}
		return MongoValueCoercer.coerce(value, raw);
	}

	/** Maps a BSON array onto the declared array or collection type, each element on its declared type. */
	private Object mapList(Type type, Class<?> raw, List<?> list) throws ApiException {
		Type elementType = raw != null && raw.isArray() ? componentType(type) : typeArgument(type, 0);
		List<Object> elements = new ArrayList<>(list.size());
		for (Object element : list) {
			elements.add(mapValue(elementType, element));
		}
		if (raw != null && raw.isArray()) {
			return MongoContainers.array(raw.getComponentType(), elements);
		}
		return MongoContainers.collection(raw, elements);
	}

	/**
	 * Maps a sub-{@link Document} to a {@link Map} or an embedded POJO. A field typed {@code Object} (or
	 * an interface / abstract type, or an unresolved type variable) gets a {@link Map}: there is no class
	 * to instantiate, and a bare {@code new Object()} would silently drop the stored content.
	 */
	private Object mapDocument(Type type, Class<?> raw, Document doc) throws ApiException {
		if (raw == null || Map.class.isAssignableFrom(raw) || !isInstantiable(raw)) {
			Class<?> keyType = rawClass(typeArgument(type, 0));
			Type valueType = typeArgument(type, 1);
			Map<Object, Object> result = MongoContainers.map(raw);
			for (Map.Entry<String, Object> entry : doc.entrySet()) {
				result.put(MongoValueCoercer.coerce(entry.getKey(), keyType), mapValue(valueType, entry.getValue()));
			}
			return result;
		}
		// An embedded POJO sub-document → a concrete instance of the declared field type.
		return documentToDto(doc, IClass.getClass(raw), Map.of());
	}

	/** Whether {@code raw} is a class a sub-document can be mapped onto (not Object, not abstract). */
	private boolean isInstantiable(Class<?> raw) {
		return raw != Object.class && !raw.isInterface() && !Modifier.isAbstract(raw.getModifiers());
	}

	/** The component type of an array type ({@code String[]} → String, {@code List<X>[]} → List<X>). */
	private Type componentType(Type type) {
		if (type instanceof GenericArrayType generic) {
			return generic.getGenericComponentType();
		}
		return type instanceof Class<?> c && c.isArray() ? c.getComponentType() : Object.class;
	}

	/** The raw {@link Class} behind a possibly-parameterized {@link Type} ({@code List<X>} → {@code List}), or {@code null}. */
	private Class<?> rawClass(Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
			return c;
		}
		return null;
	}

	/** The {@code index}-th type argument of a {@link ParameterizedType} ({@code List<X>}→X, {@code Map<K,V>}→K/V), or {@code Object} if unavailable. */
	private Type typeArgument(Type type, int index) {
		if (type instanceof ParameterizedType parameterized) {
			Type[] args = parameterized.getActualTypeArguments();
			if (index < args.length) {
				return args[index];
			}
		}
		return Object.class;
	}

	private String describeType(Object value) {
		return value == null ? "null" : value.getClass().getName();
	}

	private boolean isReference(Object value) {
		if (value instanceof DBRef) {
			return true;
		}
		return value instanceof Collection<?> c && !c.isEmpty() && c.iterator().next() instanceof DBRef;
	}

	/**
	 * Resolves a composition field's stored reference(s) into the composed DTO(s): a single
	 * {@link DBRef} → one DTO ({@code field}'s type); a {@code List<DBRef>} → a {@code List} of DTOs
	 * (the field's element type). Each referenced document is fetched from its collection by uuid.
	 */
	private Object resolveReference(IField field, Object value) throws ApiException {
		if (value instanceof Collection<?> refs) {
			IClass<?> elementType = listElementType(field);
			List<Object> resolved = new ArrayList<>(refs.size());
			for (Object ref : refs) {
				if (ref instanceof DBRef dbRef) {
					Object dto = resolveOne(dbRef, elementType);
					if (dto != null) {
						resolved.add(dto);
					}
				}
			}
			return resolved;
		}
		if (value instanceof DBRef dbRef) {
			return resolveOne(dbRef, field.getType());
		}
		return null;
	}

	/** Fetches the document referenced by {@code dbRef} and maps it (one level deep, no nested resolution). */
	private Object resolveOne(DBRef dbRef, IClass<?> targetType) throws ApiException {
		Document referenced = this.database.getCollection(dbRef.getCollectionName())
				.find(Filters.eq(config.uuidFieldName(), dbRef.getId()))
				.first();
		if (referenced == null) {
			return null;
		}
		return documentToDto(referenced, targetType, Map.of());
	}

	/** The declared element type of a {@code List<X>} composition field, or {@code Object} if not parameterized. */
	private IClass<?> listElementType(IField field) {
		Type generic = field.getGenericType();
		if (generic instanceof ParameterizedType parameterized) {
			Type[] args = parameterized.getActualTypeArguments();
			if (args.length == 1 && args[0] instanceof Class<?> elementClass) {
				return IClass.getClass(elementClass);
			}
		}
		return IClass.getClass(Object.class);
	}
}
