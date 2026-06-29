package com.garganttua.events.connectors.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.core.reflection.IClass;

/**
 * Oracle for {@link ApiEventFilter}: proves that an application-built {@link Filter} tree
 * (api-core) is evaluated correctly against an api business {@link IEvent} — by operation
 * (create/update/readAll), domain, response code, identity, payload fields and boolean composition —
 * and that a {@code null}/malformed filter is non-blocking and non-throwing.
 */
class ApiEventFilterTest {

	private static IClass<TestEntity> entity;

	@BeforeAll
	static void installReflection() {
		TestReflection.install();
		entity = IClass.getClass(TestEntity.class);
	}

	private static FakeEvent createEvent(String domain) {
		FakeEvent event = new FakeEvent();
		event.setOperation(OperationDefinition.createOneWithStandardSecurity(domain, entity));
		return event;
	}

	private static FakeEvent updateEvent(String domain) {
		FakeEvent event = new FakeEvent();
		event.setOperation(OperationDefinition.updateOneWithStandardSecurity(domain, entity));
		return event;
	}

	private static FakeEvent readAllEvent(String domain) {
		FakeEvent event = new FakeEvent();
		event.setOperation(OperationDefinition.readAllWithStandardSecurity(domain, entity));
		return event;
	}

	@Nested
	@DisplayName("operation field")
	class OperationField {

		@Test
		@DisplayName("eq(operation, create) matches a create event and rejects an update event")
		void eqOperation() {
			assertTrue(ApiEventFilter.matches(Filter.eq("operation", "create"), createEvent("contacts")));
			assertFalse(ApiEventFilter.matches(Filter.eq("operation", "create"), updateEvent("contacts")));
		}

		@Test
		@DisplayName("in(operation, create, update, readAll) matches each, rejects delete")
		void inOperation() {
			Filter filter = Filter.in("operation", "create", "update", "readAll");
			assertTrue(ApiEventFilter.matches(filter, createEvent("contacts")), "create");
			assertTrue(ApiEventFilter.matches(filter, updateEvent("contacts")), "update");
			assertTrue(ApiEventFilter.matches(filter, readAllEvent("contacts")), "readAll");

			FakeEvent deleteEvent = new FakeEvent();
			deleteEvent.setOperation(OperationDefinition.deleteOneWithStandardSecurity("contacts", entity));
			assertFalse(ApiEventFilter.matches(filter, deleteEvent), "deleteOne is not in the set");
		}

		@Test
		@DisplayName("ne(operation, create) rejects create, accepts update")
		void neOperation() {
			assertFalse(ApiEventFilter.matches(Filter.ne("operation", "create"), createEvent("contacts")));
			assertTrue(ApiEventFilter.matches(Filter.ne("operation", "create"), updateEvent("contacts")));
		}
	}

	@Nested
	@DisplayName("scalar fields")
	class ScalarFields {

		@Test
		@DisplayName("eq(domain, contacts) discriminates by domain")
		void domain() {
			assertTrue(ApiEventFilter.matches(Filter.eq("domain", "contacts"), createEvent("contacts")));
			assertFalse(ApiEventFilter.matches(Filter.eq("domain", "contacts"), createEvent("newsletters")));
		}

		@Test
		@DisplayName("eq(code, OK) discriminates by response code")
		void code() {
			FakeEvent ok = createEvent("contacts");
			ok.setCode(OperationResponseCode.OK);
			assertTrue(ApiEventFilter.matches(Filter.eq("code", "OK"), ok));
			assertFalse(ApiEventFilter.matches(Filter.eq("code", "NOT_FOUND"), ok));
		}

		@Test
		@DisplayName("tenantId / ownerId / userId resolve from the event getters")
		void identity() {
			FakeEvent event = createEvent("contacts");
			event.setTenantId("tenant-42");
			event.setOwnerId("owner-9");
			event.setUserId("user-7");
			assertTrue(ApiEventFilter.matches(Filter.eq("tenantId", "tenant-42"), event));
			assertTrue(ApiEventFilter.matches(Filter.eq("ownerId", "owner-9"), event));
			assertTrue(ApiEventFilter.matches(Filter.eq("userId", "user-7"), event));
			assertFalse(ApiEventFilter.matches(Filter.eq("tenantId", "tenant-99"), event));
		}

