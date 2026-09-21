package com.garganttua.dao.postgresql.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * How DTO names become SQL identifiers — and how they are written safely into SQL.
 *
 * <p>
 * Every identifier is DOUBLE-QUOTED in generated SQL. That preserves case ({@code createdAt} stays
 * {@code createdAt}, where PostgreSQL would fold an unquoted name to lowercase), and it makes
 * reserved words usable as field names — a DTO with a field called {@code order}, {@code user} or
 * {@code group} is ordinary, and would be a syntax error unquoted.
 * </p>
 *
 * <p>
 * Identifiers are also the one place user-controlled text could reach SQL: a sort or filter names a
 * field. That text is never inserted as is — it is resolved against the {@link PgTable} first, and
 * only a column the model built can be quoted into a statement.
 * </p>
 */
public final class PgNaming {

    /** Joins the segments of a flattened path: {@code address.city} becomes {@code address__city}. */
    public static final String PATH_SEPARATOR = "__";

    /** PostgreSQL truncates identifiers beyond 63 bytes — silently, so two long names could collide. */
    public static final int MAX_IDENTIFIER_BYTES = 63;

    private static final int HASH_CHARS = 8;

    private PgNaming() {
        // Static helpers
    }

    /**
     * Double-quotes an identifier, doubling any embedded quote.
     *
     * @param identifier the raw identifier
     * @return the identifier as it must appear in SQL
     */
    public static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * The column name of a (possibly flattened) field path.
     *
     * @param fieldPath the DTO field names, root first
     * @return the column name, shortened if PostgreSQL would otherwise truncate it
     */
    public static String column(List<String> fieldPath) {
        return fit(String.join(PATH_SEPARATOR, fieldPath));
    }

    /**
     * The table name of a domain.
     *
     * @param domainName the domain name, as the api generates it ({@code users})
     * @return the table name
     */
    public static String table(String domainName) {
        return fit(domainName.toLowerCase(Locale.ROOT));
    }

    /**
     * The name of the child table holding a collection field.
     *
     * @param table     the owning table
     * @param fieldPath the collection field's path from the root entity
     * @return the child table name
     */
    public static String childTable(String table, List<String> fieldPath) {
        return fit(table + PATH_SEPARATOR + String.join(PATH_SEPARATOR, fieldPath));
    }

    /**
     * Keeps an identifier within PostgreSQL's 63-byte limit.
     *
     * <p>
     * PostgreSQL does not reject a longer identifier, it TRUNCATES it — so two long flattened paths
     * sharing a prefix would silently become the same column. A too-long name is cut and suffixed
     * with a hash of the full name instead, which stays unique and stays stable across restarts.
     * </p>
     */
    static String fit(String identifier) {
        byte[] bytes = identifier.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_IDENTIFIER_BYTES) {
            return identifier;
        }
        String hash = hash(identifier);
        int keep = MAX_IDENTIFIER_BYTES - HASH_CHARS - 1;
        StringBuilder cut = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < identifier.length()) {
            int cp = identifier.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + len > keep) {
                break;
            }
            cut.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return cut + "_" + hash;
    }

    private static String hash(String identifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identifier.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
