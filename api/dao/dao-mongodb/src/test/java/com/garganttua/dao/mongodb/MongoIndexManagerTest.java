package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;

/**
 * The index manager against a REAL mongod.
 *
 * <p>
 * Everything here is a claim about the server rather than about our code: whether a partial filter
 * is accepted at all, what {@code listIndexes} reports, whether a unique index actually refuses a
 * concurrent duplicate, and what a collection that already holds duplicates does to a unique index.
 * A mock would answer all of those the way we expected rather than the way MongoDB does — which is
 * exactly how the constraint came to be trusted without ever reaching the database.
 * </p>
 */
@DisplayName("MongoIndexManager — declared indexes, laid on a real MongoDB")
class MongoIndexManagerTest {

	private MongoDatabase database;
	private MongoCollection<Document> collection;
	private List<String> warnings;
	private IObserver<ObservableEvent> observer;

	@BeforeEach
	void setUp() {
		this.database = MongoTestServer.freshDatabase();
		this.collection = this.database.getCollection("users");
		this.warnings = Collections.synchronizedList(new ArrayList<>());
		this.observer = event -> {
			if (event instanceof LogEvent log && log.level() == LogEvent.Level.WARN) {
				this.warnings.add(log.message());
			}
		};
		Logger.global().addObserver(this.observer);
	}

	@AfterEach
	void tearDown() {
		Logger.global().removeObserver(this.observer);
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private static EntityIndexRule rule(String field, boolean unique, UnicityScope scope, IndexKind kind) {
		return new EntityIndexRule(new ObjectAddress(field), unique, scope, kind, null);
	}

	private static List<MongoIndexSpec> specs(String tenantField, EntityIndexRule... rules) {
		return MongoIndexSpec.plan(List.of(rules), UnaryOperator.identity(), tenantField);
	}

	private void ensure(MongoIndexMode mode, List<MongoIndexSpec> specs) throws ApiException {
		new MongoIndexManager(mode).ensure(this.collection, "users", specs);
	}

	private List<Document> storedIndexes() {
		List<Document> stored = new ArrayList<>();
		this.collection.listIndexes().into(stored);
		return stored;
	}

	private Optional<Document> storedIndex(String name) {
		return storedIndexes().stream().filter(index -> name.equals(index.getString("name"))).findFirst();
	}

	private String onlyWarning() {
		assertEquals(1, this.warnings.size(), () -> "expected exactly one WARN, got " + this.warnings);
		return this.warnings.get(0);
	}

	// ------------------------------------------------------------------

	@Nested
	@DisplayName("A declared unique index reaches the database")
	class UniqueIndex {

		@Test
		@DisplayName("it is created, and the database — not the application — refuses a CONCURRENT duplicate")
		void refusesAConcurrentDuplicate() throws Exception {
			ensure(MongoIndexMode.CREATE, specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard)));

			assertTrue(storedIndex("gg_email_system_standard_unique").isPresent());

			// The gap the framework check walks through: both threads read nothing, both then write.
			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			List<Thread> racers = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String id = "u" + i;
				racers.add(Thread.ofPlatform().unstarted(() -> {
					try {
						collection.insertOne(new Document("_id", id).append("email", "same@example.org"));
					} catch (RuntimeException e) {
						failures.add(e);
					}
				}));
			}
			racers.forEach(Thread::start);
			for (Thread racer : racers) {
				racer.join();
			}

