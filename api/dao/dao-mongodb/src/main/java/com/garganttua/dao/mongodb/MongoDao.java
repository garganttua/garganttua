package com.garganttua.dao.mongodb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.definition.DtoComposition;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.annotations.Reflected;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;

/**
 * MongoDB-backed {@link IDao}: a declarative DAO that round-trips a Garganttua domain DTO to and
 * from a single Mongo collection. The CRUD surface (find / save / delete / count) lives here; the
 * DTO ⇄ {@link Document} mapping is delegated to two cohesive collaborators — {@link MongoDocumentWriter}
 * (write side) and {@link MongoDocumentReader} (read side) — sharing the mutable {@link MongoDaoConfig}
 * populated by {@link #registerDomain}.
 *
 * <p>The domain uuid is projected onto Mongo's {@code _id} on write (so {@code save} upserts and
 * {@code delete} has a key) while staying under its own field name (so uuid-keyed filters match).
 * {@code @Composed} fields are persisted as DBRefs and eagerly resolved one level deep on read.
 *
 * <p><b>PMD note:</b> {@code org.bson.Document} (a {@code Map} subtype) is the MongoDB driver's
 * storage/decoding type, surfaced deliberately rather than via a {@code Map} interface — hence the
 * narrow {@code LooseCoupling} suppression.
 */
@Reflected
@SuppressWarnings({ "PMD.LooseCoupling" })
public class MongoDao implements IDao {

	private final MongoDatabase database;
	private final String collectionName;
	private final MongoDaoConfig config = new MongoDaoConfig();
	private final MongoDocumentWriter writer = new MongoDocumentWriter(this.config);
	private final MongoDocumentReader reader;

	public MongoDao(MongoDatabase database, String collectionName) {
		this.database = database;
		this.collectionName = collectionName;
		this.reader = new MongoDocumentReader(database, this.config);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void registerDomain(IDomainDefinition domainDefinition) {
		if (domainDefinition != null && domainDefinition.dtoDefinitions() != null && !domainDefinition.dtoDefinitions().isEmpty()) {
			IDtoDefinition<?> dtoDefinition = (IDtoDefinition<?>) domainDefinition.dtoDefinitions().get(0);
			this.config.dtoClass(dtoDefinition.dtoClass());
			if (dtoDefinition.uuid() != null) {
				this.config.uuidFieldName(dtoDefinition.uuid().getLastElement());
			}
			this.config.compositions().clear();
			for (DtoComposition composition : dtoDefinition.compositions()) {
				this.config.compositions().put(composition.field().getLastElement(), composition.collection());
			}
		}
	}

	@Override
	public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
			throws ApiException {
		return find(pageable, filter, sort, Optional.empty());
	}

	@Override
	public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
			Optional<List<String>> projection) throws ApiException {
		Bson mongoFilter = filter.map(MongoFilterConverter::convert).orElse(new Document());

		FindIterable<Document> iterable = getCollection().find(mongoFilter);

		sort.ifPresent(s -> {
			Bson mongoSort = s.getDirection() == SortDirection.asc
					? Sorts.ascending(s.getFieldName())
					: Sorts.descending(s.getFieldName());
			iterable.sort(mongoSort);
		});

		pageable.ifPresent(p -> {
			iterable.skip(p.getPageIndex() * p.getPageSize());
			iterable.limit(p.getPageSize());
		});

		applyProjection(iterable, projection);

		List<Object> results = new ArrayList<>();
		for (Document doc : iterable) {
			results.add(this.reader.documentToDto(doc));
		}
		return results;
	}

	/**
	 * Narrows the fetched fields to the requested projection (entity field names translated to their
	 * document fields) for IO savings. Always force-includes the uuid field and every composition
	 * (DBRef) field — {@code _id} is returned by default — so the read side can still map the identity
	 * and resolve references. No-op when the projection is empty.
	 */
	private void applyProjection(FindIterable<Document> iterable, Optional<List<String>> projection) {
		if (projection == null || projection.isEmpty() || projection.get().isEmpty()) {
			return;
		}
		Set<String> docFields = new LinkedHashSet<>();
		for (String entityField : projection.get()) {
			if (entityField == null || entityField.isBlank()) {
				continue;
			}
			// A dotted path a.b projects its top-level document field (Mongo returns the whole sub-doc).
			String head = entityField.contains(".")
					? entityField.substring(0, entityField.indexOf('.'))
					: entityField;
			docFields.add(translateToDtoField(head.trim()));
		}
		if (docFields.isEmpty()) {
			return;
		}
		docFields.add(this.config.uuidFieldName());
		docFields.addAll(this.config.compositions().keySet());
		iterable.projection(Projections.include(new ArrayList<>(docFields)));
	}

	/**
	 * Translates an ENTITY field name to its document (DTO) field name by reading {@code @FieldMappingRule}
	 * on the DTO fields ({@code sourceFieldAddress} = the entity field). Falls back to the same name when
	 * no rule maps it (DTO field name == entity field name).
	 */
	private String translateToDtoField(String entityField) {
		if (this.config.dtoClass() == null) {
			return entityField;
		}
		IClass<?> current = this.config.dtoClass();
		while (current != null) {
			for (IField field : current.getDeclaredFields()) {
				com.garganttua.core.mapper.annotations.FieldMappingRule[] rules =
						field.getAnnotationsByType(IClass.getClass(com.garganttua.core.mapper.annotations.FieldMappingRule.class));
				for (com.garganttua.core.mapper.annotations.FieldMappingRule rule : rules) {
					if (entityField.equals(rule.sourceFieldAddress())) {
						return field.getName();
					}
				}
			}
			current = current.getSuperclass();
		}
		return entityField;
	}

	@Override
	public Object save(Object object) throws ApiException {
		Document doc = dtoToDocument(object);
		Object id = doc.get(MongoDaoConfig.MONGO_ID);

		if (id != null) {
			getCollection().replaceOne(
					Filters.eq(MongoDaoConfig.MONGO_ID, id),
					doc,
					new ReplaceOptions().upsert(true));
		} else {
			getCollection().insertOne(doc);
		}

		return object;
	}

	@Override
	public void delete(Object object) throws ApiException {
		Document doc = dtoToDocument(object);
		Object id = doc.get(MongoDaoConfig.MONGO_ID);

		if (id == null) {
			throw new ApiException("Cannot delete document without _id");
		}

		DeleteResult result = getCollection().deleteOne(Filters.eq(MongoDaoConfig.MONGO_ID, id));
		if (result.getDeletedCount() == 0) {
			throw new ApiException("Document not found for deletion: _id=" + id);
		}
	}

	@Override
	public long count(IFilter filter) throws ApiException {
		if (filter == null) {
			return getCollection().countDocuments();
		}
		Bson mongoFilter = MongoFilterConverter.convert(filter);
		return getCollection().countDocuments(mongoFilter);
	}

	private MongoCollection<Document> getCollection() {
		return this.database.getCollection(this.collectionName);
	}

	/** Converts a root DTO to its persistable {@link Document}. Package-private for the mapping tests. */
	Document dtoToDocument(Object dto) throws ApiException {
		return this.writer.dtoToDocument(dto);
	}
}
