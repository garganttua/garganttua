package com.garganttua.api.core.repository;

/**
 * What a caller is about to do with the rows a repository filter selects. Each mode opens a
 * different part of the share grants: what one may read, one may not necessarily change.
 */
public enum AccessMode {

	/** Reading: the caller's own rows, plus every row shared with it, read or write. */
	READ,

	/** Changing a row: the caller's own rows, plus the rows shared with it for writing. */
	WRITE,

	/**
	 * Acting as the row's proprietor — deleting it, or changing who it is shared with: the caller's
	 * own rows only. No grant opens this mode, so a grantee can neither destroy what it was lent nor
	 * lend it further.
	 */
	MANAGE;

	/**
	 * Reads the mode a script names.
	 *
	 * @param name {@code read}, {@code write} or {@code manage}, in any case; null reads as READ
	 * @return the mode
	 * @throws IllegalArgumentException for any other name
	 */
	public static AccessMode of(String name) {
		if (name == null) {
			return READ;
		}
		return AccessMode.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
	}
}
