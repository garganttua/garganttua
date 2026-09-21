package com.garganttua.dao.postgresql;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * Follows a dotted field path down the tree of child tables, one {@link PgHop} per collection or map
 * entry it crosses — at any depth.
 *
 * <p>
 * The walk follows MongoDB's path traversal: a list is crossed element by element, a map (a
 * sub-document) through the key the next segment names, and an element that is itself a list
 * ({@code List<List<String>>}) is NOT descended into by a field name — MongoDB does not traverse
 * nested arrays for a dotted path either. A map whose values are collections
 * ({@code Map<String, List<Book>>}) is crossed through its key, then through the table of the entry's
 * value ({@link PgChildTable#elementTable}).
 * </p>
 *
 * <p>
 * Map keys are filter text: they are bound as parameters — or, for an {@code ORDER BY} (which carries
 * none), inlined as a hex-encoded literal, which cannot break out of its quotes whatever the key holds.
 * </p>
 */
final class PgPathLocator {

    /**
     * Where a path leads.
     *
     * @param hops the child-table hops, outermost first — at least one
     * @param rest the rest of the path inside the last hop's element; null when the path names the
     *             last hop's collection itself, or the value of the map entry it reached
     */
    record Located(List<PgHop> hops, String rest) {

        Located {
            hops = List.copyOf(hops);
        }

        /** {@return the last hop} */
        PgHop last() {
            return hops.get(hops.size() - 1);
        }
    }

    private static final String EQUALS = " = ";

    private final PgTable table;
    private final String aliasPrefix;
    private final boolean inlineKeys;

    /**
     * @param table       the domain's model
     * @param aliasPrefix the alias of the first child level ({@code c}); deeper levels append their
     *                    depth ({@code c2}, {@code c3})
     * @param inlineKeys  whether map keys are inlined as literals (for an {@code ORDER BY}) instead of
     *                    bound
     */
    PgPathLocator(PgTable table, String aliasPrefix, boolean inlineKeys) {
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
    Optional<Located> locate(String field) {
        Optional<PgChildTable> top = deepest(table.children(), field);
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
    Optional<PgHop> items(List<PgHop> holder) {
        PgHop last = holder.get(holder.size() - 1);
        return last.child().elementTable().map(items -> hop(holder, items));
    }

    private Optional<Located> descend(List<PgHop> hops, PgChildTable child, String rest) {
        if (child.kind() == PgChildKind.MAP && rest != null) {
            return entry(hops, child, rest);
        }
        hops.add(hop(hops, child));
        if (rest == null) {
            return Optional.of(new Located(hops, null));
        }
        if (child.kind() == PgChildKind.NESTED_COLLECTION) {
            // A list of maps is a list of sub-documents, traversed by key; a list of lists is not traversed.
            Optional<PgChildTable> items = child.elementTable().filter(e -> e.kind() == PgChildKind.MAP);
            return items.isPresent() ? descend(hops, items.get(), rest) : Optional.of(new Located(hops, rest));
        }
        return inside(hops, child, rest);
    }

    /** A map crossed through the key the path names: its entry, then what the entry's value holds. */
    private Optional<Located> entry(List<PgHop> hops, PgChildTable map, String rest) {
        boolean scalarValues = map.elementTable().isEmpty() && map.valueColumns().size() == 1
                && map.valueColumns().get(0).fieldPath().isEmpty();
        int dot = rest.indexOf('.');
        String key = scalarValues || dot < 0 ? rest : rest.substring(0, dot);
        String after = scalarValues || dot < 0 ? null : rest.substring(dot + 1);
        Optional<PgHop> keyed = keyed(hops, map, key);
        if (keyed.isEmpty()) {
            return Optional.empty();
        }
        hops.add(keyed.get());
        Optional<PgChildTable> value = map.elementTable();
        if (value.isPresent()) {
            // The entry's value is itself a collection: its rows live in the element table.
            return descend(hops, value.get(), after);
        }
        return after == null ? Optional.of(new Located(hops, null)) : inside(hops, map, after);
    }

    /** A path inside a POJO element: into a collection it holds, or onto its own columns. */
    private Optional<Located> inside(List<PgHop> hops, PgChildTable holder, String rest) {
        List<PgChildTable> held = holder.children().stream().filter(c -> !c.fieldPath().isEmpty()).toList();
        Optional<PgChildTable> next = deepest(held, rest);
        if (next.isPresent()) {
            return descend(hops, next.get(), remainder(next.get(), rest));
        }
        return Optional.of(new Located(hops, rest));
    }

    /** A hop over every row of a collection, under the row the previous hop (or the root) reached. */
    private PgHop hop(List<PgHop> hops, PgChildTable child) {
        String alias = alias(hops.size() + 1);
        return new PgHop(child, alias, scope(hops, alias), absent(hops, child, alias), false);
    }

    /** A hop onto one map entry: the rows of the map, narrowed to the key. */
    private Optional<PgHop> keyed(List<PgHop> hops, PgChildTable map, String key) {
        String alias = alias(hops.size() + 1);
        PgColumn keyColumn = new PgColumn(PgChildTable.KEY, PgTypes.sqlTypeOf(map.keyType()).orElse(PgTypes.TEXT),
                PgColumnKind.SCALAR, List.of(), map.keyType());
        Object value;
        try {
            value = PgValues.toJdbc(keyColumn, key);
        } catch (ApiException e) {
            // A key that cannot be a key of this map (text against an Integer-keyed map) is in no document.
            return Optional.empty();
        }
        String ref = alias + "." + PgNaming.quote(PgChildTable.KEY);
        PgSql narrowed = inlineKeys
                ? PgSql.of(" AND " + ref + EQUALS + literal(String.valueOf(value), keyColumn.sqlType()))
                : PgSql.of(" AND " + ref + EQUALS + PgValues.placeholder(keyColumn), value);
        PgSql scope = scope(hops, alias).then(narrowed);
        PgSql keyAbsent = PgSql.of("SELECT 1 FROM " + PgNaming.quote(map.name()) + " " + alias + " WHERE ")
                .then(scope).wrap("NOT EXISTS (", ")");
        return Optional.of(new PgHop(map, alias, scope, keyAbsent, true));
    }

    /** {@code _owner} to the root row for the first level, {@code _parent} to the row above for the others. */
    private PgSql scope(List<PgHop> hops, String alias) {
        if (hops.isEmpty()) {
            return PgSql.of(alias + "." + PgNaming.quote(PgChildTable.OWNER) + EQUALS + PgQuery.ALIAS + "."
                    + PgNaming.quote(table.id().name()));
        }
        return PgSql.of(alias + "." + PgNaming.quote(PgChildTable.PARENT) + EQUALS
                + hops.get(hops.size() - 1).ref(PgChildTable.ID));
    }

    /**
     * "The collection is missing from the row holding it": its presence bit there is NULL — on the root
     * row, or among the element columns of the parent table; for the table of an element that IS a
     * collection, the element's own presence bit. Without a bit, no row at all.
     */
    private PgSql absent(List<PgHop> hops, PgChildTable child, String alias) {
        Optional<String> bit;
        if (hops.isEmpty()) {
            bit = table.presence(child.dottedPath()).map(p -> PgQuery.ALIAS + "." + PgNaming.quote(p.name()));
        } else {
            PgHop holder = hops.get(hops.size() - 1);
            List<String> path = child.fieldPath();
            bit = path.isEmpty() ? Optional.of(holder.ref(PgChildTable.PRESENT))
                    : holder.child().valueColumns().stream()
                            .filter(c -> c.kind() == PgColumnKind.PRESENCE && c.fieldPath().equals(path))
                            .findFirst().map(c -> holder.ref(c.name()));
        }
        return bit.map(b -> PgSql.of(b + " IS NULL")).orElseGet(() -> PgSql.of("SELECT 1 FROM "
                + PgNaming.quote(child.name()) + " " + alias + " WHERE ").then(scope(hops, alias))
                .wrap("NOT EXISTS (", ")"));
    }

    private String alias(int depth) {
        return depth == 1 ? aliasPrefix : aliasPrefix + depth;
    }

    /** The table among {@code candidates} whose (relative) path is the field or its deepest proper prefix. */
    private static Optional<PgChildTable> deepest(List<PgChildTable> candidates, String field) {
        return candidates.stream()
                .filter(c -> field.equals(relative(c)) || field.startsWith(relative(c) + "."))
                .max(Comparator.comparingInt(c -> relative(c).length()));
    }

    /** {@return the path after a table's own, or null when the field names the table itself} */
    private static String remainder(PgChildTable child, String field) {
        String own = relative(child);
        return field.length() == own.length() ? null : field.substring(own.length() + 1);
    }

    private static String relative(PgChildTable child) {
        return String.join(".", child.fieldPath());
    }

    /** A text literal no content can escape — hex digits only — cast to the key column's type. */
    private static String literal(String text, String sqlType) {
        String hex = HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
        String decoded = "convert_from(decode('" + hex + "', 'hex'), 'UTF8')";
        return PgTypes.TEXT.equals(sqlType) ? decoded : "CAST(" + decoded + " AS " + sqlType + ")";
    }
}
