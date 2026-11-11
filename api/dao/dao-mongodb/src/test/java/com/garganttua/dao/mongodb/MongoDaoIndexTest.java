package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * {@code registerDomain} laying the declared indexes on a REAL MongoDB — the whole path the
 * consumer reported as missing: a domain declares a unique field, and nothing ever asked the
 * database to hold it.
 */
@DisplayName("MongoDao.registerDomain — the declared indexes reach the database")
class MongoDaoIndexTest {

	/** The entity field {@code email} is stored under the document field {@code mail_address}. */
	public static class PersonDto {
		@FieldMappingRule(sourceFieldAddress = "uuid")
		private String uuid;
		@FieldMappingRule(sourceFieldAddress = "tenantId")
		private String tenant_ref;
		@FieldMappingRule(sourceFieldAddress = "email")
		private String mail_address;

		public String getUuid() {
			return this.uuid;
		}

		public void setUuid(String uuid) {
			this.uuid = uuid;
		}

		public String getTenant_ref() {
			return this.tenant_ref;
		}

		public void setTenant_ref(String tenantRef) {
			this.tenant_ref = tenantRef;
		}

		public String getMail_address() {
			return this.mail_address;
		}

		public void setMail_address(String mailAddress) {
			this.mail_address = mailAddress;
		}
	}

	@BeforeAll
	static void installReflection() {
		com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
	}

	private MongoDatabase database;
	private MongoCollection<Document> collection;

	@BeforeEach
	void setUp() {
		this.database = MongoTestServer.freshDatabase();
		this.collection = this.database.getCollection("people");
	}

	private MongoDao register(MongoIndexMode mode, String tenantField, EntityIndexRule... rules) {
		return register(mode, tenantField, null, null, rules);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private MongoDao register(MongoIndexMode mode, String tenantField, String entityUuid, String entityTenant,
			EntityIndexRule... rules) {
		IDtoDefinition<Object> dtoDefinition = mock(IDtoDefinition.class);
		when(dtoDefinition.dtoClass()).thenReturn((IClass) IClass.getClass(PersonDto.class));
		when(dtoDefinition.uuid()).thenReturn(new ObjectAddress("uuid"));
		when(dtoDefinition.compositions()).thenReturn(List.of());
		when(dtoDefinition.tenantId()).thenReturn(tenantField == null ? null : new ObjectAddress(tenantField));

		IEntityDefinition<Object> entityDefinition = mock(IEntityDefinition.class);
		when(entityDefinition.indexes()).thenReturn(List.of(rules));
		when(entityDefinition.uuid()).thenReturn(entityUuid == null ? null : new ObjectAddress(entityUuid));
		when(entityDefinition.tenantId()).thenReturn(entityTenant == null ? null : new ObjectAddress(entityTenant));

		IDomainDefinition domainDefinition = mock(IDomainDefinition.class);
		when(domainDefinition.domainName()).thenReturn("people");
		when(domainDefinition.dtoDefinitions()).thenReturn(List.of(dtoDefinition));
		when(domainDefinition.entityDefinition()).thenReturn(entityDefinition);

		MongoDao dao = new MongoDao(this.database, "people", mode);
		dao.registerDomain(domainDefinition);
		return dao;
	}

	private Optional<Document> storedIndex(String name) {
		List<Document> stored = new ArrayList<>();
		this.collection.listIndexes().into(stored);
		return stored.stream().filter(index -> name.equals(index.getString("name"))).findFirst();
	}

	private static EntityIndexRule rule(String field, boolean unique, UnicityScope scope, IndexKind kind) {
		return new EntityIndexRule(new ObjectAddress(field), unique, scope, kind, null);
	}

	private static PersonDto person(String uuid, String tenant, String email) {
		PersonDto dto = new PersonDto();
		dto.setUuid(uuid);
		dto.setTenant_ref(tenant);
		dto.setMail_address(email);
		return dto;
	}

	@Nested
	@DisplayName("Translation to document fields")
	class Translation {

		@Test
		@DisplayName("the index is laid on the DOCUMENT field name — one on the entity name would "
				+ "be an index no query this DAO emits could ever use")
		void indexesTheDocumentField() {
			register(MongoIndexMode.CREATE, null,
					rule("email", true, UnicityScope.system, IndexKind.standard));

			Document stored = storedIndex("gg_email_system_standard_unique").orElseThrow();
			assertEquals(List.of("mail_address"), List.copyOf(((Document) stored.get("key")).keySet()));
		}

		@Test
		@DisplayName("the tenant key is the DTO's own tenant field, first")
		void tenantKeyIsTheDtoField() {
			register(MongoIndexMode.CREATE, "tenant_ref",
					rule("email", true, UnicityScope.tenant, IndexKind.standard));

			Document stored = storedIndex("gg_email_tenant_standard_unique").orElseThrow();
			assertEquals(List.of("tenant_ref", "mail_address"),
					List.copyOf(((Document) stored.get("key")).keySet()));
		}
	}

	@Nested
	@DisplayName("The constraint is held by the store")
	class HeldByTheStore {

		@Test
		@DisplayName("two concurrent saves of the same email: one wins, the other is refused — the "
				+ "read-then-write gap no longer lets both through")
		void concurrentSavesCannotBothWin() throws Exception {
			MongoDao dao = register(MongoIndexMode.CREATE, null,
					rule("email", true, UnicityScope.system, IndexKind.standard));

			CyclicBarrier barrier = new CyclicBarrier(2);
			List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			List<Thread> racers = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				PersonDto dto = person("uuid-" + i, null, "race@example.org");
				racers.add(Thread.ofPlatform().unstarted(() -> {
					try {
						barrier.await();
						dao.save(dto);
					} catch (Exception e) {
						failures.add(e);
					}
				}));
			}
			racers.forEach(Thread::start);
			for (Thread racer : racers) {
				racer.join();
			}

			assertEquals(1, collection.countDocuments(), "the database kept exactly one");
			assertEquals(1, failures.size(), () -> "one save had to fail, got " + failures);
		}

		@Test
		@DisplayName("the refusal is a CONFLICT (409), the same answer the framework's own unicity "
				+ "check gives — not a server error")
		void databaseRefusalReadsAsAConflict() throws Exception {
			MongoDao dao = register(MongoIndexMode.CREATE, null,
					rule("email", true, UnicityScope.system, IndexKind.standard));
			dao.save(person("uuid-1", null, "taken@example.org"));

			ApiException thrown = assertThrows(ApiException.class,
					() -> dao.save(person("uuid-2", null, "taken@example.org")));

			assertEquals(ApiException.CONFLICT, thrown.getCode(),
					() -> "a duplicate the database catches must read like one the api catches: " + thrown.getMessage());
			assertTrue(thrown.getMessage().contains("people"), thrown.getMessage());
		}

		@Test
		@DisplayName("a save with no email still goes through, twice — as it did before the index")
		void nullValuesAreStillAccepted() throws Exception {
			MongoDao dao = register(MongoIndexMode.CREATE, null,
					rule("email", true, UnicityScope.system, IndexKind.standard));

			dao.save(person("uuid-1", null, null));
			dao.save(person("uuid-2", null, null));

			assertEquals(2, collection.countDocuments());
		}
	}

