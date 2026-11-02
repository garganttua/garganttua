package com.garganttua.dao.postgresql;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * One PostgreSQL index, as the DAO will issue it: an {@code EntityIndexRule} once its field has been
 * resolved to a column of the domain's {@code PgTable} and its kind turned into an access method.
 *
 * @param name            the index name, unquoted, already prefixed with its table and within 63 bytes
 * @param table           the table it is on, unquoted — the main table or a child table
 * @param method          the access method: {@code btree}, {@code gist} or {@code gin}
 * @param keys            the key expressions, as SQL (columns quoted); the declared field is last
 * @param unique          whether the index refuses a duplicate
 * @param field           the declared field, by its DTO dotted path — what messages name
 * @param predicateColumn for a unique index, the column whose NULL the uniqueness ignores; null otherwise
 * @param groupColumns    the plain columns the keys cover, unquoted — what a duplicate query groups on
 */
record PgIndexSpec(String name, String table, String method, List<String> keys, boolean unique, String field,
        String predicateColumn, List<String> groupColumns) {

    PgIndexSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(method, "method");
        keys = List.copyOf(keys);
        groupColumns = groupColumns == null ? List.of() : List.copyOf(groupColumns);
    }

    /**
     * {@return the statement creating this index}
     *
     * <p>
     * A unique index is PARTIAL: {@code WHERE <field> IS NOT NULL}. It holds the uniqueness only where
     * the field has a value — exactly what the framework's own unicity check does, and what the MongoDB
     * DAO's partial filter does. PostgreSQL already treats NULLs as distinct in a unique index; the
     * predicate makes that explicit, and keeps the rows without a value out of the index altogether.
     * </p>
     */
    String createSql() {
        StringJoiner keyList = new StringJoiner(", ", "(", ")");
        this.keys.forEach(keyList::add);
        return "CREATE " + (this.unique ? "UNIQUE " : "") + "INDEX IF NOT EXISTS " + PgNaming.quote(this.name)
                + " ON " + PgNaming.quote(this.table) + " USING " + this.method + " " + keyList
                + predicate();
    }

    private String predicate() {
        return this.predicateColumn == null ? "" : " WHERE " + PgNaming.quote(this.predicateColumn) + " IS NOT NULL";
    }

    /** {@return a one-line description of the index, for a log line} */
    String describe() {
        return "on " + this.table + " USING " + this.method + " " + String.join(", ", this.keys)
                + (this.unique ? ", unique" : "") + predicate();
    }

    /**
     * {@return a ready-to-paste query listing the groups of rows the unique index refuses} It groups on
     * every key column, so a tenant-scoped duplicate is listed per tenant.
     */
    String duplicateQuery() {
        StringJoiner columns = new StringJoiner(", ");
        this.groupColumns.forEach(c -> columns.add(PgNaming.quote(c)));
        return "SELECT " + columns + ", count(*) FROM " + PgNaming.quote(this.table) + predicate()
                + " GROUP BY " + columns + " HAVING count(*) > 1";
    }

    /**
     * Whether a stored index has the same keys, method, uniqueness and partial predicate as this one.
     *
     * @param stored the stored index, as the catalog describes it
     * @return {@code true} when nothing differs
     */
    boolean matches(PgStoredIndex stored) {
        return coversSameKeysAs(stored) && this.unique == stored.unique()
                && (this.predicateColumn != null) == (stored.predicate() != null);
    }

    /**
     * Whether a stored index is built over the same keys with the same method — whatever its name.
     *
     * @param stored the stored index
     * @return {@code true} when the key shape is the same
     */
    boolean coversSameKeysAs(PgStoredIndex stored) {
        if (!this.table.equals(stored.table()) || !this.method.equals(stored.method())
                || this.keys.size() != stored.keys().size()) {
            return false;
        }
        for (int i = 0; i < this.keys.size(); i++) {
            if (!normalize(this.keys.get(i)).equals(normalize(stored.keys().get(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A key expression stripped of what PostgreSQL adds or removes when it renders one back: quotes,
     * blanks, parentheses, and the implicit casts of a text-search key.
     */
    static String normalize(String key) {
        return key.replace("::regconfig", "").replace("::text", "").replaceAll("[\"\\s()]", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * One index as the catalog stores it.
     *
     * @param name      its name
     * @param table     the table it is on
     * @param method    its access method
     * @param keys      its key expressions, as {@code pg_get_indexdef} renders them
     * @param unique    whether it is unique
     * @param predicate its partial predicate, or null
     */
    record PgStoredIndex(String name, String table, String method, List<String> keys, boolean unique,
            String predicate) {

        PgStoredIndex {
            keys = List.copyOf(keys);
        }

        /** {@return the same one-line shape as {@link PgIndexSpec#describe()}} */
        String describe() {
            return "on " + this.table + " USING " + this.method + " " + String.join(", ", this.keys)
                    + (this.unique ? ", unique" : "") + (this.predicate == null ? "" : " WHERE " + this.predicate);
        }
    }
}
