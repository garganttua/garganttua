package com.garganttua.api.core.integ.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.entity.ShareGrant;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.Access;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.entity.ShareRequest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Sharing an entity with other tenants and owners, run through the real CRUD scripts against an
 * in-memory DAO that evaluates the filters the framework hands it — {@code $and}, {@code $or},
 * {@code $eq} and {@code $in}, with MongoDB's semantics on a list field (a value matches when SOME
 * element does). Any other operator fails loudly, so a filter this DAO does not model cannot pass.
 *
 * <p>
 * Two declarations are covered. The legacy {@code .shared(field)} names a tenant (or a list of them)
 * and grants READ only. {@code .shares(field)} carries {@link ShareGrant} tokens, written only by the
 * {@code share} operation, granting read or write to tenants and owners.
 * </p>
 */
@DisplayName("Sharing entities with tenants and owners")
class SharingIntegrationTest extends AbstractCrudScriptTest {

	private static final String A = "TENANT_A";
	private static final String B = "TENANT_B";
	private static final String C = "TENANT_C";
	private static final String D = "TENANT_D";
	private static final String ALICE = "users:alice";
	private static final String BOB = "users:bob";
	private static final String CAROL = "users:carol";
	private static final String DAVE = "users:dave";

	// ───── Entities / DTOs ─────

	public static class Note {
		private String id;
		private String uuid;
		private String tenantId;
		private String ownerId;
		private String sharedWith;
		private List<String> shares;
		private String title;

		public Note() {}
		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public String getOwnerId() { return ownerId; }
		public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
		public String getSharedWith() { return sharedWith; }
		public void setSharedWith(String sharedWith) { this.sharedWith = sharedWith; }
		public List<String> getShares() { return shares; }
		public void setShares(List<String> shares) { this.shares = shares; }
		public String getTitle() { return title; }
		public void setTitle(String title) { this.title = title; }
	}

	public static class NoteDto {
		@FieldMappingRule(sourceFieldAddress = "id")
		private String id;
		@FieldMappingRule(sourceFieldAddress = "uuid")
		private String uuid;
		@FieldMappingRule(sourceFieldAddress = "tenantId")
		private String tenantId;
		@FieldMappingRule(sourceFieldAddress = "ownerId")
		private String ownerId;
		@FieldMappingRule(sourceFieldAddress = "sharedWith")
		private String sharedWith;
		@FieldMappingRule(sourceFieldAddress = "shares")
		private List<String> shares;
		@FieldMappingRule(sourceFieldAddress = "title")
		private String title;

		public NoteDto() {}
		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public String getOwnerId() { return ownerId; }
		public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
		public String getSharedWith() { return sharedWith; }
		public void setSharedWith(String sharedWith) { this.sharedWith = sharedWith; }
		public List<String> getShares() { return shares; }
		public void setShares(List<String> shares) { this.shares = shares; }
		public String getTitle() { return title; }
		public void setTitle(String title) { this.title = title; }
	}

	/** The legacy shared field as a list of tenants. */
	public static class Memo {
		private String id;
		private String uuid;
		private String tenantId;
		private List<String> sharedWith;

		public Memo() {}
		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public List<String> getSharedWith() { return sharedWith; }
		public void setSharedWith(List<String> sharedWith) { this.sharedWith = sharedWith; }
	}

	public static class MemoDto {
		@FieldMappingRule(sourceFieldAddress = "id")
		private String id;
		@FieldMappingRule(sourceFieldAddress = "uuid")
		private String uuid;
		@FieldMappingRule(sourceFieldAddress = "tenantId")
		private String tenantId;
		@FieldMappingRule(sourceFieldAddress = "sharedWith")
		private List<String> sharedWith;

		public MemoDto() {}
		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public List<String> getSharedWith() { return sharedWith; }
		public void setSharedWith(List<String> sharedWith) { this.sharedWith = sharedWith; }
	}

	// ───── Filter-evaluating in-memory DAO ─────

	/** Upserts by uuid and evaluates $and / $or / $field with $eq or $in; any other operator throws. */
	public static class FilteringDao implements IDao {
		private final List<Object> storage = new ArrayList<>();

		@Override
		public void registerDomain(IDomainDefinition domainDefinition) {}

		@Override
		public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
				throws ApiException {
			List<Object> result = new ArrayList<>();
			for (Object row : storage) {
				if (filter.isEmpty() || matches(row, filter.get())) {
					result.add(row);
				}
			}
			return result;
		}

