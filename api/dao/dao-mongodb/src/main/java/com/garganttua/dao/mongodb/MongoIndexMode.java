package com.garganttua.dao.mongodb;

import java.util.Locale;
import java.util.Optional;

/**
 * Who creates the indexes a domain declares, and what happens when one cannot be created.
 *
 * <p>
 * The counterpart of {@code SchemaMode} on the PostgreSQL side, with one mode more. PostgreSQL only
 * ever needs to know whether the DAO may issue DDL; MongoDB needs a third answer because a unique
 * index is the one piece of DDL that can fail on <em>data</em> rather than on permissions: a
 * collection that already holds duplicates refuses it. Whether that refusal stops the application is
 * a deployment decision, not a framework one — {@link #CREATE} keeps it starting and says what is
 * wrong, {@link #STRICT} stops it.
 * </p>
 */
public enum MongoIndexMode {

    /**
     * The DAO creates the declared indexes that are missing, and nothing else — it never drops,
     * renames or alters one that exists. An index it cannot create is a WARN naming the gap, and the
     * application starts. The default: a running service is not taken down by a constraint that was
     * already being violated before the constraint existed.
     */
    CREATE,

    /**
     * The DAO issues no index command at all, and reads nothing. For a database whose indexes are
     * owned by a separate, reviewed process.
     */
    NONE,

    /**
     * {@link #CREATE}, except that an index that cannot be created stops the application with the
     * reason. For an environment where a declared uniqueness constraint the database does not hold
     * must never go unnoticed.
     */
    STRICT;

    /**
     * Reads a mode from its configured spelling, ignoring case and surrounding blanks.
     *
     * @param value the configured value, {@code null} or blank for none
     * @return the mode, or empty when {@code value} names none — the caller decides whether that is
     *         a default or an error, since it cannot tell "unset" from "misspelt" otherwise
     */
    public static Optional<MongoIndexMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (MongoIndexMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
