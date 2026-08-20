package com.garganttua.api.core.integ.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.integ.crud.ReadOneByUuidInvokeRegressionTest.FilterAwareDao;
import com.garganttua.core.reflection.IClass;

/**
 * Null handling at UPDATE, through the real {@code IDomain.updateOne(uuid, body, caller)} path.
 *
 * <p>The whitelist entry decides what a {@code null} incoming value means. By default a {@code null}
 * ERASES the stored value (PUT semantics: the body describes the full state of every updatable
 * field). {@code update(field, ignoreNull = true)} opts the field into PATCH semantics: a
 * {@code null} means "not supplied" and the stored value survives.
 */
@DisplayName("Update-time null policy (entity().update(field[, authority][, ignoreNull]))")
class UpdateNullPolicyIntegrationTest extends AbstractCrudIntegrationTest {

	private final ICaller caller = Caller.createSuperCaller("superTenant");

	/** Builds a fresh users domain, seeded with one complete Alice row. */
	private IDomain<?> domainWith(UpdateDeclaration declaration) throws ApiException {
		IApiBuilder builder = newBuilder();
		var entity = builder.domain(IClass.getClass(User.class))
				.tenant(true)
				.superTenant("superTenant")
				.entity().id("id").uuid("uuid").tenantId("tenantId");
		declaration.declare(entity);
		entity.up()
				.dto(IClass.getClass(UserDto.class))
					.id("id").uuid("uuid").tenantId("tenantId")
					.db(new FilterAwareDao())
				.up()
				.security().disable(true).up()
			.up();

		IApi api = buildAndStart(builder);
		IDomain<?> users = api.getDomain("users").orElseThrow();
		User alice = new User();
		alice.setUuid("uuid-alice");
		alice.setTenantId("superTenant");
		alice.setName("Alice");
		alice.setEmail("alice@example.com");
		users.createOne(alice, caller);
		return users;
	}

	@FunctionalInterface
	private interface UpdateDeclaration {
		void declare(com.garganttua.api.commons.context.dsl.IEntityBuilder<User> entity) throws ApiException;
	}

	private static User updated(IOperationResponse response) {
		assertEquals(OperationResponseCode.UPDATED, response.getResponseCode(),
				"update should succeed. got=" + response.getResponseCode() + " / " + response.getResponse());
		return (User) response.getResponse();
	}

	/** A body that carries a new name and leaves email unset (null). */
	private static User nameOnly(String name) {
		User u = new User();
		u.setName(name);
		return u;
	}

	@Test
	@DisplayName("by default a null incoming value ERASES the stored value")
	void nullErasesByDefault() throws ApiException {
		IDomain<?> users = domainWith(e -> { e.update("name"); e.update("email"); });

		User result = updated(users.updateOne("uuid-alice", nameOnly("Alice Updated"), caller));

		assertEquals("Alice Updated", result.getName());
		assertNull(result.getEmail(), "'email' declared with the default policy — the null body value erases it");
	}

	@Test
	@DisplayName("ignoreNull = true leaves the stored value untouched")
	void ignoreNullPreservesStoredValue() throws ApiException {
		IDomain<?> users = domainWith(e -> { e.update("name"); e.update("email", true); });

		User result = updated(users.updateOne("uuid-alice", nameOnly("Alice Updated"), caller));

		assertEquals("Alice Updated", result.getName());
		assertEquals("alice@example.com", result.getEmail(),
				"'email' declared ignoreNull — a null body value means 'not supplied'");
	}

	@Test
	@DisplayName("ignoreNull = true still applies a non-null incoming value")
	void ignoreNullStillAppliesNonNull() throws ApiException {
		IDomain<?> users = domainWith(e -> { e.update("name"); e.update("email", true); });

		User body = nameOnly("Alice Updated");
		body.setEmail("new@example.com");
		User result = updated(users.updateOne("uuid-alice", body, caller));

		assertEquals("new@example.com", result.getEmail());
	}

	@Test
	@DisplayName("the policy is per-field and combines with the authority gate")
	void policyCombinesWithAuthorityGate() throws ApiException {
		IDomain<?> users = domainWith(e -> {
			e.update("name", "user-set-name", true);
			e.update("email");
		});
		ICaller withoutAuthority = new Caller("superTenant", "superTenant", "u1", "u1", true, true, List.of());

		User result = updated(users.updateOne("uuid-alice", nameOnly("Hacked"), withoutAuthority));

		assertEquals("Alice", result.getName(), "caller lacks 'user-set-name' — the field is never written");
		assertNull(result.getEmail(), "'email' is ungated and default policy — the null body value erases it");
	}
}
