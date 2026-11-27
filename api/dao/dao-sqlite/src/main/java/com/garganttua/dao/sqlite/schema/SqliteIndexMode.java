package com.garganttua.dao.sqlite.schema;

import java.util.Locale;
import java.util.Optional;

/**
 * Who creates the indexes a domain declares ({@code @EntityIndexed}) — the twin of {@code mongodb.index.auto} and
 * {@code postgresql.index.auto}.
 */
public enum SqliteIndexMode {
    /** Create the declared indexes that are missing; warn about one that cannot be created, and start anyway. */
    CREATE,
    /** Touch no index at all. */
    NONE,
    /** As {@link #CREATE}, but refuse to start when a declared index cannot be created, or is missing in VALIDATE. */
    STRICT;

    /**
     * The mode a setting names.
     *
     * @param value the setting, any case
     * @return the mode, or empty when it names none
     */
    public static Optional<SqliteIndexMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (SqliteIndexMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
