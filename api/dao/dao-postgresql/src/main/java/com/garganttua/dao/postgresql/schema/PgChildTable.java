package com.garganttua.dao.postgresql.schema;

import java.util.List;
import java.util.Objects;

import com.garganttua.core.reflection.IClass;

/**
 * A table holding one collection or map field of the owning entity, one row per element.
 *
 * <p>
 * Fixed columns, in this order: {@link #OWNER} ({@code TEXT}, the owning row's id, foreign key with
 * {@code ON DELETE CASCADE}), then {@link #ORD} ({@code INTEGER}, the element's position) for lists
 * and sets or {@link #KEY} (the map key) for maps. Then {@link #valueColumns()}. A child table never
 * has children of its own: a collection nested inside an element becomes a {@code JSONB} value
 * column instead — that keeps the mapping bounded.
 * </p>
 *
 * @param name               the table name, unquoted
 * @param kind               what it holds
 * @param fieldPath          the DTO field path from the ROOT entity to the collection
 * @param collectionType     the declared field type ({@code List}, {@code Set}, {@code Map}, …)
 * @param elementType        the element type (for maps: the VALUE type)
 * @param keyType            for maps, the key type; null otherwise
 * @param valueColumns       the value columns — one {@code value} column for scalars and
 *                           references, the flattened element columns for POJOs
 * @param composedCollection for {@link PgChildKind#COMPOSITION_COLLECTION}, the target domain; null
 *                           otherwise
 */
public record PgChildTable(String name, PgChildKind kind, List<String> fieldPath,
        IClass<?> collectionType, IClass<?> elementType, IClass<?> keyType,
        List<PgColumn> valueColumns, String composedCollection) {

    /** The owning row's id. */
    public static final String OWNER = "_owner";
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
    }

    /** {@return the field path joined with dots} */
    public String dottedPath() {
        return String.join(".", fieldPath);
    }

    /** {@return whether rows are ordered by {@link #ORD} (lists, sets) rather than keyed by {@link #KEY}} */
    public boolean ordered() {
        return kind != PgChildKind.MAP;
    }
}
