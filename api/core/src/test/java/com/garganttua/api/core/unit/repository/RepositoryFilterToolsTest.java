package com.garganttua.api.core.unit.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.ShareGrant;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.repository.AccessMode;
import com.garganttua.api.core.repository.RepositoryFilterTools;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * The access filter, evaluated: each case builds the filter for a caller and a mode, then runs it on
 * in-memory rows with MongoDB's semantics ({@code $eq} / {@code $in} match SOME element of a list
 * field). What a filter selects is what matters, not the shape of its tree.
 */
@DisplayName("RepositoryFilterTools — what each caller reaches, per access mode")
class RepositoryFilterToolsTest {

	private static final ObjectAddress TENANT = new ObjectAddress("tenantId");
	private static final ObjectAddress OWNER = new ObjectAddress("ownerId");
	private static final ObjectAddress SHARES = new ObjectAddress("shares");
	private static final ObjectAddress HIDDEN = new ObjectAddress("hidden");

	private static Map<String, Object> row(String name, String tenant, String owner, String... grants) {
		Map<String, Object> row = new HashMap<>();
		row.put("name", name);
		row.put("tenantId", tenant);
		row.put("ownerId", owner);
		row.put("shares", grants.length == 0 ? null : List.of(grants));
		row.put("hidden", false);
		return row;
	}

	private static IDomainDefinition<?> domain(boolean publik, ObjectAddress owned, ObjectAddress shares,
			ObjectAddress hiddenable) {
		@SuppressWarnings("unchecked")
		IDomainDefinition<Object> def = mock(IDomainDefinition.class);
		@SuppressWarnings("unchecked")
		IEntityDefinition<Object> entity = mock(IEntityDefinition.class);
		when(entity.tenantId()).thenReturn(TENANT);
		when(def.entityDefinition()).thenReturn(entity);
		when(def.domainName()).thenReturn("notes");
		when(def.publik()).thenReturn(publik);
		when(def.tenant()).thenReturn(false);
		when(def.owned()).thenReturn(owned);
		when(def.shares()).thenReturn(shares);
		when(def.hiddenable()).thenReturn(hiddenable);
		return def;
	}

