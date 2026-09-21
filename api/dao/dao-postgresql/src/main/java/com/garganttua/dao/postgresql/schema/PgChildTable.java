package com.garganttua.dao.postgresql.schema;

import java.util.List;
import java.util.Objects;

import com.garganttua.core.reflection.IClass;

/**
 * A table holding one collection or map field, one row per element — and, since elements may
 * themselves hold collections, possibly the parent of further child tables, to any depth.
 *
 * <p>
 * Fixed columns, in this order:
 * </p>
 * <ul>
 * <li>{@link #ID} ({@code BIGINT} identity) — ONLY when this table has {@link #children()}: the key its
 * children's {@link #PARENT} references;</li>
 * <li>{@link #OWNER} ({@code TEXT}) — the ROOT entity's id, at every depth, foreign key with
 * {@code ON DELETE CASCADE}: one query per table loads the descendants of a whole page;</li>
 * <li>{@link #PARENT} ({@code BIGINT}) — ONLY on a nested table: the {@link #ID} of the parent row, foreign
 * key with {@code ON DELETE CASCADE}, so rewriting or deleting an entity takes its whole tree with it;</li>
 * <li>{@link #ORD} ({@code INTEGER}) for lists and sets, or {@link #KEY} for maps;</li>
 * <li>then {@link #valueColumns()}.</li>
 * </ul>
 *
 * <p>
 * An element is described by its value columns (a scalar, or the flattened fields of a POJO) AND by the
 * child tables of the collections it holds. When the element IS a collection or a map
 * ({@code List<List<String>>}, {@code Map<String, List<Book>>}), it has no value column besides its
 * presence bit, and exactly one child table whose {@link #fieldPath()} is EMPTY: that table holds the
 * element itself.
 * </p>
 *
 * @param name               the table name, unquoted
 * @param kind               what it holds
 * @param fieldPath          the collection field's path from the object that OWNS it — the root entity
 *                           for a top-level table, the parent table's element for a nested one (empty
 *                           when the parent's element is this collection)
 * @param collectionType     the declared field type ({@code List}, {@code Set}, {@code Map}, …)
 * @param elementType        the element type (for maps: the VALUE type)
 * @param keyType            for maps, the key type; null otherwise
 * @param valueColumns       the value columns — one {@code value} column for scalars and
 *                           references, the flattened element columns for POJOs
 * @param composedCollection for {@link PgChildKind#COMPOSITION_COLLECTION}, the target domain; null
 *                           otherwise
 * @param absolutePath       the dotted path of this collection from the ROOT entity, as filters, sorts
 *                           and projections name it ({@code orders.lines})
 * @param parent             the name of the parent child table, or null for a top-level table
 * @param children           the child tables of the collections held by this table's elements
 */
public record PgChildTable(String name, PgChildKind kind, List<String> fieldPath,
        IClass<?> collectionType, IClass<?> elementType, IClass<?> keyType,
        List<PgColumn> valueColumns, String composedCollection, List<String> absolutePath,
        String parent, List<PgChildTable> children) {

    /** The row key a nested child table's {@link #PARENT} references — present only on parents. */
    public static final String ID = "_id";
    /** The ROOT entity's id, at every depth. */
    public static final String OWNER = "_owner";
    /** The parent row's {@link #ID} — present only on nested tables. */
    public static final String PARENT = "_parent";
    /** The element's position, for lists and sets. */
    public static final String ORD = "_ord";
    /** The map key, for maps. */
    public static final String KEY = "_key";
    /** Whether a POJO element existed — a null element and an all-null one are otherwise the same row. */
    public static final String PRESENT = "_present";

    /** The single value column of scalar and reference collections. */
    public static final String VALUE = "value";

    public PgChildTable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        fieldPath = List.copyOf(Objects.requireNonNull(fieldPath, "fieldPath"));
        valueColumns = List.copyOf(Objects.requireNonNull(valueColumns, "valueColumns"));
        absolutePath = absolutePath == null ? fieldPath : List.copyOf(absolutePath);
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** A top-level child table without children — the shape every table had before nesting. */
    public PgChildTable(String name, PgChildKind kind, List<String> fieldPath, IClass<?> collectionType,
            IClass<?> elementType, IClass<?> keyType, List<PgColumn> valueColumns, String composedCollection) {
        this(name, kind, fieldPath, collectionType, elementType, keyType, valueColumns, composedCollection,
                fieldPath, null, List.of());
    }

    /** {@return the ABSOLUTE path joined with dots — what filters, sorts and projections name} */
    public String dottedPath() {
        return String.join(".", absolutePath);
    }

    /** {@return whether rows are ordered by {@link #ORD} (lists, sets) rather than keyed by {@link #KEY}} */
    public boolean ordered() {
        return kind != PgChildKind.MAP;
    }

    /** {@return whether this table sits under another child table (it then has a {@link #PARENT} column)} */
    public boolean nested() {
        return parent != null;
    }

    /** {@return whether rows of this table carry an {@link #ID} — true exactly when it has children} */
    public boolean hasChildren() {
        return !children.isEmpty();
    }

    /**
     * The child table holding the element ITSELF, when the element is a collection or a map.
     *
     * @return that table, or empty when elements are scalars or POJOs
     */
    public java.util.Optional<PgChildTable> elementTable() {
        return children.stream().filter(c -> c.fieldPath().isEmpty()).findFirst();
    }
}