		@Test
		@DisplayName("empty(tenantId) matches a null tenant, regex matches a userId pattern")
		void emptyAndRegex() {
			FakeEvent event = createEvent("contacts");
			event.setUserId("user-7");
			assertTrue(ApiEventFilter.matches(Filter.empty("tenantId"), event), "no tenant set");
			assertTrue(ApiEventFilter.matches(Filter.regex("userId", "user-\\d+"), event));
			assertFalse(ApiEventFilter.matches(Filter.regex("userId", "admin-\\d+"), event));
		}
	}

	@Nested
	@DisplayName("boolean composition")
	class BooleanComposition {

		@Test
		@DisplayName("and(eq(operation,create), eq(domain,contacts)) requires both")
		void and() {
			Filter filter = Filter.and(Filter.eq("operation", "create"), Filter.eq("domain", "contacts"));
			assertTrue(ApiEventFilter.matches(filter, createEvent("contacts")));
			assertFalse(ApiEventFilter.matches(filter, createEvent("newsletters")), "wrong domain");
			assertFalse(ApiEventFilter.matches(filter, updateEvent("contacts")), "wrong operation");
		}

		@Test
		@DisplayName("or(eq(operation,create), eq(operation,update)) needs either")
		void or() {
			Filter filter = Filter.or(Filter.eq("operation", "create"), Filter.eq("operation", "update"));
			assertTrue(ApiEventFilter.matches(filter, createEvent("contacts")));
			assertTrue(ApiEventFilter.matches(filter, updateEvent("contacts")));
			assertFalse(ApiEventFilter.matches(filter, readAllEvent("contacts")));
		}

		@Test
		@DisplayName("nor(eq(operation,create), eq(operation,update)) excludes both")
		void nor() {
			Filter filter = Filter.nor(Filter.eq("operation", "create"), Filter.eq("operation", "update"));
			assertFalse(ApiEventFilter.matches(filter, createEvent("contacts")));
			assertFalse(ApiEventFilter.matches(filter, updateEvent("contacts")));
			assertTrue(ApiEventFilter.matches(filter, readAllEvent("contacts")));
		}
	}

	@Nested
	@DisplayName("payload fields")
	class PayloadFields {

		@Test
		@DisplayName("eq(in.email, ...) resolves from the IEvent.getIn() payload POJO")
		void dottedInField() {
			FakeEvent event = createEvent("contacts");
			event.setIn(new Contact("alice@example.com"));
			assertTrue(ApiEventFilter.matches(Filter.eq("in.email", "alice@example.com"), event));
			assertFalse(ApiEventFilter.matches(Filter.eq("in.email", "bob@example.com"), event));
		}

		@Test
		@DisplayName("in(in.email, ...) resolves the payload field against a set")
		void dottedInSet() {
			FakeEvent event = createEvent("contacts");
			event.setIn(new Contact("alice@example.com"));
			Filter filter = Filter.in("in.email", "alice@example.com", "carol@example.com");
			assertTrue(ApiEventFilter.matches(filter, event));
		}

		@Test
		@DisplayName("an unresolved payload field is null and fails an eq")
		void unresolvedPayloadField() {
			FakeEvent event = createEvent("contacts");
			event.setIn(new Contact("alice@example.com"));
			assertFalse(ApiEventFilter.matches(Filter.eq("in.unknown", "x"), event));
		}
	}

	@Nested
	@DisplayName("defensive behaviour")
	class Defensive {

		@Test
		@DisplayName("a null filter matches everything")
		void nullFilter() {
			assertTrue(ApiEventFilter.matches(null, createEvent("contacts")));
			assertTrue(ApiEventFilter.matches(null, new FakeEvent()));
		}

		@Test
		@DisplayName("an event with no operation does not match an operation filter (no throw)")
		void noOperation() {
			assertFalse(ApiEventFilter.matches(Filter.eq("operation", "create"), new FakeEvent()));
		}

		@Test
		@DisplayName("an unknown / malformed operator returns false without throwing")
		void malformedFilter() {
			MalformedFilter unknownOp = new MalformedFilter();
			assertFalse(ApiEventFilter.matches(unknownOp, createEvent("contacts")));
		}
	}

	/** Simple test entity — only its simple name is used for operation naming (never invoked here). */
	static final class TestEntity {
	}

	/** Test payload POJO exposing a getter the dotted {@code in.email} field resolves against. */
	static final class Contact {

		private final String email;

		Contact(String email) {
			this.email = email;
		}

		public String getEmail() {
			return this.email;
		}
	}
}
