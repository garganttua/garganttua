package com.garganttua.dao.mongodb;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
 * {@code LooseCoupling}/{@code ReplaceJavaUtilDate} suppressions ({@code java.util.Date} is how the
 * driver decodes a BSON datetime).
 */
@SuppressWarnings({ "PMD.LooseCoupling", "PMD.ReplaceJavaUtilDate" })
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
	 * becomes either a {@link Map} (when the field is a {@code Map<…>}) or an embedded POJO (via
	 * {@link #documentToDto}); a {@link List} is rebuilt element-by-element on its declared element
	 * type (recovered from the field's {@code ParameterizedType}); everything scalar falls through to
	 * {@link #coerce}. The recursion carries the generic {@link Type} so nested {@code List<POJO>} /
	 * {@code Map<String,POJO>} recover their concrete element types rather than staying raw {@code Document}s.
	 */
	private Object mapValue(Type type, Object value) throws ApiException {
		if (value == null) {
			return null;
		}
		// A persisted IKey sub-document is reconstructed regardless of the (interface) target type —
		// same priority as in coerce().
		if (IKeyBsonBridge.isKeyDocument(value)) {
			return IKeyBsonBridge.fromDocument((Document) value);
		}
		Class<?> raw = rawClass(type);
		if (value instanceof Document doc) {
			return mapDocument(type, raw, doc, value);
		}
		if (value instanceof List<?> list) {
			Type elementType = typeArgument(type, 0);
			List<Object> result = new ArrayList<>(list.size());
			for (Object element : list) {
				result.add(mapValue(elementType, element));
			}
			return result;
		}
		return coerce(value, raw);
	}

	/** Maps a sub-{@link Document} to a {@link Map}, an embedded POJO, or leaves it raw when the type is unknown. */
	private Object mapDocument(Type type, Class<?> raw, Document doc, Object value) throws ApiException {
		if (raw != null && Map.class.isAssignableFrom(raw)) {
			Type valueType = typeArgument(type, 1);
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<String, Object> entry : doc.entrySet()) {
				result.put(entry.getKey(), mapValue(valueType, entry.getValue()));
			}
			return result;
		}
		if (raw != null) {
			// An embedded POJO sub-document → a concrete instance of the declared field type.
			return documentToDto(doc, IClass.getClass(raw), Map.of());
		}
		return value;
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

	/**
	 * Adapts a value decoded from BSON to the field's declared Java type — MongoDB's Document codec
	 * is lossy across the JVM type system (an enum comes back as a String, a {@code java.time.Instant}
	 * as a {@code java.util.Date}, a 32-bit field as an {@code Integer}). Handles the common, lossless
	 * cases; anything it does not recognise is returned untouched for {@code field.set} to accept or reject.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Object coerce(Object value, Class<?> target) throws ApiException {
		// A persisted IKey sub-document is reconstructed regardless of the (interface) target type.
		if (IKeyBsonBridge.isKeyDocument(value)) {
			return IKeyBsonBridge.fromDocument((Document) value);
		}
		if (value == null || target == null || target.isInstance(value)) {
			return value;
		}
		if (value instanceof org.bson.types.Binary binary && target == byte[].class) {
			// The driver decodes BSON binary back as org.bson.types.Binary, not byte[] — unwrap it,
			// else any byte[] field (token signature, key material) is unreadable.
			return binary.getData();
		}
		if (target.isEnum() && value instanceof String name) {
			return Enum.valueOf((Class<? extends Enum>) target, name);
		}
		if (value instanceof java.util.Date date) {
			return fromDate(date, target);
		}
		if (value instanceof Number number) {
			return fromNumber(number, target);
		}
		if (value instanceof String text) {
			return fromString(text, target);
		}
		return value;
	}

	/** {@code java.util.Date} (how the driver decodes a BSON datetime) → the declared {@code java.time} type, at UTC. */
	private Object fromDate(java.util.Date date, Class<?> target) {
		java.time.Instant instant = date.toInstant();
		if (target == java.time.Instant.class) {
			return instant;
		}
		if (target == java.time.LocalDateTime.class) {
			return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
		}
		if (target == java.time.LocalDate.class) {
			return java.time.LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC);
		}
		if (target == java.time.ZonedDateTime.class) {
			return instant.atZone(java.time.ZoneOffset.UTC);
		}
		if (target == java.time.OffsetDateTime.class) {
			return instant.atOffset(java.time.ZoneOffset.UTC);
		}
		return date;
	}

	/** Widens/narrows a stored {@link Number} to the declared numeric type (handles primitives too). */
	private Object fromNumber(Number number, Class<?> target) {
		if (target == Long.class || target == long.class) {
			return number.longValue();
		}
		if (target == Integer.class || target == int.class) {
			return number.intValue();
		}
		if (target == Double.class || target == double.class) {
			return number.doubleValue();
		}
		if (target == Float.class || target == float.class) {
			return number.floatValue();
		}
		if (target == Short.class || target == short.class) {
			return number.shortValue();
		}
		if (target == Byte.class || target == byte.class) {
			return number.byteValue();
		}
		return number;
	}

	/** Parses a stored {@code String} into the declared scalar type (configs occasionally land as text). */
	private Object fromString(String text, Class<?> target) {
		if (target == Integer.class || target == int.class) {
			return Integer.valueOf(text);
		}
		if (target == Long.class || target == long.class) {
			return Long.valueOf(text);
		}
		if (target == Double.class || target == double.class) {
			return Double.valueOf(text);
		}
		if (target == Float.class || target == float.class) {
			return Float.valueOf(text);
		}
		if (target == Boolean.class || target == boolean.class) {
			return Boolean.valueOf(text);
		}
		return text;
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