	private static List<String> reached(List<Map<String, Object>> rows, ICaller caller, IDomainDefinition<?> def,
			AccessMode mode) {
		IFilter filter = RepositoryFilterTools.buildFilter(caller, null, def, true, mode);
		List<String> names = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			if (filter == null || matches(row, filter)) {
				names.add((String) row.get("name"));
			}
		}
		return names;
	}

	private static boolean matches(Map<String, Object> row, IFilter f) {
		return switch (f.getName()) {
			case "$and" -> f.getFilters().stream().allMatch(sub -> matches(row, sub));
			case "$or" -> f.getFilters().stream().anyMatch(sub -> matches(row, sub));
			case "$field" -> {
				IFilter op = f.getFilters().get(0);
				Object actual = row.get(String.valueOf(f.getValue()));
				yield switch (op.getName()) {
					case "$eq" -> some(actual, op.getValue());
					case "$in" -> op.getFilters().stream().anyMatch(v -> some(actual, v.getValue()));
					default -> throw new IllegalStateException("unmodelled operator " + op.getName());
				};
			}
			default -> throw new IllegalStateException("unmodelled filter " + f.getName());
		};
	}

	private static boolean some(Object actual, Object expected) {
		if (actual instanceof Collection<?> elements) {
			return elements.stream().anyMatch(e -> Objects.equals(e, expected));
		}
		return Objects.equals(actual, expected);
	}

	private static ICaller owner(String tenant, String owner) {
		return new Caller(tenant, tenant, owner, owner, false, false, List.of());
	}

	@Nested
	@DisplayName("a private owned domain with grants")
	class PrivateOwnedWithGrants {

		private final IDomainDefinition<?> def = domain(false, OWNER, SHARES, null);
		private final List<Map<String, Object>> rows = List.of(
				row("alice", "A", "users:alice"),
				row("alice-read-to-B", "A", "users:alice", ShareGrant.readByTenant("B").token()),
				row("alice-write-to-dave", "A", "users:alice", ShareGrant.writeByOwner("users:dave").token()),
				row("unowned-of-A", "A", null),
				row("bob", "B", "users:bob"));

		@Test
		@DisplayName("READ: own rows plus every grant")
		void read() {
			assertEquals(List.of("alice-read-to-B", "bob"), reached(rows, owner("B", "users:bob"), def, AccessMode.READ));
			assertEquals(List.of("alice-write-to-dave"), reached(rows, owner("D", "users:dave"), def, AccessMode.READ));
		}

		@Test
		@DisplayName("WRITE: own rows plus write grants only")
		void write() {
			assertEquals(List.of("bob"), reached(rows, owner("B", "users:bob"), def, AccessMode.WRITE));
			assertEquals(List.of("alice-write-to-dave"), reached(rows, owner("D", "users:dave"), def, AccessMode.WRITE));
		}

		@Test
		@DisplayName("MANAGE: own rows only, whatever the grants")
		void manage() {
			assertEquals(List.of("bob"), reached(rows, owner("B", "users:bob"), def, AccessMode.MANAGE));
			assertEquals(List.of(), reached(rows, owner("D", "users:dave"), def, AccessMode.MANAGE));
			assertEquals(List.of("alice", "alice-read-to-B", "alice-write-to-dave"),
					reached(rows, owner("A", "users:alice"), def, AccessMode.MANAGE));
		}

		@Test
		@DisplayName("a caller without an ownerId reaches only the unowned rows of its tenant, plus its tenant's grants")
		void ownerless() {
			ICaller tenantOfA = new Caller("A", "A", null, null, false, false, List.of());
			assertEquals(List.of("unowned-of-A"), reached(rows, tenantOfA, def, AccessMode.READ));
			ICaller tenantOfB = new Caller("B", "B", null, null, false, false, List.of());
			assertEquals(List.of("alice-read-to-B"), reached(rows, tenantOfB, def, AccessMode.READ));
		}

		@Test
		@DisplayName("a super owner reaches every row of its tenant")
		void superOwner() {
			ICaller boss = new Caller("A", "A", "users:boss", "users:boss", false, true, List.of());
			assertEquals(List.of("alice", "alice-read-to-B", "alice-write-to-dave", "unowned-of-A"),
					reached(rows, boss, def, AccessMode.MANAGE));
		}

		@Test
		@DisplayName("a super tenant without a requested tenant is not filtered")
		void superTenant() {
			ICaller root = new Caller("ROOT", null, null, null, true, true, List.of());
			assertNull(RepositoryFilterTools.buildFilter(root, null, def, true, AccessMode.MANAGE));
		}
	}

	@Nested
	@DisplayName("a hiddenable private domain")
	class Hiddenable {

		@Test
		@DisplayName("a grant reaches only visible rows; the own branch reaches hidden ones too")
		void grantsRespectVisibility() {
			IDomainDefinition<?> def = domain(false, null, SHARES, HIDDEN);
			Map<String, Object> hidden = row("hidden-to-B", "A", null, ShareGrant.readByTenant("B").token());
			hidden.put("hidden", true);
			Map<String, Object> ownHidden = row("own-hidden", "B", null);
			ownHidden.put("hidden", true);
			List<Map<String, Object>> rows = List.of(hidden, ownHidden,
					row("visible-to-B", "A", null, ShareGrant.readByTenant("B").token()));
			ICaller b = new Caller("B", "B", null, null, false, false, List.of());
			assertEquals(List.of("own-hidden", "visible-to-B"), reached(rows, b, def, AccessMode.READ));
		}
	}

	@Nested
	@DisplayName("a public domain")
	class Public {

		private final IDomainDefinition<?> def = domain(true, null, null, null);
		private final List<Map<String, Object>> rows = List.of(row("of-A", "A", null), row("of-B", "B", null));

		@Test
		@DisplayName("is read and updated across tenants, as before")
		void readAndWriteUnchanged() {
			ICaller b = owner("B", null);
			assertEquals(List.of("of-A", "of-B"), reached(rows, b, def, AccessMode.READ));
			assertEquals(List.of("of-A", "of-B"), reached(rows, b, def, AccessMode.WRITE));
		}

		@Test
		@DisplayName("MANAGE (delete) stays within the caller's own tenant")
		void manageStaysHome() {
			assertEquals(List.of("of-B"), reached(rows, owner("B", null), def, AccessMode.MANAGE));
		}
	}
}
