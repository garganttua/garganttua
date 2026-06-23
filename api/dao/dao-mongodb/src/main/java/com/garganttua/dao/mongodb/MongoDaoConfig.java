package com.garganttua.dao.mongodb;

import java.util.HashMap;
import java.util.Map;

import com.garganttua.core.reflection.IClass;

/**
 * Mutable mapping configuration shared between a {@code MongoDao} and its read/write collaborators
 * ({@link MongoDocumentReader} / {@link MongoDocumentWriter}). Holds the DTO class, the uuid field
 * name and the composition field → target collection map, all (re)populated from the domain
 * definition by {@code MongoDao.registerDomain}. The collaborators read it live, so a re-register
 * is reflected without re-wiring them.
 */
final class MongoDaoConfig {

	/** The Mongo primary-key field; the domain uuid is projected onto it on write. */
	static final String MONGO_ID = "_id";

	private static final String DEFAULT_UUID_FIELD = "uuid";

	private IClass<?> dtoType;

	/** Field name holding the DTO uuid — also the {@code $id} carried by every DBRef this DAO emits. */
	private String uuidField = DEFAULT_UUID_FIELD;

	/** Composition field name → target collection ({@code $ref}), as declared by {@code @Composed}/{@code .composed(...)}. */
	private final Map<String, String> compositionMap = new HashMap<>();

	IClass<?> dtoClass() {
		return this.dtoType;
	}

	void dtoClass(IClass<?> dtoClass) {
		this.dtoType = dtoClass;
	}

	String uuidFieldName() {
		return this.uuidField;
	}

	void uuidFieldName(String uuidFieldName) {
		this.uuidField = uuidFieldName;
	}

	Map<String, String> compositions() {
		return this.compositionMap;
	}
}
