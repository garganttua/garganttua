package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.sql.schema.SqlChildKind;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * Follows a dotted field path down the tree of child tables, one {@link SqlHop} per collection or map
 * entry it crosses — at any depth.
 *
 * <p>
 * The walk follows MongoDB's path traversal: a list is crossed element by element, a map (a
 * sub-document) through the key the next segment names, and an element that is itself a list
 * ({@code List<List<String>>}) is NOT descended into by a field name — MongoDB does not traverse
 * nested arrays for a dotted path either. A map whose values are collections
 * ({@code Map<String, List<Book>>}) is crossed through its key, then through the table of the entry's
 * value ({@link SqlChildTable#elementTable}).
 * </p>
 *
 * <p>
 * Map keys are filter text: they are bound as parameters — or, for an {@code ORDER BY} (which carries
 * none), inlined as a hex-encoded literal, which cannot break out of its quotes whatever the key holds.
 * </p>
 */
public final class SqlPathLocator {

    /**
     * Where a path leads.
     *
     * @param hops the child-table hops, outermost first — at least one
     * @param rest the rest of the path inside the last hop's element; null when the path names the
     *             last hop's collection itself, or the value of the map entry it reached
     */
    public record Located(List<SqlHop> hops, String rest) {

        public Located {
            hops = List.copyOf(hops);
        }

        /** {@return the last hop} */
        public SqlHop last() {
            return hops.get(hops.size() - 1);
        }
    }

    private static final String EQUALS = " = ";

    private final SqlTable table;
    private final String aliasPrefix;
    private final boolean inlineKeys;

    /**
     * @param table       the domain's model
     * @param aliasPrefix the alias of the first child level ({@code c}); deeper levels append their
     *                    depth ({@code c2}, {@code c3})
     * @param inlineKeys  whether map keys are inlined as literals (for an {@code ORDER BY}) instead of
     *                    bound
     */
    public SqlPathLocator(SqlTable table, String aliasPrefix, boolean inlineKeys) {
        this.table = table;
        this.aliasPrefix = aliasPrefix;
        this.inlineKeys = inlineKeys;
    }

    /**
     * Locates a path in the child tables.
     *
     * @param field the dotted path from the root entity
     * @return where it leads; empty when it crosses no collection, or names a map key that cannot be a
     *         key of the map
     */
    public Optional<Located> locate(String field) {
        Optional<SqlChildTable> top = deepest(table.children(), field);
        if (top.isEmpty()) {
            return Optional.empty();
        }
        return descend(new ArrayList<>(), top.get(), remainder(top.get(), field));
    }

    /**
     * The hop to the table holding the items of an element that is itself a collection.
     *
     * @param holder the hop whose elements are collections
     * @return the hop to their items, or empty when the elements are not collections
     */
    public Optional<SqlHop> items(List<SqlHop> holder) {
        SqlHop last = holder.get(holder.size() - 1);
        return last.child().elementTable().map(items -> hop(holder, items));
    }

    private Optional<Located> descend(List<SqlHop> hops, SqlChildTable child, String rest) {
        if (child.kind() == SqlChildKind.MAP && rest != null) {
            return entry(hops, child, rest);
        }
        hops.add(hop(hops, child));
        if (rest == null) {
            return Optional.of(new Located(hops, null));
        }
        if (child.kind() == SqlChildKind.NESTED_COLLECTION) {
            // A list of maps is a list of sub-documents, traversed by key; a list of lists is not traversed.
            Optional<SqlChildTable> items = child.elementTable().filter(e -> e.kind() == SqlChildKind.MAP);
            return items.isPresent() ? descend(hops, items.get(), rest) : Optional.of(new Located(hops, rest));
        }
        return inside(hops, child, rest);
    }

    /** A map crossed through the key the path names: its entry, then what the entry's value holds. */
    private Optional<Located> entry(List<SqlHop> hops, SqlChildTable map, String rest) {
        boolean scalarValues = map.elementTable().isEmpty() && map.valueColumns().size() == 1
                && map.valueColumns().get(0).fieldPath().isEmpty();
        int dot = rest.indexOf('.');
        String key = scalarValues || dot < 0 ? rest : rest.substring(0, dot);
        String after = scalarValues || dot < 0 ? null : rest.substring(dot + 1);
        Optional<SqlHop> keyed = keyed(hops, map, key);
        if (keyed.isEmpty()) {
            return Optional.empty();
        }
        hops.add(keyed.get());
        Optional<SqlChildTable> value = map.elementTable();
        if (value.isPresent()) {
            // The entry's value is itself a collection: its rows live in the element table.
            return descend(hops, value.get(), after);
        }
        return after == null ? Optional.of(new Located(hops, null)) : inside(hops, map, after);
    }

    /** A path inside a POJO element: into a collection it holds, or onto its own columns. */
    private Optional<Located> inside(List<SqlHop> hops, SqlChildTable holder, String rest) {
        List<SqlChildTable> held = holder.children().stream().filter(c -> !c.fieldPath().isEmpty()).toList();
        Optional<SqlChildTable> next = deepest(held, rest);
        if (next.isPresent()) {
            return descend(hops, next.get(), remainder(next.get(), rest));
        }
        return Optional.of(new Located(hops, rest));
    }

    /** A hop over every row of a collection, under the row the previous hop (or the root) reached. */
    private SqlHop hop(List<SqlHop> hops, SqlChildTable child) {
        String alias = alias(hops.size() + 1);
        return new SqlHop(child, alias, scope(hops, alias), absent(hops, child, alias), false);
    }

    /** A hop onto one map entry: the rows of the map, narrowed to the key. */
    private Optional<SqlHop> keyed(List<SqlHop> hops, SqlChildTable map, String key) {
        String alias = alias(hops.size() + 1);
        SqlColumn keyColumn = new SqlColumn(SqlChildTable.KEY, SqlTypes.sqlTypeOf(map.keyType()).orElse(SqlTypes.TEXT),
                SqlColumnKind.SCALAR, List.of(), map.keyType());
        Object value;
        try {
            value = SqlValues.toJdbc(this.table.dialect(), keyColumn, key);
        } catch (ApiException e) {
            // A key that cannot be a key of this map (text against an Integer-keyed map) is in no document.
            return Optional.empty();
        }
        String ref = alias + "." + SqlNaming.quote(SqlChildTable.KEY);
        SqlFragment narrowed = inlineKeys
                ? SqlFragment.of(" AND " + ref + EQUALS + this.table.dialect().textLiteral(String.valueOf(value), keyColumn.sqlType()))
                : SqlFragment.of(" AND " + ref + EQUALS + SqlValues.placeholder(this.table.dialect(), keyColumn), value);
        SqlFragment scope = scope(hops, alias).then(narrowed);
        SqlFragment keyAbsent = SqlFragment.of("SELECT 1 FROM " + SqlNaming.quote(map.name()) + " " + alias + " WHERE ")
                .then(scope).wrap("NOT EXISTS (", ")");
        return Optional.of(new SqlHop(map, alias, scope, keyAbsent, true));
    }

    /** {@code _owner} to the root row for the first level, {@code _parent} to the row above for the others. */
    private SqlFragment scope(List<SqlHop> hops, String alias) {
        if (hops.isEmpty()) {
            return SqlFragment.of(alias + "." + SqlNaming.quote(SqlChildTable.OWNER) + EQUALS + SqlQuery.ALIAS + "."
                    + SqlNaming.quote(table.id().name()));
        }
        return SqlFragment.of(alias + "." + SqlNaming.quote(SqlChildTable.PARENT) + EQUALS
                + hops.get(hops.size() - 1).ref(SqlChildTable.ID));
    }

    /**
     * "The collection is missing from the row holding it": its presence bit there is NULL — on the root
     * row, or among the element columns of the parent table; for the table of an element that IS a
     * collection, the element's own presence bit. Without a bit, no row at all.
     */
    private SqlFragment absent(List<SqlHop> hops, SqlChildTable child, String alias) {
        Optional<String> bit;
        if (hops.isEmpty()) {
            bit = table.presence(child.dottedPath()).map(p -> SqlQuery.ALIAS + "." + SqlNaming.quote(p.name()));
        } else {
            SqlHop holder = hops.get(hops.size() - 1);
            List<String> path = child.fieldPath();
            bit = path.isEmpty() ? Optional.of(holder.ref(SqlChildTable.PRESENT))
                    : holder.child().valueColumns().stream()
                            .filter(c -> c.kind() == SqlColumnKind.PRESENCE && c.fieldPath().equals(path))
                            .findFirst().map(c -> holder.ref(c.name()));
        }
        return bit.map(b -> SqlFragment.of(b + " IS NULL")).orElseGet(() -> SqlFragment.of("SELECT 1 FROM "
                + SqlNaming.quote(child.name()) + " " + alias + " WHERE ").then(scope(hops, alias))
                .wrap("NOT EXISTS (", ")"));
    }

    private String alias(int depth) {
        return depth == 1 ? aliasPrefix : aliasPrefix + depth;
    }

    /** The table among {@code candidates} whose (relative) path is the field or its deepest proper prefix. */
    private static Optional<SqlChildTable> deepest(List<SqlChildTable> candidates, String field) {
        return candidates.stream()
                .filter(c -> field.equals(relative(c)) || field.startsWith(relative(c) + "."))
                .max(Comparator.comparingInt(c -> relative(c).length()));
    }

    /** {@return the path after a table's own, or null when the field names the table itself} */
    private static String remainder(SqlChildTable child, String field) {
        String own = relative(child);
        return field.length() == own.length() ? null : field.substring(own.length() + 1);
    }

    private static String relative(SqlChildTable child) {
        return String.join(".", child.fieldPath());
    }

}
