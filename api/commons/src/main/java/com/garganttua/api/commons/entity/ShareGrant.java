package com.garganttua.api.commons.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.garganttua.api.commons.ApiException;

/**
 * One grant of a shared entity: who else may read it, or read and write it.
 *
 * <p>
 * A domain declares its grants field with {@code .shares(field)}, a {@code List<String>}. Each element
 * is the {@link #token()} of a grant — {@code r:tenant:<tenantId>}, {@code w:tenant:<tenantId>},
 * {@code r:owner:<ownerId>} or {@code w:owner:<ownerId>} — so that "is this entity shared with me" is
 * a single {@code $in} over a list field, which every DAO already answers by matching elements. A list
 * of {@code {grantee, right}} objects would need {@code $elemMatch} to bind the two to the same
 * element, and the filter language has none.
 * </p>
 *
 * <p>
 * A tenant grant opens the entity to every owner of that tenant. An owner grant names one owner in its
 * qualified form ({@code users:<uuid>}), unique across tenants, so it needs no tenant grant beside it.
 * A {@code write} grant implies {@code read}.
 * </p>
 *
 * @param right   what the grantee may do
 * @param kind    whether the grantee is a tenant or an owner
 * @param grantee the tenant id, or the qualified owner id
 */
public record ShareGrant(Right right, Kind kind, String grantee) {

	/** What a grant lets its grantee do. {@link #write} implies {@link #read}. */
	public enum Right {
		read("r"), write("w");

		private final String letter;

		Right(String letter) {
			this.letter = letter;
		}

		/** The one-letter code the token carries. */
		public String code() {
			return this.letter;
		}
	}

	/** Who a grant designates. */
	public enum Kind {
		tenant, owner
	}

	private static final String SEPARATOR = ":";

	/** right, kind, grantee. */
	private static final int TOKEN_PARTS = 3;

	/**
	 * Validates the grant.
	 *
	 * @throws IllegalArgumentException when a part is missing or the grantee is blank or contains
	 *                                  whitespace
	 */
	public ShareGrant {
		Objects.requireNonNull(right, "A share grant needs a right");
		Objects.requireNonNull(kind, "A share grant needs a kind of grantee");
		if (grantee == null || grantee.isBlank() || grantee.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException("A share grant needs a grantee without blanks, got [" + grantee + "]");
		}
	}

	/** Lets every owner of {@code tenantId} read the entity. */
	public static ShareGrant readByTenant(String tenantId) {
		return new ShareGrant(Right.read, Kind.tenant, tenantId);
	}

	/** Lets every owner of {@code tenantId} read and write the entity. */
	public static ShareGrant writeByTenant(String tenantId) {
		return new ShareGrant(Right.write, Kind.tenant, tenantId);
	}

	/** Lets the owner {@code ownerId} (qualified: {@code users:<uuid>}) read the entity. */
	public static ShareGrant readByOwner(String ownerId) {
		return new ShareGrant(Right.read, Kind.owner, ownerId);
	}

	/** Lets the owner {@code ownerId} (qualified: {@code users:<uuid>}) read and write the entity. */
	public static ShareGrant writeByOwner(String ownerId) {
		return new ShareGrant(Right.write, Kind.owner, ownerId);
	}

	/** The form stored in the grants field and sent on the wire. */
	public String token() {
		return this.right.code() + SEPARATOR + this.kind.name() + SEPARATOR + this.grantee;
	}

	/**
	 * Reads a token back.
	 *
	 * @param token {@code r|w:tenant|owner:<grantee>}
	 * @return the grant
	 * @throws ApiException when the token is malformed — the message names the expected form
	 */
	public static ShareGrant parse(String token) throws ApiException {
		if (token != null) {
			String[] parts = token.split(SEPARATOR, TOKEN_PARTS);
			if (parts.length == TOKEN_PARTS) {
				Right right = parts[0].equals(Right.read.code()) ? Right.read
						: parts[0].equals(Right.write.code()) ? Right.write : null;
				Kind kind = parts[1].equals(Kind.tenant.name()) ? Kind.tenant
						: parts[1].equals(Kind.owner.name()) ? Kind.owner : null;
				if (right != null && kind != null) {
					try {
						return new ShareGrant(right, kind, parts[2]);
					} catch (IllegalArgumentException e) {
						throw new ApiException("Invalid share grant [" + token + "]: " + e.getMessage(), e);
					}
				}
			}
		}
		throw new ApiException("Invalid share grant [" + token
				+ "]: expected r|w:tenant:<tenantId> or r|w:owner:<ownerId>");
	}

	/**
	 * The tokens that let a caller READ an entity: any grant, read or write, to its tenant or to
	 * itself.
	 *
	 * @param tenantId the caller's requested tenant, or null
	 * @param ownerId  the caller's owner, or null
	 * @return the tokens, empty when the caller names neither
	 */
	public static List<String> readTokens(String tenantId, String ownerId) {
		List<String> tokens = new ArrayList<>(writeTokens(tenantId, ownerId));
		addIfPresent(tokens, Right.read, Kind.tenant, tenantId);
		addIfPresent(tokens, Right.read, Kind.owner, ownerId);
		return tokens;
	}

	/**
	 * The tokens that let a caller WRITE an entity: a write grant to its tenant or to itself.
	 *
	 * @param tenantId the caller's requested tenant, or null
	 * @param ownerId  the caller's owner, or null
	 * @return the tokens, empty when the caller names neither
	 */
	public static List<String> writeTokens(String tenantId, String ownerId) {
		List<String> tokens = new ArrayList<>();
		addIfPresent(tokens, Right.write, Kind.tenant, tenantId);
		addIfPresent(tokens, Right.write, Kind.owner, ownerId);
		return tokens;
	}

	private static void addIfPresent(List<String> tokens, Right right, Kind kind, String grantee) {
		if (grantee != null && !grantee.isBlank()) {
			tokens.add(new ShareGrant(right, kind, grantee).token());
		}
	}
}
