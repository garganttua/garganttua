package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

import org.bson.BsonBinaryReader;
import org.bson.BsonBinarySubType;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;

/**
 * Save → read round trip of the values the BSON Document codec cannot give back as they were: a
 * {@code BigDecimal} (decoded as Decimal128), a {@code BigInteger} (no codec), a {@code char}, a
 * {@code LocalTime} (decoded as a Date), a {@code UUID} (no representation configured), a
 * {@code Set} / array (decoded as an ArrayList), null elements and null map values, typed map keys,
 * and an {@code Object} field holding a map.
 *
 * <p>
 * Each written document goes through a REAL BSON encode/decode with the driver's default codec
 * registry — exactly what a mongod round trip does to it — before being read back, so these tests
 * catch codec mismatches without a server.
 * </p>
 */
@DisplayName("MongoDao — save/read round trip of the values BSON decodes lossily")
class MongoDaoRoundTripTest {

	public enum Status {
		ACTIVE, CLOSED
	}

	public static class Numbers {
		String uuid;
		BigDecimal amount;
		BigInteger big;
		Object anything;
	}

	public static class Scalars {
		String uuid;
		char c;
		Character boxed;
		LocalTime at;
		UUID ref;
	}

	public static class Containers {
		String uuid;
		Set<String> labels;
		SortedSet<String> sorted;
		String[] words;
		List<String> tags;
		Map<String, String> entries;
	}

	public static class Keyed {
		String uuid;
		Map<Status, Integer> counts;
		Map<Integer, String> labels;
		Map<Long, List<String>> buckets;
	}

	public static class Untyped {
		String uuid;
		Object any;
	}

