package com.garganttua.dao.postgresql.schema;

import java.util.List;
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
 * @param name     the main table name, unquoted
 * @param id       the primary-key column (the DTO uuid field)
 * @param columns  every main-table column INCLUDING {@code id}, in declaration order
 * @param children the child tables, one per collection or map field
 */
public record PgTable(String name, PgColumn id, List<PgColumn> columns, List<PgChildTable> children) {

    public PgTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(id, "id");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }

    /**
     * The main-table column mapping a dotted DTO field path ({@code name}, {@code address.city}).
     *
     * @param dottedPath the path as filters, sorts and projections write it
     * @return the column, or empty when the path names a collection (see {@link #child}) or nothing
     */
    public Optional<PgColumn> column(String dottedPath) {
        return columns.stream().filter(c -> c.dottedPath().equals(dottedPath)).findFirst();
    }

    /**
     * The child table holding the collection at a dotted DTO field path.
     *
     * @param dottedPath the path of the collection field itself ({@code tags}, {@code address.tags})
     * @return the child table, or empty when the path is not a collection
     */
    public Optional<PgChildTable> child(String dottedPath) {
        return children.stream().filter(c -> c.dottedPath().equals(dottedPath)).findFirst();
    }

    /** {@return whether any column needs PostGIS — the DDL must then create the extension} */
    public boolean needsPostgis() {
        return columns.stream().anyMatch(c -> c.kind() == PgColumnKind.GEOMETRY)
                || children.stream().flatMap(c -> c.valueColumns().stream())
                        .anyMatch(c -> c.kind() == PgColumnKind.GEOMETRY);
    }
}
