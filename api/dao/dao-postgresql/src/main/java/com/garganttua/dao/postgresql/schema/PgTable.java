package com.garganttua.dao.postgresql.schema;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The relational shape of one domain: its main table and the child tables of its collections.
 *
 * <p>
 * The single source of truth shared by every part of the DAO — the DDL, the writer, the reader and
 * the filter translator all read it, none of them re-derives the mapping on its own. Built once per
 * domain by {@link PgSchemaModel#of}.
 * </p>
 *
 * @param name         the main table name, unquoted
 * @param id           the primary-key column (the DTO uuid field)
 * @param columns      every main-table column INCLUDING {@code id}, in declaration order
 * @param children     the TOP-LEVEL child tables, one per collection or map field of the entity; the
 *                     tables of collections held by their elements hang below them ({@link #allChildren})
 * @param compositions every {@code @Composed} field — single or collection — by its dotted path, to
 *                     the domain it references: what the writer needs to read the target's uuid and
 *                     the reader to resolve it
 */
public record PgTable(String name, PgColumn id, List<PgColumn> columns, List<PgChildTable> children,
        Map<String, String> compositions) {

    public PgTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(id, "id");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        compositions = compositions == null ? Map.of() : Map.copyOf(compositions);
    }

    /**
     * The domain a {@code @Composed} field references.
     *
     * @param dottedPath the composition field's path
     * @return the target domain name, or empty when the path is not a composition
     */
    public Optional<String> compositionTarget(String dottedPath) {
        return Optional.ofNullable(compositions.get(dottedPath));
    }

    /**
     * The main-table column mapping a dotted DTO field path ({@code name}, {@code address.city}).
     *
     * @param dottedPath the path as filters, sorts and projections write it
     * @return the column, or empty when the path names a collection (see {@link #child}) or nothing
     */
    public Optional<PgColumn> column(String dottedPath) {
        return columns.stream()
                .filter(c -> c.kind() != PgColumnKind.PRESENCE && c.dottedPath().equals(dottedPath))
                .findFirst();
    }

    /**
     * The presence column of a structure — a flattened POJO, a collection, a map or a reference
     * collection — at a dotted path. Not returned by {@link #column}: a presence bit is not the value
     * of the field, and resolving a filter on {@code address} or {@code tags} must not land on it.
     *
     * @param dottedPath the structure's path
     * @return its presence column, or empty when the path is not a structure
     */
    public Optional<PgColumn> presence(String dottedPath) {
        return columns.stream()
                .filter(c -> c.kind() == PgColumnKind.PRESENCE && c.dottedPath().equals(dottedPath))
                .findFirst();
    }

    /**
     * The child table holding the collection at a dotted DTO field path.
     *
     * @param dottedPath the path of the collection field itself ({@code tags}, {@code address.tags})
     * @return the child table, or empty when the path is not a collection
     */
    public Optional<PgChildTable> child(String dottedPath) {
        return allChildren().stream().filter(c -> c.dottedPath().equals(dottedPath)).findFirst();
    }

    /**
     * Every child table at every depth, parents before their children (the order DDL needs).
     *
     * @return the child tables, depth-first
     */
    public List<PgChildTable> allChildren() {
        List<PgChildTable> out = new java.util.ArrayList<>();
        for (PgChildTable child : children) {
            collect(child, out);
        }
        return out;
    }

    private static void collect(PgChildTable child, List<PgChildTable> out) {
        out.add(child);
        for (PgChildTable grandchild : child.children()) {
            collect(grandchild, out);
        }
    }

    /** {@return whether any column needs PostGIS — the DDL must then create the extension} */
    public boolean needsPostgis() {
        return columns.stream().anyMatch(c -> c.kind() == PgColumnKind.GEOMETRY)
                || allChildren().stream().flatMap(c -> c.valueColumns().stream())
                        .anyMatch(c -> c.kind() == PgColumnKind.GEOMETRY);
    }
}