	@BeforeAll
	static void installReflection() {
		com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static MongoDao dao(Class<?> dto, MongoCollection<Document> collection) {
		MongoDatabase database = mock(MongoDatabase.class);
		when(database.getCollection("things")).thenReturn(collection);
		IDtoDefinition<Object> dtoDefinition = mock(IDtoDefinition.class);
		when(dtoDefinition.dtoClass()).thenReturn((IClass) IClass.getClass(dto));
		when(dtoDefinition.uuid()).thenReturn(new ObjectAddress("uuid"));
		when(dtoDefinition.compositions()).thenReturn(List.of());
		IDomainDefinition domain = mock(IDomainDefinition.class);
		when(domain.dtoDefinitions()).thenReturn(List.of(dtoDefinition));
		MongoDao dao = new MongoDao(database, "things");
		dao.registerDomain(domain);
		return dao;
	}

	/** What mongod hands back for {@code doc}: a real BSON encode/decode with the default codecs. */
	private static Document throughBson(Document doc) {
		DocumentCodec codec = new DocumentCodec(MongoClientSettings.getDefaultCodecRegistry());
		RawBsonDocument raw = new RawBsonDocument(doc, codec);
		return codec.decode(new BsonBinaryReader(raw.getByteBuffer().asNIO()), DecoderContext.builder().build());
	}

	/** Writes {@code dto}, sends the document through BSON, and reads it back with the same DAO mapping. */
	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(Class<T> type, T dto) throws ApiException {
		MongoCollection<Document> collection = mock(MongoCollection.class);
		MongoDao dao = dao(type, collection);
		Document stored = throughBson(dao.dtoToDocument(dto));
		FindIterable<Document> find = mock(FindIterable.class);
		when(collection.find(any(Bson.class))).thenReturn(find);
		MongoCursor<Document> cursor = mock(MongoCursor.class);
		when(cursor.hasNext()).thenReturn(true, false);
		when(cursor.next()).thenReturn(stored);
		when(find.iterator()).thenReturn(cursor);
		List<Object> read = dao.find(Optional.empty(), Optional.empty(), Optional.empty());
		assertEquals(1, read.size());
		return type.cast(read.get(0));
	}

	@SuppressWarnings("unchecked")
	private static Document written(Class<?> type, Object dto) throws ApiException {
		return dao(type, mock(MongoCollection.class)).dtoToDocument(dto);
	}

	@Nested
	@DisplayName("big numbers (Decimal128, 34 significant digits)")
	class BigNumbers {

		@Test
		@DisplayName("a BigDecimal comes back a BigDecimal with its scale (1.50 stays 1.50)")
		void bigDecimalKeepsScale() throws Exception {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.amount = new BigDecimal("1.50");
			Numbers back = roundTrip(Numbers.class, n);
			assertEquals("1.50", back.amount.toPlainString());
			assertEquals(2, back.amount.scale());
		}

		@Test
		@DisplayName("a BigDecimal of exactly 34 digits round-trips unchanged")
		void thirtyFourDigits() throws Exception {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.amount = new BigDecimal("1234567890123456789012345678901.234");
			assertEquals(n.amount, roundTrip(Numbers.class, n).amount);
		}

		@Test
		@DisplayName("a BigDecimal beyond 34 digits is refused with an ApiException naming the field and the limit")
		void tooManyDigits() {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.amount = new BigDecimal("1234567890123456789012345678901234567890.0123456789");
			ApiException e = assertThrows(ApiException.class, () -> written(Numbers.class, n));
			assertTrue(e.getMessage().contains("'amount'"), e.getMessage());
			assertTrue(e.getMessage().contains("34"), e.getMessage());
		}

		@Test
		@DisplayName("a BigInteger (no driver codec) is stored as Decimal128 and comes back a BigInteger")
		void bigInteger() throws Exception {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.big = BigInteger.TWO.pow(100);
			assertInstanceOf(Decimal128.class, written(Numbers.class, n).get("big"));
			assertEquals(BigInteger.TWO.pow(100), roundTrip(Numbers.class, n).big);
		}

		@Test
		@DisplayName("a BigInteger beyond 34 digits is refused like a BigDecimal")
		void bigIntegerTooLarge() {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.big = BigInteger.TEN.pow(40);
			ApiException e = assertThrows(ApiException.class, () -> written(Numbers.class, n));
			assertTrue(e.getMessage().contains("'big'"), e.getMessage());
		}

		@Test
		@DisplayName("a BigDecimal nested in a map names its path in the refusal")
		void nestedPath() {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.anything = Map.of("price", new BigDecimal("1" + "0".repeat(40) + ".5"));
			ApiException e = assertThrows(ApiException.class, () -> written(Numbers.class, n));
			assertTrue(e.getMessage().contains("'anything.price'"), e.getMessage());
		}

		@Test
		@DisplayName("a Decimal128 in an Object field comes back a BigDecimal, not the driver type")
		void decimalInObjectField() throws Exception {
			Numbers n = new Numbers();
			n.uuid = "n1";
			n.anything = new BigDecimal("2.500");
			assertEquals(new BigDecimal("2.500"), roundTrip(Numbers.class, n).anything);
		}
	}

	@Nested
	@DisplayName("char, LocalTime, UUID")
	class Scalar {

		@Test
		@DisplayName("char and Character come back as the same characters")
		void chars() throws Exception {
			Scalars s = new Scalars();
			s.uuid = "s1";
			s.c = '€';
			s.boxed = 'é';
			Scalars back = roundTrip(Scalars.class, s);
			assertEquals('€', back.c);
			assertEquals(Character.valueOf('é'), back.boxed);
		}

		@Test
		@DisplayName("a LocalTime (stored by the driver as a datetime) comes back as the same time of day")
		void localTime() throws Exception {
			Scalars s = new Scalars();
			s.uuid = "s1";
			s.at = LocalTime.of(23, 59, 58, 123_000_000);
			assertEquals(s.at, roundTrip(Scalars.class, s).at);
		}

		@Test
		@DisplayName("a UUID is stored as its string, whatever the client's uuidRepresentation, and read back a UUID")
		void uuid() throws Exception {
			Scalars s = new Scalars();
			s.uuid = "s1";
			s.ref = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
			assertEquals("123e4567-e89b-12d3-a456-426614174000", written(Scalars.class, s).get("ref"));
			assertEquals(s.ref, roundTrip(Scalars.class, s).ref);
		}

		@Test
		@DisplayName("a UUID stored as standard binary (subtype 4) by another client still reads back")
		void uuidFromStandardBinary() throws Exception {
			UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
			byte[] bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
					.putLong(id.getLeastSignificantBits()).array();
			Object read = MongoValueCoercer.coerce(new Binary(BsonBinarySubType.UUID_STANDARD, bytes), UUID.class);
			assertEquals(id, read);
		}
	}

	@Nested
	@DisplayName("collections, arrays and null elements")
	class CollectionFields {

		@Test
		@DisplayName("a Set comes back a Set in stored order, a SortedSet a sorted set, an array an array")
		void declaredTypes() throws Exception {
			Containers c = new Containers();
			c.uuid = "c1";
			c.labels = new LinkedHashSet<>(List.of("c", "a", "b"));
			c.sorted = new TreeSet<>(List.of("z", "m"));
			c.words = new String[] { "q", "p" };
			Containers back = roundTrip(Containers.class, c);
			assertInstanceOf(LinkedHashSet.class, back.labels);
			assertEquals(List.of("c", "a", "b"), new ArrayList<>(back.labels));
			assertInstanceOf(TreeSet.class, back.sorted);
			assertEquals(List.of("m", "z"), new ArrayList<>(back.sorted));
			assertArrayEquals(new String[] { "q", "p" }, back.words);
		}

		@Test
		@DisplayName("an empty Set stays an empty Set")
		void emptySet() throws Exception {
			Containers c = new Containers();
			c.uuid = "c1";
			c.labels = new LinkedHashSet<>();
			Containers back = roundTrip(Containers.class, c);
			assertInstanceOf(Set.class, back.labels);
			assertTrue(back.labels.isEmpty());
		}

		@Test
		@DisplayName("null list elements and null map values are stored as BSON null and read back in place")
		void nulls() throws Exception {
			Containers c = new Containers();
			c.uuid = "c1";
			c.tags = new ArrayList<>(Arrays.asList("a", null, "b"));
			c.entries = new LinkedHashMap<>();
			c.entries.put("k", null);
			c.entries.put("v", "value");
			Containers back = roundTrip(Containers.class, c);
			assertEquals(Arrays.asList("a", null, "b"), back.tags);
			assertTrue(back.entries.containsKey("k"));
			assertNull(back.entries.get("k"));
			assertEquals("value", back.entries.get("v"));
		}

		@Test
		@DisplayName("a null element in an array keeps its position")
		void nullArrayElement() throws Exception {
			Containers c = new Containers();
			c.uuid = "c1";
			c.words = new String[] { "a", null };
			assertArrayEquals(new String[] { "a", null }, roundTrip(Containers.class, c).words);
		}
	}

	@Nested
	@DisplayName("map keys and untyped fields")
	class Maps {

		@Test
		@DisplayName("enum, Integer and Long map keys come back in their declared type")
		void typedKeys() throws Exception {
			Keyed k = new Keyed();
			k.uuid = "k1";
			k.counts = new LinkedHashMap<>(Map.of(Status.ACTIVE, 3));
			k.labels = new LinkedHashMap<>(Map.of(-20, "minus twenty"));
			k.buckets = new LinkedHashMap<>(Map.of(9_000_000_000L, List.of("x")));
			Keyed back = roundTrip(Keyed.class, k);
			assertEquals(3, back.counts.get(Status.ACTIVE));
			assertEquals("minus twenty", back.labels.get(-20));
			assertEquals(List.of("x"), back.buckets.get(9_000_000_000L));
		}

		@Test
		@DisplayName("an Object field holding a (nested) map comes back as maps, not a bare Object")
		void objectHoldingMap() throws Exception {
			Untyped u = new Untyped();
			u.uuid = "u1";
			u.any = new LinkedHashMap<>(Map.of("k", "v", "inner", Map.of("n", 2)));
			Map<?, ?> back = assertInstanceOf(Map.class, roundTrip(Untyped.class, u).any);
			assertEquals("v", back.get("k"));
			assertEquals(Map.of("n", 2), back.get("inner"));
			assertInstanceOf(LinkedHashMap.class, back.get("inner"));
		}
	}
}