	@Nested
	@DisplayName("Nothing declared, nothing done")
	class Unchanged {

		@Test
		@DisplayName("a domain declaring no index writes nothing at registration")
		void noIndexNoWrite() {
			register(MongoIndexMode.CREATE, "tenant_ref");

			assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("people"));
		}

		@Test
		@DisplayName("a domain with no entity definition at all registers as it always did")
		@SuppressWarnings({ "unchecked", "rawtypes" })
		void noEntityDefinition() {
			IDtoDefinition<Object> dtoDefinition = mock(IDtoDefinition.class);
			when(dtoDefinition.dtoClass()).thenReturn((IClass) IClass.getClass(PersonDto.class));
			when(dtoDefinition.uuid()).thenReturn(new ObjectAddress("uuid"));
			when(dtoDefinition.compositions()).thenReturn(List.of());
			IDomainDefinition domainDefinition = mock(IDomainDefinition.class);
			when(domainDefinition.dtoDefinitions()).thenReturn(List.of(dtoDefinition));

			new MongoDao(database, "people").registerDomain(domainDefinition);

			assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("people"));
		}
	}

	@Nested
	@DisplayName("Startup failure")
	class Startup {

		@Test
		@DisplayName("a collection already holding duplicates does NOT stop registration in CREATE mode")
		void createStartsAnyway() {
			collection.insertOne(new Document("_id", "a").append("mail_address", "dup@example.org"));
			collection.insertOne(new Document("_id", "b").append("mail_address", "dup@example.org"));

			register(MongoIndexMode.CREATE, null,
					rule("email", true, UnicityScope.system, IndexKind.standard));

			assertTrue(storedIndex("gg_email_system_standard_unique").isEmpty());
		}

		@Test
		@DisplayName("STRICT stops registration, and says why")
		void strictStops() {
			collection.insertOne(new Document("_id", "a").append("mail_address", "dup@example.org"));
			collection.insertOne(new Document("_id", "b").append("mail_address", "dup@example.org"));

			IllegalStateException thrown = assertThrows(IllegalStateException.class,
					() -> register(MongoIndexMode.STRICT, null,
							rule("email", true, UnicityScope.system, IndexKind.standard)));

			assertTrue(thrown.getMessage().contains("mail_address"), thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("The indexes the framework itself queries by — laid whatever the entity declares")
	class FrameworkIndexes {

		private String winningPlan(Document filter) {
			return collection.find(filter).explain().toJson();
		}

		@Test
		@DisplayName("the uuid is indexed, unique; the tenant too: a read by uuid and a tenant's read no longer scan")
		void uuidAndTenant() {
			register(MongoIndexMode.CREATE, "tenant_ref", "uuid", "tenantId");
			for (int i = 0; i < 200; i++) {
				collection.insertOne(new Document("_id", "u" + i).append("uuid", "u" + i)
						.append("tenant_ref", "t" + (i % 4)));
			}

			Document uuidIndex = storedIndex("gg_uuid_system_standard_unique").orElseThrow();
			assertEquals(List.of("uuid"), List.copyOf(((Document) uuidIndex.get("key")).keySet()));
			assertTrue(uuidIndex.getBoolean("unique"));
			assertTrue(uuidIndex.getBoolean("sparse"), "sparse, not partial: the planner uses it for {uuid: value}");
			Document tenantIndex = storedIndex("gg_tenantId_system_standard_idx").orElseThrow();
			assertEquals(List.of("tenant_ref"), List.copyOf(((Document) tenantIndex.get("key")).keySet()),
					"on the DOCUMENT field the tenant filter uses");

			String byUuid = winningPlan(new Document("uuid", "u7"));
			assertTrue(byUuid.contains("IXSCAN") && !byUuid.contains("COLLSCAN"), byUuid);
			String byTenant = winningPlan(new Document("tenant_ref", "t1"));
			assertTrue(byTenant.contains("IXSCAN") && !byTenant.contains("COLLSCAN"), byTenant);
		}

		@Test
		@DisplayName("the uuid cannot be stored twice: the store refuses the second document")
		void uuidUnique() {
			register(MongoIndexMode.CREATE, null, "uuid", null);
			collection.insertOne(new Document("_id", "a").append("uuid", "same"));

			assertThrows(com.mongodb.MongoWriteException.class,
					() -> collection.insertOne(new Document("_id", "b").append("uuid", "same")));
		}

		@Test
		@DisplayName("an index the entity declares on the same key is not asked for twice")
		void declaredOnce() {
			register(MongoIndexMode.CREATE, null, "uuid", null,
					rule("uuid", true, UnicityScope.system, IndexKind.standard));

			List<Document> stored = new ArrayList<>();
			collection.listIndexes().into(stored);
			assertEquals(1, stored.stream().filter(index -> ((Document) index.get("key")).containsKey("uuid")).count());
		}

		@Test
		@DisplayName("an index the deployment laid itself on the uuid is kept, and not doubled")
		void deploymentIndexKept() {
			collection.createIndex(new Document("uuid", 1), new com.mongodb.client.model.IndexOptions().name("by_uuid"));

			register(MongoIndexMode.CREATE, null, "uuid", null);

			assertTrue(storedIndex("gg_uuid_system_standard_unique").isEmpty());
			assertTrue(storedIndex("by_uuid").isPresent());
		}

		@Test
		@DisplayName("a tenant registry, whose tenant IS its uuid, gets the uuid index only")
		void tenantIsUuid() {
			register(MongoIndexMode.CREATE, "uuid", "uuid", "uuid");

			assertTrue(storedIndex("gg_uuid_system_standard_unique").isPresent());
			assertTrue(storedIndex("gg_uuid_system_standard_idx").isEmpty());
		}

		@Test
		@DisplayName("duplicate uuids already stored: CREATE starts without the index, STRICT stops")
		void duplicates() {
			collection.insertOne(new Document("_id", "a").append("uuid", "dup"));
			collection.insertOne(new Document("_id", "b").append("uuid", "dup"));

			register(MongoIndexMode.CREATE, null, "uuid", null);
			assertTrue(storedIndex("gg_uuid_system_standard_unique").isEmpty());

			assertThrows(IllegalStateException.class, () -> register(MongoIndexMode.STRICT, null, "uuid", null));
		}

		@Test
		@DisplayName("NONE lays nothing, the framework's indexes included")
		void none() {
			register(MongoIndexMode.NONE, "tenant_ref", "uuid", "tenantId");

			assertFalse(database.listCollectionNames().into(new ArrayList<>()).contains("people"));
		}
	}
}
