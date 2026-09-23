package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Translation of a declared index into what MongoDB is asked for: which document field it lands on,
 * in which key order, under which marker, and with which filter.
 */
@DisplayName("MongoIndexSpec — a declared index, translated for MongoDB")
class MongoIndexSpecTest {

	/** Stands in for the DAO's entity → document field mapping. */
	private static final UnaryOperator<String> MAPPING =
			field -> Map.of("email", "mail_address", "home", "home_loc").getOrDefault(field, field);

	private static EntityIndexRule rule(String field, boolean unique, UnicityScope scope, IndexKind kind) {
		return new EntityIndexRule(new ObjectAddress(field), unique, scope, kind, null);
	}

	@Nested
	@DisplayName("Field translation and key order")
	class KeyOrder {

		@Test
		@DisplayName("the index lands on the DOCUMENT field, not the entity field")
		void translatesTheFieldName() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.system,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals("mail_address", spec.field());
			assertEquals(new Document("mail_address", 1), spec.keys());
		}

		@Test
		@DisplayName("a tenant-scoped index puts the tenant field FIRST, so tenant reads use it too")
		void tenantComesFirst() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals(List.of("tenant_id", "mail_address"), spec.keyFields());
			assertEquals(new Document("tenant_id", 1).append("mail_address", 1), spec.keys());
			assertEquals("mail_address", spec.field(), "the declared field stays the last key");
		}

		@Test
		@DisplayName("a system-scoped index is on the field alone")
		void systemScopeIsTheFieldAlone() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.system,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals(List.of("mail_address"), spec.keyFields());
		}

		@Test
		@DisplayName("a tenant-scoped index on a domain with NO tenant field degrades to the field alone")
		void noTenantFieldDegrades() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, null);

			assertEquals(List.of("mail_address"), spec.keyFields());
		}

		@Test
		@DisplayName("a tenant-scoped index ON the tenant field itself is not composed with itself")
		void tenantFieldIsNotDuplicated() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("tenant_id", false, UnicityScope.tenant,
					IndexKind.standard), UnaryOperator.identity(), "tenant_id");

			assertEquals(List.of("tenant_id"), spec.keyFields());
		}

		@Test
		@DisplayName("plan keeps the declaration order and tolerates a null list")
		void planKeepsOrder() {
			List<MongoIndexSpec> specs = MongoIndexSpec.plan(List.of(
					rule("email", true, UnicityScope.tenant, IndexKind.standard),
					rule("home", false, UnicityScope.system, IndexKind.geo)), MAPPING, "tenant_id");

			assertEquals(List.of("mail_address", "home_loc"), specs.stream().map(MongoIndexSpec::field).toList());
			assertTrue(MongoIndexSpec.plan(null, MAPPING, "tenant_id").isEmpty());
		}
	}

	@Nested
	@DisplayName("Kind markers")
	class Kinds {

		@Test
		@DisplayName("geo is a 2dsphere key, and a tenant prefix keeps it compound")
		void geoIs2dsphere() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("home", false, UnicityScope.tenant,
					IndexKind.geo), MAPPING, "tenant_id");

			assertEquals(new Document("tenant_id", 1).append("home_loc", "2dsphere"), spec.keys());
		}

		@Test
		@DisplayName("text is a text key")
		void textIsText() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("bio", false, UnicityScope.system,
					IndexKind.text), MAPPING, "tenant_id");

			assertEquals(new Document("bio", "text"), spec.keys());
		}

		@Test
		@DisplayName("a text index is STORED as _fts/_ftsx plus weights — comparing against keys() "
				+ "would re-create it at every start")
		void textStoresDifferently() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("bio", false, UnicityScope.tenant,
					IndexKind.text), MAPPING, "tenant_id");

			assertEquals(new Document("tenant_id", 1).append("_fts", "text").append("_ftsx", 1),
					spec.storedKeys());
			assertEquals(new Document("bio", 1), spec.weights().orElseThrow());
		}

		@Test
		@DisplayName("a standard index stores exactly what it was asked for")
		void standardStoresAsAsked() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", false, UnicityScope.system,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals(spec.keys(), spec.storedKeys());
			assertTrue(spec.weights().isEmpty());
		}
	}

	@Nested
	@DisplayName("Uniqueness filter")
	class PartialFilter {

		@Test
		@DisplayName("a unique index filters on present-and-not-null, which is what validateUnicity did")
		void uniqueCarriesTheFilter() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, "tenant_id");

			Document filter = spec.partialFilter().orElseThrow();
			assertEquals(new Document("mail_address",
					new Document("$type", MongoIndexSpec.PRESENT_AND_NOT_NULL)), filter);
			assertTrue(spec.options().isUnique());
		}

		@Test
		@DisplayName("the filter guards the declared field, not the tenant prefix")
		void filterGuardsTheDeclaredFieldOnly() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals(List.of("mail_address"), List.copyOf(spec.partialFilter().orElseThrow().keySet()));
		}

		@Test
		@DisplayName("the type list excludes null — including it would index an explicit null and "
				+ "refuse the second one")
		void typeListExcludesNull() {
			assertFalse(MongoIndexSpec.PRESENT_AND_NOT_NULL.contains("null"));
			assertFalse(MongoIndexSpec.PRESENT_AND_NOT_NULL.contains("undefined"));
			assertTrue(MongoIndexSpec.PRESENT_AND_NOT_NULL.contains("string"));
		}

		@Test
		@DisplayName("a non-unique index carries no filter at all")
		void nonUniqueHasNoFilter() {
			MongoIndexSpec spec = MongoIndexSpec.of(rule("email", false, UnicityScope.system,
					IndexKind.standard), MAPPING, "tenant_id");

			assertTrue(spec.partialFilter().isEmpty());
			assertFalse(Boolean.TRUE.equals(spec.options().isUnique()));
		}
	}

	@Nested
	@DisplayName("Comparison with a stored index")
	class Comparison {

		private final MongoIndexSpec unique = MongoIndexSpec.of(
				rule("email", true, UnicityScope.system, IndexKind.standard), MAPPING, "tenant_id");

		private Document stored(boolean uniqueFlag, boolean withFilter) {
			Document doc = new Document("key", new Document("mail_address", 1)).append("name", unique.name());
			if (uniqueFlag) {
				doc.append("unique", Boolean.TRUE);
			}
			if (withFilter) {
				doc.append("partialFilterExpression", unique.partialFilter().orElseThrow());
			}
			return doc;
		}

		@Test
		@DisplayName("an identical stored index matches")
		void identicalMatches() {
			assertTrue(unique.matches(stored(true, true)));
		}

		@Test
		@DisplayName("same keys but not unique does NOT match — that is a gap worth naming")
		void uniquenessDifferenceIsADifference() {
			assertFalse(unique.matches(stored(false, false)));
			assertTrue(unique.coversSameKeysAs(stored(false, false)));
		}

		@Test
		@DisplayName("same keys and unique but no partial filter does NOT match")
		void filterDifferenceIsADifference() {
			assertFalse(unique.matches(stored(true, false)));
		}

		@Test
		@DisplayName("different keys are not the same index")
		void differentKeysDoNotMatch() {
			assertFalse(unique.coversSameKeysAs(new Document("key", new Document("other", 1))));
		}

		@Test
		@DisplayName("a stored text index is recognised by its _fts keys AND its weights")
		void textIsRecognised() {
			MongoIndexSpec text = MongoIndexSpec.of(rule("bio", false, UnicityScope.system,
					IndexKind.text), MAPPING, "tenant_id");
			Document storedText = new Document("key", new Document("_fts", "text").append("_ftsx", 1))
					.append("weights", new Document("bio", 1));

			assertTrue(text.matches(storedText));
			assertFalse(text.coversSameKeysAs(new Document("key", new Document("_fts", "text")
					.append("_ftsx", 1)).append("weights", new Document("note", 1))),
					"another text index on a different field is a different index");
		}
	}

	@Nested
	@DisplayName("Derived name")
	class Naming {

		@Test
		@DisplayName("the same declaration always derives the same name, so a restart finds its index")
		void derivedNameIsStable() {
			MongoIndexSpec first = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, "tenant_id");
			MongoIndexSpec second = MongoIndexSpec.of(rule("email", true, UnicityScope.tenant,
					IndexKind.standard), MAPPING, "tenant_id");

			assertEquals(first.name(), second.name());
			assertEquals("gg_email_tenant_standard_unique", first.name());
		}

		@Test
		@DisplayName("a declared name wins over the derived one")
		void declaredNameWins() {
			MongoIndexSpec spec = MongoIndexSpec.of(new EntityIndexRule(new ObjectAddress("email"),
					true, UnicityScope.tenant, IndexKind.standard, "my_index"), MAPPING, "tenant_id");

			assertEquals("my_index", spec.name());
		}
	}
}
