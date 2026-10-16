package com.garganttua.api.commons.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;

@DisplayName("ShareGrant — the tokens of a shared entity")
class ShareGrantTest {

	@Nested
	@DisplayName("token")
	class Token {

		@Test
		@DisplayName("each factory spells its right and kind")
		void factories() {
			assertEquals("r:tenant:B", ShareGrant.readByTenant("B").token());
			assertEquals("w:tenant:B", ShareGrant.writeByTenant("B").token());
			assertEquals("r:owner:users:bob", ShareGrant.readByOwner("users:bob").token());
			assertEquals("w:owner:users:bob", ShareGrant.writeByOwner("users:bob").token());
		}

		@Test
		@DisplayName("parse reads back what token wrote, a qualified owner id included")
		void roundTrip() throws ApiException {
			ShareGrant grant = ShareGrant.writeByOwner("users:bob");
			assertEquals(grant, ShareGrant.parse(grant.token()));
		}

		@Test
		@DisplayName("a malformed token is refused with the expected form")
		void malformed() {
			for (String token : new String[] { null, "", "r:tenant", "x:tenant:B", "r:group:B", "R:tenant:B",
					"r:tenant:", "r:tenant:a b" }) {
				ApiException e = assertThrows(ApiException.class, () -> ShareGrant.parse(token), "token " + token);
				assertTrue(e.getMessage().contains("Invalid share grant"), e.getMessage());
			}
		}
	}

	@Nested
	@DisplayName("tokens a caller is entitled to")
	class Entitlement {

		@Test
		@DisplayName("reading is opened by any grant, to the tenant or to the owner")
		void readTokens() {
			assertEquals(List.of("w:tenant:T", "w:owner:users:o", "r:tenant:T", "r:owner:users:o"),
					ShareGrant.readTokens("T", "users:o"));
		}

		@Test
		@DisplayName("writing is opened by write grants only")
		void writeTokens() {
			assertEquals(List.of("w:tenant:T", "w:owner:users:o"), ShareGrant.writeTokens("T", "users:o"));
		}

		@Test
		@DisplayName("a caller naming neither tenant nor owner is entitled to nothing")
		void nobody() {
			assertTrue(ShareGrant.readTokens(null, " ").isEmpty());
			assertEquals(List.of("w:owner:users:o"), ShareGrant.writeTokens(null, "users:o"));
		}
	}
}
