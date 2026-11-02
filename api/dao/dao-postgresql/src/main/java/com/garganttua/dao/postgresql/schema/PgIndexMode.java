package com.garganttua.dao.postgresql.schema;

import java.util.Locale;
import java.util.Optional;

/**
 * Who creates the indexes a domain declares, and what happens when one cannot be created — the
 * PostgreSQL twin of the MongoDB DAO's {@code MongoIndexMode}, with the same three answers.
 *
 * <p>
 * It is separate from {@link SchemaMode} because a unique index is the one piece of DDL that can fail
 * on <em>data</em> rather than on permissions: a table that already holds duplicates refuses it.
 * Whether that refusal stops the application is a deployment decision. Under
 * {@link SchemaMode#VALIDATE} no DDL is ever issued, whatever this mode says: a missing index is then
 * reported with the statement that would create it — a WARN in {@link #CREATE}, a refusal to start
 * in {@link #STRICT}.
 * </p>
 */
public enum PgIndexMode {

    /**
     * The DAO creates the declared indexes that are missing, and nothing else — it never drops,
     * renames or alters one that exists. An index it cannot create is a WARN naming the gap, and the
     * application starts. The default.
     */
    CREATE,

    /** The DAO issues no index statement at all, and reads nothing. */
    NONE,

    /**
     * {@link #CREATE}, except that an index that cannot be created — or, under
     * {@link SchemaMode#VALIDATE}, that is missing — stops the application with the reason.
     */
    STRICT;

    /**
     * Reads a mode from its configured spelling, ignoring case and surrounding blanks.
     *
     * @param value the configured value, {@code null} or blank for none
     * @return the mode, or empty when {@code value} names none — the caller decides whether that is
     *         a default or an error
     */
    public static Optional<PgIndexMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (PgIndexMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