		private static boolean matches(Object row, IFilter f) {
			switch (f.getName()) {
				case "$and":
					return f.getFilters().stream().allMatch(sub -> matches(row, sub));
				case "$or":
					return f.getFilters().stream().anyMatch(sub -> matches(row, sub));
				case "$field":
					IFilter op = f.getFilters().get(0);
					Object actual = read(row, String.valueOf(f.getValue()));
					return switch (op.getName()) {
						case "$eq" -> equalsSome(actual, op.getValue());
						case "$in" -> op.getFilters().stream().anyMatch(v -> equalsSome(actual, v.getValue()));
						default -> throw new IllegalStateException("FilteringDao: unmodelled operator " + op.getName());
					};
				default:
					throw new IllegalStateException("FilteringDao: unmodelled filter " + f.getName());
			}
		}

		/** MongoDB's rule: on a list field a value matches when SOME element equals it. */
		private static boolean equalsSome(Object actual, Object expected) {
			if (actual instanceof Collection<?> elements) {
				return elements.stream().anyMatch(e -> java.util.Objects.equals(e, expected));
			}
			return java.util.Objects.equals(actual, expected);
		}

		private static Object read(Object row, String field) {
			try {
				java.lang.reflect.Field fd = row.getClass().getDeclaredField(field);
				fd.setAccessible(true);
				return fd.get(row);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("FilteringDao: no field " + field, e);
			}
		}

		@Override
		public Object save(Object object) throws ApiException {
			Object uuid = read(object, "uuid");
			for (int i = 0; i < storage.size(); i++) {
				if (uuid != null && uuid.equals(read(storage.get(i), "uuid"))) {
					storage.set(i, object);
					return object;
				}
			}
			storage.add(object);
			return object;
		}

		@Override
		public void delete(Object object) throws ApiException {
			Object uuid = read(object, "uuid");
			storage.removeIf(row -> uuid != null && uuid.equals(read(row, "uuid")));
		}

		@Override
		public long count(IFilter filter) throws ApiException {
			return find(Optional.empty(), Optional.ofNullable(filter), Optional.empty()).size();
		}

		List<Object> getStorage() { return storage; }

		NoteDto row(String uuid) {
			return storage.stream().map(NoteDto.class::cast).filter(r -> uuid.equals(r.getUuid())).findFirst()
					.orElseThrow(() -> new AssertionError("no row " + uuid));
		}
	}

	// ───── Helpers ─────