			assertEquals(1, collection.countDocuments(), "exactly one of the two writes survived");
			assertEquals(1, failures.size());
			assertTrue(failures.get(0) instanceof MongoWriteException);
		}

		@Test
		@DisplayName("several documents with a NULL or absent value still pass — the partial filter "
				+ "reproduces validateUnicity, which skipped a null")
		void nullsAreNotDuplicates() throws Exception {
			ensure(MongoIndexMode.CREATE, specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard)));

			collection.insertOne(new Document("_id", "n1").append("email", null));
			collection.insertOne(new Document("_id", "n2").append("email", null));
			collection.insertOne(new Document("_id", "m1"));
			collection.insertOne(new Document("_id", "m2"));

			assertEquals(4, collection.countDocuments());
			collection.insertOne(new Document("_id", "v1").append("email", "a@b"));
			assertThrows(MongoWriteException.class,
					() -> collection.insertOne(new Document("_id", "v2").append("email", "a@b")));
		}

		@Test
		@DisplayName("a tenant-scoped index carries the tenant FIRST, so a tenant-filtered read uses it")
		void tenantComesFirst() throws Exception {
			ensure(MongoIndexMode.CREATE, specs("tenantId", rule("email", true, UnicityScope.tenant,
					IndexKind.standard)));

			Document stored = storedIndex("gg_email_tenant_standard_unique").orElseThrow();
			assertEquals(List.of("tenantId", "email"),
					List.copyOf(((Document) stored.get("key")).keySet()));

			collection.insertOne(new Document("_id", "1").append("tenantId", "t1").append("email", "e"));
			collection.insertOne(new Document("_id", "2").append("tenantId", "t2").append("email", "e"));
			assertEquals(2, collection.countDocuments(), "two tenants may hold the same value");
			assertThrows(MongoWriteException.class, () -> collection.insertOne(
					new Document("_id", "3").append("tenantId", "t1").append("email", "e")));
		}
	}

	@Nested
	@DisplayName("A database that already holds duplicates")
	class ExistingDuplicates {

		@BeforeEach
		void seedDuplicates() {
			collection.insertOne(new Document("_id", "a").append("email", "dup@example.org"));
			collection.insertOne(new Document("_id", "b").append("email", "dup@example.org"));
		}

		@Test
		@DisplayName("the application STARTS, and the WARN names the domain, the field and a query "
				+ "that lists the offending documents")
		void startsWithAWarn() throws Exception {
			ensure(MongoIndexMode.CREATE, specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard)));

			assertTrue(storedIndex("gg_email_system_standard_unique").isEmpty(),
					"the index could not be created — and nothing pretends otherwise");
			String warning = onlyWarning();
			assertTrue(warning.contains("users"), warning);
			assertTrue(warning.contains("email"), warning);
			assertTrue(warning.contains("db.getCollection(\"users\").aggregate(["), warning);
			assertTrue(warning.contains("$group"), warning);
		}

		@Test
		@DisplayName("STRICT mode refuses to start, naming the same thing")
		void strictRefusesToStart() {
			List<MongoIndexSpec> specs = specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard));

			ApiException thrown = assertThrows(ApiException.class,
					() -> ensure(MongoIndexMode.STRICT, specs));

			assertTrue(thrown.getMessage().contains("email"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains("mongodb.index.auto=strict"), thrown.getMessage());
			assertTrue(warnings.isEmpty(), "STRICT stops; it does not also warn");
		}

		@Test
		@DisplayName("the pasted aggregation groups on every key, so a tenant-scoped duplicate is "
				+ "listed per tenant")
		void duplicateQueryGroupsOnEveryKey() {
			MongoIndexSpec spec = specs("tenantId",
					rule("email", true, UnicityScope.tenant, IndexKind.standard)).get(0);

			String query = MongoIndexManager.duplicateQuery(collection, spec);

			assertTrue(query.contains("\"tenantId\":\"$tenantId\""), query);
			assertTrue(query.contains("\"email\":\"$email\""), query);
			assertTrue(query.contains("{$match:{\"email\":{$ne:null}}}"), query);
		}
	}

	@Nested
	@DisplayName("Additive only")
	class Additive {

		@Test
		@DisplayName("an existing index under the declared name is left ALONE, and the difference is named")
		void existingIndexIsNotAltered() throws Exception {
			collection.createIndex(new Document("email", 1),
					new IndexOptions().name("gg_email_system_standard_unique"));

			ensure(MongoIndexMode.CREATE, specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard)));

			Document stored = storedIndex("gg_email_system_standard_unique").orElseThrow();
			assertFalse(Boolean.TRUE.equals(stored.getBoolean("unique")),
					"the stored index still is what it was — nothing was dropped or re-created");
			assertTrue(onlyWarning().contains("left as is"), warnings.toString());
		}

		@Test
		@DisplayName("an index covering the same keys under ANOTHER name is reported, not fought over")
		void sameKeysUnderAnotherNameIsReported() throws Exception {
			collection.createIndex(new Document("email", 1), new IndexOptions().name("legacy_email"));

			ensure(MongoIndexMode.CREATE, specs(null, rule("email", false, UnicityScope.system,
					IndexKind.standard)));

			assertEquals(2, storedIndexes().size(), "_id_ and the legacy one — nothing was added");
			String warning = onlyWarning();
			assertTrue(warning.contains("legacy_email"), warning);
			assertTrue(warning.contains("left untouched"), warning);
		}

		@Test
		@DisplayName("a second start finds its own index and creates nothing — the derived name is stable")
		void secondStartIsANoOp() throws Exception {
			List<MongoIndexSpec> specs = specs("tenantId", rule("email", true, UnicityScope.tenant,
					IndexKind.standard), rule("bio", false, UnicityScope.system, IndexKind.text));
			ensure(MongoIndexMode.CREATE, specs);
			int after = storedIndexes().size();

			ensure(MongoIndexMode.CREATE, specs);

			assertEquals(after, storedIndexes().size());
			assertTrue(warnings.isEmpty(), () -> "a restart is silent, got " + warnings);
		}
	}

	@Nested
	@DisplayName("Index kinds")
	class Kinds {

		@Test
		@DisplayName("geo builds a 2dsphere that actually serves a $geoWithin query")
		void geoServesAGeoWithinQuery() throws Exception {
			ensure(MongoIndexMode.CREATE, specs(null, rule("home", false, UnicityScope.system,
					IndexKind.geo)));

			Document stored = storedIndex("gg_home_system_geo_idx").orElseThrow();
			assertEquals("2dsphere", ((Document) stored.get("key")).get("home"));

			collection.insertOne(new Document("_id", "paris").append("home", point(2.35, 48.85)));
			collection.insertOne(new Document("_id", "tokyo").append("home", point(139.69, 35.68)));

			Document aroundParis = new Document("home", new Document("$geoWithin",
					new Document("$geometry", new Document("type", "Polygon")
							.append("coordinates", List.of(List.of(List.of(2.0, 48.5), List.of(3.0, 48.5),
									List.of(3.0, 49.0), List.of(2.0, 49.0), List.of(2.0, 48.5)))))));
			assertEquals(1, collection.countDocuments(aroundParis));
			assertTrue(collection.find(aroundParis).explain().toJson().contains("2dsphere"),
					"the query plan uses the index, which is the whole point of declaring it");
		}

		private static Document point(double lon, double lat) {
			return new Document("type", "Point").append("coordinates", List.of(lon, lat));
		}

		@Test
		@DisplayName("text builds a text index that serves a $text search")
		void textServesATextSearch() throws Exception {
			ensure(MongoIndexMode.CREATE, specs(null, rule("bio", false, UnicityScope.system,
					IndexKind.text)));

			collection.insertOne(new Document("_id", "1").append("bio", "the quick brown fox"));

			assertEquals(1, collection.countDocuments(
					new Document("$text", new Document("$search", "brown"))));
		}
	}

	@Nested
	@DisplayName("Costing nothing when nothing is declared")
	class Silence {

		@Test
		@DisplayName("no declared index means not one command — the collection is not even created")
		void noDeclarationNoWrite() throws Exception {
			ensure(MongoIndexMode.CREATE, List.of());

			assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("users"),
					"a domain that declares no index starts exactly as it did before this existed");
			assertTrue(warnings.isEmpty());
		}

		@Test
		@DisplayName("a database that cannot be reached is a WARN, not a startup crash — registerDomain "
				+ "did no I/O at all before this existed")
		void unreachableDatabaseStillStarts() throws Exception {
			MongoClientSettings settings = MongoClientSettings.builder()
					.applyConnectionString(new ConnectionString("mongodb://127.0.0.1:1"))
					.applyToClusterSettings(c -> c.serverSelectionTimeout(200, TimeUnit.MILLISECONDS))
					.build();
			try (MongoClient dead = MongoClients.create(settings)) {
				MongoCollection<Document> unreachable = dead.getDatabase("nope").getCollection("users");

				new MongoIndexManager(MongoIndexMode.CREATE).ensure(unreachable, "users",
						specs(null, rule("email", true, UnicityScope.system, IndexKind.standard)));

				assertTrue(onlyWarning().contains("could not be read"), warnings.toString());
			}
		}

		@Test
		@DisplayName("NONE touches nothing, even when indexes ARE declared")
		void noneTouchesNothing() throws Exception {
			ensure(MongoIndexMode.NONE, specs(null, rule("email", true, UnicityScope.system,
					IndexKind.standard)));

			assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("users"));
			assertTrue(warnings.isEmpty());
		}
	}
}
