package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

/**
 * Paging and filter values. A page must be stable over a non-unique sort key (the driver's sort is
 * not stable across skip/limit, so {@code _id} is appended as the last key), a negative page is
 * refused rather than silently turned into page 0, and a large page index must not overflow into a
 * negative skip. Filter values are converted like stored values, so a {@code BigDecimal},
 * {@code BigInteger} or {@code UUID} filter compares with what the writer stored.
 */
@DisplayName("MongoDao — paging and filter values")
class MongoDaoPagingAndFilterValuesTest {

	public static class Item {
		String uuid;
		Integer group;
	}

	@BeforeAll
	static void installReflection() {
		com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
	}

	private MongoCollection<Document> collection;
	private FindIterable<Document> find;
	private MongoDao dao;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@BeforeEach
	void setUp() {
		MongoDatabase database = mock(MongoDatabase.class);
		this.collection = mock(MongoCollection.class);
		when(database.getCollection("items")).thenReturn(this.collection);
		IDtoDefinition<Object> dto = mock(IDtoDefinition.class);
		when(dto.dtoClass()).thenReturn((IClass) IClass.getClass(Item.class));
		when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
		when(dto.compositions()).thenReturn(List.of());
		IDomainDefinition domain = mock(IDomainDefinition.class);
		when(domain.dtoDefinitions()).thenReturn(List.of(dto));
		this.dao = new MongoDao(database, "items");
		this.dao.registerDomain(domain);

		this.find = mock(FindIterable.class);
		when(this.collection.find(any(Bson.class))).thenReturn(this.find);
		MongoCursor<Document> cursor = mock(MongoCursor.class);
		when(cursor.hasNext()).thenReturn(false);
		when(this.find.iterator()).thenReturn(cursor);
	}

	private static IPageable page(int index, int size) {
		IPageable page = mock(IPageable.class);
		when(page.getPageIndex()).thenReturn(index);
		when(page.getPageSize()).thenReturn(size);
		return page;
	}

	private static ISort sort(String field, SortDirection direction) {
		ISort sort = mock(ISort.class);
		when(sort.getFieldName()).thenReturn(field);
		when(sort.getDirection()).thenReturn(direction);
		return sort;
	}

	private static IFilter field(String field, String op, Object value) {
		IFilter comparison = mock(IFilter.class);
		when(comparison.getName()).thenReturn(op);
		when(comparison.getValue()).thenReturn(value);
		IFilter node = mock(IFilter.class);
		when(node.getName()).thenReturn("$field");
		when(node.getValue()).thenReturn(field);
		when(node.getFilters()).thenReturn(List.of(comparison));
		return node;
	}

	private static BsonDocument render(Bson bson) {
		return bson.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
	}

	private BsonDocument sortSent() {
		ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
		verify(this.find).sort(captor.capture());
		return render(captor.getValue());
	}

	@Nested
	@DisplayName("sort keys")
	class SortKeys {

		@Test
		@DisplayName("a paged sort gets _id ascending as its last key")
		void pagedSortAppendsId() {
			dao.find(Optional.of(page(1, 2)), Optional.empty(), Optional.of(sort("group", SortDirection.desc)));
			BsonDocument expected = new BsonDocument("group", new BsonInt32(-1)).append("_id", new BsonInt32(1));
			assertEquals(expected, sortSent());
			assertEquals(List.of("group", "_id"), List.copyOf(sortSent().keySet()), "the requested key stays first");
		}

		@Test
		@DisplayName("a page without sort is still ordered by _id")
		void pageWithoutSort() {
			dao.find(Optional.of(page(0, 2)), Optional.empty(), Optional.empty());
			assertEquals(new BsonDocument("_id", new BsonInt32(1)), sortSent());
		}

		@Test
		@DisplayName("an unpaged sort is left as requested")
		void unpagedSort() {
			dao.find(Optional.empty(), Optional.empty(), Optional.of(sort("group", SortDirection.asc)));
			assertEquals(new BsonDocument("group", new BsonInt32(1)), sortSent());
		}

		@Test
		@DisplayName("a page of size 0 means no limit: no tiebreak key is added, nothing is skipped")
		void sizeZero() {
			dao.find(Optional.of(page(3, 0)), Optional.empty(), Optional.empty());
			verify(find, never()).sort(any(Bson.class));
			verify(find).skip(0);
		}
	}

	@Nested
	@DisplayName("page bounds")
	class Bounds {

		@Test
		@DisplayName("the skip is index × size")
		void skip() {
			dao.find(Optional.of(page(3, 25)), Optional.empty(), Optional.empty());
			verify(find).skip(75);
			verify(find).limit(25);
		}

		@Test
		@DisplayName("a negative index or size is refused, as on PostgreSQL")
		void negative() {
			assertThrows(ApiException.class, () -> dao.find(Optional.of(page(-1, 2)), Optional.empty(), Optional.empty()));
			assertThrows(ApiException.class, () -> dao.find(Optional.of(page(0, -2)), Optional.empty(), Optional.empty()));
			verify(collection, never()).find(any(Bson.class));
		}

		@Test
		@DisplayName("an index whose offset overflows an int is an empty page, not page 0")
		void overflow() {
			List<Object> rows = dao.find(Optional.of(page(Integer.MAX_VALUE, 2)), Optional.empty(), Optional.empty());
			assertTrue(rows.isEmpty());
			verify(collection, never()).find(any(Bson.class));
			assertEquals(2L * Integer.MAX_VALUE, MongoDao.offset(page(Integer.MAX_VALUE, 2)));
		}
	}

	@Nested
	@DisplayName("filter values")
	class FilterValues {

		@Test
		@DisplayName("a BigDecimal / BigInteger filter value is sent as the Decimal128 the writer stores")
		void bigNumbers() {
			assertEquals(new BsonDocument("amount", new BsonDecimal128(new Decimal128(new BigDecimal("1.50")))),
					render(MongoFilterConverter.convert(field("amount", "$eq", new BigDecimal("1.50")))));
			assertEquals(new BsonDocument("big", new BsonDocument("$gt", new BsonDecimal128(new Decimal128(new BigDecimal("12"))))),
					render(MongoFilterConverter.convert(field("big", "$gt", BigInteger.valueOf(12)))));
		}

		@Test
		@DisplayName("a filter value beyond 34 digits is refused with an ApiException naming the field")
		void tooManyDigits() {
			ApiException e = assertThrows(ApiException.class,
					() -> MongoFilterConverter.convert(field("amount", "$eq", BigInteger.TEN.pow(40))));
			assertTrue(e.getMessage().contains("'amount'"), e.getMessage());
		}

		@Test
		@DisplayName("a UUID filter value is sent as the string the writer stores")
		void uuid() {
			UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
			assertEquals(new BsonDocument("ref", new BsonString(id.toString())),
					render(MongoFilterConverter.convert(field("ref", "$eq", id))));
		}
	}
}