	/** Multi-tenancy needs one tenant domain; reuse the base User domain (security off). */
	private static IApiBuilder withTenantDomain(IApiBuilder builder) throws ApiException {
		builder.domain(IClass.getClass(User.class))
				.tenant(true)
				.superTenant("superTenant")
				.entity().id("id").uuid("uuid").tenantId("tenantId").up()
				.dto(IClass.getClass(UserDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(new StubDao()).up()
				.security().disable(true).up()
			.up();
		return builder;
	}

	/** A notes domain on {@code dao}: {@code title} updatable by anyone who may write the note. */
	private IDomain<?> notes(FilteringDao dao, java.util.function.Consumer<IDomainBuilder<Note>> characteristics)
			throws ApiException {
		IApiBuilder builder = withTenantDomain(newBuilder());
		IDomainBuilder<Note> domain = builder.domain(IClass.getClass(Note.class));
		characteristics.accept(domain);
		domain.entity()
					.id("id").uuid("uuid").tenantId("tenantId")
					.update("title")
					.update("sharedWith")
					.update("shares")
				.up()
				.dto(IClass.getClass(NoteDto.class))
					.id("id").uuid("uuid").tenantId("tenantId")
					.db(dao)
				.up()
				.security().disable(true).up()
			.up();
		return buildAndStart(builder).getDomain("notes").orElseThrow();
	}

	private static IClass<Note> noteClass() {
		return IClass.getClass(Note.class);
	}

	private static Caller tenant(String tenantId) {
		return new Caller(tenantId, tenantId, "caller-" + tenantId, null, false, false, List.of());
	}

	private static Caller owner(String tenantId, String ownerId) {
		return new Caller(tenantId, tenantId, ownerId, ownerId, false, false, List.of());
	}

	private static Caller superOwner(String tenantId, String ownerId) {
		return new Caller(tenantId, tenantId, ownerId, ownerId, false, true, List.of());
	}

	private static OperationRequest request(OperationDefinition op, Caller caller) {
		OperationRequest request = new OperationRequest(new HashMap<>());
		request.arg(IOperationRequest.OPERATION, op);
		request.arg(IOperationRequest.TENANT_ID, caller.tenantId());
		request.arg(IOperationRequest.REQUESTED_TENANT_ID, caller.requestedTenantId());
		if (caller.ownerId() != null) {
			request.arg(IOperationRequest.OWNER_ID, caller.ownerId());
		}
		request.arg(IOperationRequest.SUPER_TENANT, caller.superTenant());
		request.arg(IOperationRequest.SUPER_OWNER, caller.superOwner());
		request.arg(IOperationRequest.CALLER, caller);
		return request;
	}

	private WorkflowResult create(IDomain<?> ctx, Caller caller, Note note) {
		OperationRequest r = request(OperationDefinition.createOneWithStandardSecurity("notes", noteClass()), caller);
		r.arg("entity", note);
		return executeScript(ctx, r);
	}

	@SuppressWarnings("unchecked")
	private List<Object> readAll(IDomain<?> ctx, Caller caller) {
		WorkflowResult result = executeScript(ctx,
				request(OperationDefinition.readAllWithStandardSecurity("notes", noteClass()), caller));
		assertTrue(result.isSuccess(), "readAll should succeed, code=" + result.code());
		return (List<Object>) result.output();
	}

	private WorkflowResult readOne(IDomain<?> ctx, Caller caller, String uuid) {
		return onOne(ctx, OperationDefinition.readOneWithStandardSecurity("notes", noteClass()), caller, uuid);
	}

	private WorkflowResult update(IDomain<?> ctx, Caller caller, String uuid, Note body) {
		OperationRequest r = request(OperationDefinition.updateOneWithStandardSecurity("notes", noteClass()), caller);
		r.arg("entity", body);
		r.arg("type", "uuid");
		r.arg("identifier", uuid);
		return executeScript(ctx, r);
	}

	private WorkflowResult deleteOne(IDomain<?> ctx, Caller caller, String uuid) {
		return onOne(ctx, OperationDefinition.deleteOneWithStandardSecurity("notes", noteClass()), caller, uuid);
	}

	private WorkflowResult deleteAll(IDomain<?> ctx, Caller caller) {
		return executeScript(ctx,
				request(OperationDefinition.deleteAllWithStandardSecurity("notes", noteClass()), caller));
	}

	private WorkflowResult share(IDomain<?> ctx, Caller caller, String uuid, Object grants) {
		OperationRequest r = request(OperationDefinition.share("notes", noteClass(), true, null, Access.authenticated),
				caller);
		r.arg("entity", grants);
		r.arg("type", "uuid");
		r.arg("identifier", uuid);
		return executeScript(ctx, r);
	}

	private WorkflowResult onOne(IDomain<?> ctx, OperationDefinition op, Caller caller, String uuid) {
		OperationRequest r = request(op, caller);
		r.arg("type", "uuid");
		r.arg("identifier", uuid);
		return executeScript(ctx, r);
	}

	private static Note note(String title) {
		Note n = new Note();
		n.setTitle(title);
		return n;
	}

	private static Note sharedNote(String title, String sharedWith) {
		Note n = note(title);
		n.setSharedWith(sharedWith);
		return n;
	}

	private static String uuidOf(WorkflowResult created) {
		assertTrue(created.isSuccess(), "create should succeed, code=" + created.code());
		return ((Note) created.output()).getUuid();
	}

	private static void assertDenied(WorkflowResult result) {
		assertFalse(result.isSuccess(), "the operation should have been refused");
		assertEquals(404, result.code(), "a row out of reach answers 404, not 403: its existence is not disclosed");
	}

	// ───── 1. Legacy .shared(field): a tenant, read only ─────

	@Nested
	@DisplayName("legacy .shared(field) — tenant A's note shared with B, READ only")
	class LegacySharedTenant {

		private FilteringDao dao;
		private IDomain<?> ctx;
		private String uuid;

		@BeforeEach
		void setUp() throws ApiException {
			dao = new FilteringDao();
			ctx = notes(dao, d -> {
				try {
					d.shared("sharedWith");
				} catch (ApiException e) {
					throw new IllegalStateException(e);
				}
			});
			uuid = uuidOf(create(ctx, tenant(A), sharedNote("hello", B)));
		}

		@Test
		@DisplayName("A and B read it; C does not")
		void readers() {
			assertEquals(1, readAll(ctx, tenant(A)).size());
			assertEquals(1, readAll(ctx, tenant(B)).size());
			assertTrue(readOne(ctx, tenant(B), uuid).isSuccess());
			assertTrue(readAll(ctx, tenant(C)).isEmpty());
			assertDenied(readOne(ctx, tenant(C), uuid));
		}

		@Test
		@DisplayName("B can neither update nor delete it; A can")
		void onlyTheOwningTenantWrites() {
			assertDenied(update(ctx, tenant(B), uuid, note("edited by B")));
			assertDenied(deleteOne(ctx, tenant(B), uuid));
			assertEquals("hello", dao.row(uuid).getTitle());

			assertTrue(update(ctx, tenant(A), uuid, sharedNote("edited by A", B)).isSuccess());
			assertEquals("edited by A", dao.row(uuid).getTitle());
		}

		@Test
		@DisplayName("B's DELETE_ALL leaves the note A shared with it, and every other tenant's rows")
		void deleteAllStaysWithinTheOwnRows() {
			uuidOf(create(ctx, tenant(A), note("A private")));
			uuidOf(create(ctx, tenant(C), note("C private")));
			uuidOf(create(ctx, tenant(B), note("B private")));

			assertTrue(deleteAll(ctx, tenant(B)).isSuccess());

			assertEquals(3, dao.getStorage().size(), "only B's own row is gone");
			assertTrue(dao.getStorage().stream().map(NoteDto.class::cast).noneMatch(r -> B.equals(r.getTenantId())));
		}
	}

	@Nested
	@DisplayName("legacy .shared(field) on an owned domain — alice@A's note shared with tenant B")
	class LegacySharedOwned {

		private IDomain<?> ctx;
		private String uuid;

		@BeforeEach
		void setUp() throws ApiException {
			ctx = notes(new FilteringDao(), d -> {
				try {
					d.owned("ownerId").shared("sharedWith");
				} catch (ApiException e) {
					throw new IllegalStateException(e);
				}
			});
			uuid = uuidOf(create(ctx, owner(A, ALICE), sharedNote("alice's", B)));
		}

		@Test
		@DisplayName("a grant to tenant B opens the note to every owner of B")
		void everyOwnerOfTheGrantedTenantReadsIt() {
			assertEquals(1, readAll(ctx, owner(B, BOB)).size());
			assertTrue(readOne(ctx, owner(B, BOB), uuid).isSuccess());
			assertEquals(1, readAll(ctx, superOwner(B, "users:boss-b")).size());
		}

		@Test
		@DisplayName("another owner of A (carol) does not read it: the own branch is alice's")
		void otherOwnerOfTheOwningTenantDoesNot() {
			assertTrue(readAll(ctx, owner(A, CAROL)).isEmpty());
			assertDenied(readOne(ctx, owner(A, CAROL), uuid));
		}

		@Test
		@DisplayName("a caller of A without an ownerId reaches only rows that belong to no owner")
		void ownerlessCallerReachesOnlyUnownedRows() {
			assertTrue(readAll(ctx, tenant(A)).isEmpty(), "alice's note is not an ownerless caller's");
		}

		@Test
		@DisplayName("the owner filter reads caller.ownerId(), not requestedOwnerId()")
		void ownerFilterUsesOwnerId() {
			Caller carolRequestingAlice = new Caller(A, A, CAROL, CAROL, ALICE, false, false, List.of());
			Caller aliceRequestingCarol = new Caller(A, A, ALICE, ALICE, CAROL, false, false, List.of());
			assertTrue(readAll(ctx, carolRequestingAlice).isEmpty());
			assertEquals(1, readAll(ctx, aliceRequestingCarol).size());
		}
	}

	@Nested
	@DisplayName("legacy .shared(field) on a List<String> field — several tenants")
	class LegacySharedList {

		@Test
		@DisplayName("every listed tenant reads the row, an unlisted one does not")
		void listedTenantsRead() throws ApiException {
			FilteringDao dao = new FilteringDao();
			IApiBuilder builder = withTenantDomain(newBuilder());
			builder.domain(IClass.getClass(Memo.class))
					.shared("sharedWith")
					.entity().id("id").uuid("uuid").tenantId("tenantId").up()
					.dto(IClass.getClass(MemoDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(dao).up()
					.security().disable(true).up()
				.up();
			IDomain<?> ctx = buildAndStart(builder).getDomain("memos").orElseThrow();
			Memo memo = new Memo();
			memo.setSharedWith(List.of(B, C));
			OperationRequest create = request(
					OperationDefinition.createOneWithStandardSecurity("memos", IClass.getClass(Memo.class)), tenant(A));
			create.arg("entity", memo);
			assertTrue(executeScript(ctx, create).isSuccess());

			for (String t : List.of(B, C, D)) {
				WorkflowResult r = executeScript(ctx, request(
						OperationDefinition.readAllWithStandardSecurity("memos", IClass.getClass(Memo.class)), tenant(t)));
				assertEquals(D.equals(t) ? 0 : 1, ((List<?>) r.output()).size(), "tenant " + t);
			}
		}
	}

	// ───── 2. .shares(field): grant tokens, read or write, tenants and owners ─────

	@Nested
	@DisplayName(".shares(field) — alice@A's note, grants written by the share operation")
	class SharesGrants {

		private FilteringDao dao;
		private IDomain<?> ctx;
		private String uuid;

		@BeforeEach
		void setUp() throws ApiException {
			dao = new FilteringDao();
			ctx = notes(dao, d -> {
				try {
					d.owned("ownerId").shares("shares");
				} catch (ApiException e) {
					throw new IllegalStateException(e);
				}
			});
			Note body = note("alice's");
			body.setShares(List.of(ShareGrant.writeByTenant(C).token()));
			uuid = uuidOf(create(ctx, owner(A, ALICE), body));
		}

		private void shareWithBobReadAndDaveWrite() {
			WorkflowResult shared = share(ctx, owner(A, ALICE), uuid, new ShareRequest(List.of(
					ShareGrant.readByTenant(B).token(), ShareGrant.writeByOwner(DAVE).token())));
			assertTrue(shared.isSuccess(), "share should succeed, code=" + shared.code());
		}

		@Test
		@DisplayName("declaring .shares registers the share operation, closed behind an authority")
		void shareOperationRegistered() {
			OperationDefinition op = ctx.getDomainDefinition().operations().stream()
					.filter(o -> o.getBusinessOperation() == BusinessOperation.share).findFirst().orElseThrow();
			assertTrue(op.authority());
			assertEquals("share-one-note", op.effectiveAuthorityName());
			assertEquals("/notes/${uuid}/shares", op.getPath().path());
		}

		@Test
		@DisplayName("a client create cannot write grants: the body's are dropped")
		void createDropsGrants() {
			assertNull(dao.row(uuid).getShares());
			assertTrue(readAll(ctx, owner(C, "users:someone-of-c")).isEmpty());
		}

		@Test
		@DisplayName("a read grant to tenant B: every owner of B reads it, none writes or deletes it")
		void tenantReadGrant() {
			shareWithBobReadAndDaveWrite();
			assertEquals(1, readAll(ctx, owner(B, BOB)).size());
			assertTrue(readOne(ctx, owner(B, BOB), uuid).isSuccess());
			assertDenied(update(ctx, owner(B, BOB), uuid, note("edited by bob")));
			assertDenied(deleteOne(ctx, owner(B, BOB), uuid));
			assertEquals("alice's", dao.row(uuid).getTitle());
		}

		@Test
		@DisplayName("a write grant to owner dave (tenant D): dave reads and updates it, but cannot delete it")
		void ownerWriteGrant() {
			shareWithBobReadAndDaveWrite();
			assertEquals(1, readAll(ctx, owner(D, DAVE)).size());
			assertTrue(update(ctx, owner(D, DAVE), uuid, note("edited by dave")).isSuccess());
			assertEquals("edited by dave", dao.row(uuid).getTitle());
			assertDenied(deleteOne(ctx, owner(D, DAVE), uuid));
			assertTrue(readAll(ctx, owner(D, "users:other-of-d")).isEmpty(), "the grant names dave, not tenant D");
		}

		@Test
		@DisplayName("a grantee cannot change the grants: neither through update nor through share")
		void granteeCannotReshare() {
			shareWithBobReadAndDaveWrite();
			Note body = note("edited by dave");
			body.setShares(List.of(ShareGrant.writeByOwner(CAROL).token()));
			assertTrue(update(ctx, owner(D, DAVE), uuid, body).isSuccess());
			assertEquals(List.of(ShareGrant.readByTenant(B).token(), ShareGrant.writeByOwner(DAVE).token()),
					dao.row(uuid).getShares(), "an update keeps the stored grants");

			assertDenied(share(ctx, owner(D, DAVE), uuid, List.of(ShareGrant.writeByOwner(CAROL).token())));
			assertDenied(share(ctx, owner(B, BOB), uuid, List.of()));
		}

		@Test
		@DisplayName("another owner of the same tenant is reached only through a grant")
		void sameTenantOwnerByGrant() {
			assertTrue(readAll(ctx, owner(A, CAROL)).isEmpty());
			assertTrue(share(ctx, owner(A, ALICE), uuid, List.of(ShareGrant.readByOwner(CAROL).token())).isSuccess());
			assertEquals(1, readAll(ctx, owner(A, CAROL)).size());
		}

		@Test
		@DisplayName("sharing with [] stops sharing")
		void emptyGrantsStopSharing() {
			shareWithBobReadAndDaveWrite();
			assertTrue(share(ctx, owner(A, ALICE), uuid, new ShareRequest(List.of())).isSuccess());
			assertTrue(readAll(ctx, owner(B, BOB)).isEmpty());
			assertTrue(readAll(ctx, owner(D, DAVE)).isEmpty());
		}

		@Test
		@DisplayName("grants are validated and de-duplicated; a malformed one rejects the whole request")
		void grantsValidated() {
			String bob = ShareGrant.readByOwner(BOB).token();
			assertTrue(share(ctx, owner(A, ALICE), uuid, List.of(bob, bob)).isSuccess());
			assertEquals(List.of(bob), dao.row(uuid).getShares());

			WorkflowResult malformed = share(ctx, owner(A, ALICE), uuid, List.of(bob, "x:tenant:B"));
			assertFalse(malformed.isSuccess());
			assertEquals(400, malformed.code());
			assertEquals(List.of(bob), dao.row(uuid).getShares(), "nothing written");
		}

		@Test
		@DisplayName("a grantee's DELETE_ALL never reaches what it was lent")
		void deleteAllByGrantee() {
			shareWithBobReadAndDaveWrite();
			assertTrue(deleteAll(ctx, owner(D, DAVE)).isSuccess());
			assertTrue(deleteAll(ctx, owner(B, BOB)).isSuccess());
			assertEquals(1, dao.getStorage().size());
		}
	}

	// ───── 3. Create-time stamping ─────

	@Nested
	@DisplayName("create — a regular caller always writes into its own tenant, for itself")
	class CreateStamping {

		private FilteringDao dao;
		private IDomain<?> ctx;

		@BeforeEach
		void setUp() throws ApiException {
			dao = new FilteringDao();
			ctx = notes(dao, d -> {
				try {
					d.owned("ownerId");
				} catch (ApiException e) {
					throw new IllegalStateException(e);
				}
			});
		}

		@Test
		@DisplayName("a forged tenantId and ownerId in the body are overwritten by the caller's")
		void forgedTenantAndOwnerOverwritten() {
			Note forged = note("planted");
			forged.setTenantId(C);
			forged.setOwnerId(BOB);
			String uuid = uuidOf(create(ctx, owner(A, ALICE), forged));
			assertEquals(A, dao.row(uuid).getTenantId());
			assertEquals(ALICE, dao.row(uuid).getOwnerId());
			assertTrue(readAll(ctx, owner(C, BOB)).isEmpty());
		}

		@Test
		@DisplayName("a super tenant and super owner may create for another tenant and owner")
		void superCallersChoose() {
			Note onBehalf = note("on behalf");
			onBehalf.setTenantId(C);
			onBehalf.setOwnerId(BOB);
			Caller admin = new Caller(A, A, "users:admin", "users:admin", true, true, List.of());
			String uuid = uuidOf(create(ctx, admin, onBehalf));
			assertEquals(C, dao.row(uuid).getTenantId());
			assertEquals(BOB, dao.row(uuid).getOwnerId());
		}
	}

	// ───── 4. Builder ─────

	@Nested
	@DisplayName("builder — the type of a sharing field")
	class BuilderTypeCheck {

		@Test
		@DisplayName(".shares on a String field is rejected: grants are a list")
		void sharesNeedsAList() throws ApiException {
			IDomainBuilder<Note> domain = newBuilder().domain(noteClass());
			ApiException e = assertThrows(ApiException.class, () -> domain.shares("title"));
			assertTrue(e.getMessage().contains("List<String>"), e.getMessage());
		}

		@Test
		@DisplayName(".shared accepts a String or a List<String> field")
		void sharedAcceptsStringOrList() throws ApiException {
			newBuilder().domain(noteClass()).shared("sharedWith");
			newBuilder().domain(IClass.getClass(Memo.class)).shared("sharedWith");
		}
	}
}
