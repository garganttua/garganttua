package com.garganttua.dao.postgresql.schema;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geojson.GeoJsonObject;

import com.garganttua.core.crypto.IKey;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;

/**
 * Derives the relational shape of a domain from its DTO class.
 *
 * <p>
 * One rule per kind of field, applied recursively:
 * </p>
 * <table>
 * <caption>How a DTO field becomes columns</caption>
 * <tr><th>Field</th><th>Becomes</th></tr>
 * <tr><td>the uuid field</td><td>{@code TEXT PRIMARY KEY}</td></tr>
 * <tr><td>a scalar ({@link PgTypes})</td><td>one typed column</td></tr>
 * <tr><td>a {@code @Composed} reference</td><td>a {@code TEXT} column of the referenced uuid</td></tr>
 * <tr><td>a collection of {@code @Composed}</td><td>a child table of uuids</td></tr>
 * <tr><td>{@code IKey}</td><td>a {@code JSONB} key descriptor</td></tr>
 * <tr><td>a GeoJSON geometry</td><td>a PostGIS {@code geometry} column</td></tr>
 * <tr><td>an embedded POJO</td><td>its fields, flattened with a prefix ({@code address__city})</td></tr>
 * <tr><td>a list/set of scalars or of POJOs</td><td>a child table, one row per element</td></tr>
 * <tr><td>a map with scalar keys</td><td>a child table keyed by {@code _key}</td></tr>
 * <tr><td>anything else</td><td>a {@code JSONB} column</td></tr>
 * </table>
 *
 * <p>
 * <b>Presence.</b> Relational storage loses a fact MongoDB keeps: whether a structure EXISTED. A
 * null list and an empty one are both zero child rows; a null POJO and one whose fields are all null
 * are both all-NULL columns. MongoDB answers differently for each ({@code $empty}, {@code $eq null},
 * and what {@code find} hands back), so every flattened POJO, every collection, map and reference
 * collection gets a {@link PgColumnKind#PRESENCE} column on its owner — {@code TRUE} when the value
 * was there, {@code NULL} when it was not — and every POJO element of a child table a
 * {@link PgChildTable#PRESENT} column, for the same reason one level down.
 * </p>
 *
 * <p>
 * The {@code JSONB} fallback is not laziness. Some shapes have no finite relational form: a POJO
 * that contains itself ({@code Node self}) would flatten into infinitely many columns, and an
 * untyped {@code List<Object>} (GeoJSON coordinates are nested lists of numbers) has no column type
 * at all. Those are stored as documents, and everything with a finite, typed shape gets real columns.
 * </p>
 *
 * <p>
 * Two more bounds keep the mapping finite: a child table never has children of its own (a collection
 * inside a collection element becomes a {@code JSONB} value column), and an abstract class or an
 * interface is never flattened (the reader could not instantiate it).
 * </p>
 */
public final class PgSchemaModel {

    private PgSchemaModel() {
        // Static factory
    }

    /**
     * Builds the relational shape of one domain.
     *
     * @param domainName   the domain name — the main table is named after it
     * @param dtoClass     the domain's persisted DTO class
     * @param uuidField    the DTO field holding the uuid — the primary key
     * @param compositions the {@code @Composed} fields: DTO field name to target domain name
     * @return the table model
     * @throws IllegalArgumentException if the DTO has no uuid field, or two fields map to one column
     */
    public static PgTable of(String domainName, IClass<?> dtoClass, String uuidField,
            Map<String, String> compositions) {
        String table = PgNaming.table(domainName);
        List<PgColumn> columns = new ArrayList<>();
        List<PgChildTable> children = new ArrayList<>();
        PgColumn id = null;

        for (IField field : persistedFields(dtoClass)) {
            String name = field.getName();
            List<String> path = List.of(name);
            if (name.equals(uuidField)) {
                id = new PgColumn(PgNaming.column(path), PgTypes.TEXT, PgColumnKind.ID, path, field.getType());
                continue;
            }
            String target = compositions == null ? null : compositions.get(name);
            if (target != null) {
                addComposition(table, field, path, target, columns, children);
                continue;
            }
            new Walker(table, null, List.of()).add(path, field.getType(), field.getGenericType(), Set.of(),
                    columns, children);
        }
        if (id == null) {
            throw new IllegalArgumentException("DTO " + dtoClass.getName() + " has no uuid field '"
                    + uuidField + "': a relational table needs a primary key, and the domain uuid is it.");
        }
        List<PgColumn> all = new ArrayList<>();
        all.add(id);
        all.addAll(columns);
        refuseCollisions(table, all, children);
        return new PgTable(table, id, all, children, composedFields(dtoClass, compositions));
    }

    /** The {@code @Composed} fields the DTO really persists, in field order: field name to target domain. */
    private static Map<String, String> composedFields(IClass<?> dtoClass, Map<String, String> compositions) {
        Map<String, String> composed = new LinkedHashMap<>();
        if (compositions != null) {
            for (IField field : persistedFields(dtoClass)) {
                String target = compositions.get(field.getName());
                if (target != null) {
                    composed.put(field.getName(), target);
                }
            }
        }
        return composed;
    }

    private static void addComposition(String table, IField field, List<String> path, String target,
            List<PgColumn> columns, List<PgChildTable> children) {
        if (isCollection(field.getType())) {
            PgColumn value = new PgColumn(PgChildTable.VALUE, PgTypes.TEXT, PgColumnKind.COMPOSITION,
                    List.of(), IClass.getClass(String.class));
            columns.add(new PgColumn(PgNaming.column(path), PgTypes.BOOLEAN, PgColumnKind.PRESENCE, path,
                    field.getType()));
            children.add(new PgChildTable(PgNaming.childTable(table, path),
                    PgChildKind.COMPOSITION_COLLECTION, path, field.getType(),
                    elementType(field.getGenericType(), 0), null, List.of(value), target));
        } else {
            columns.add(new PgColumn(PgNaming.column(path), PgTypes.TEXT, PgColumnKind.COMPOSITION, path,
                    field.getType()));
        }
    }

    /**
     * Classifies values and emits their columns and child tables — at any depth.
     *
     * <p>
     * A Walker walks the fields of ONE owning object: the root entity, or the element of a child table.
     * Paths it emits are relative to that owner; child-table NAMES use the absolute path, so they stay
     * unique across the whole tree ({@code shops__orders__lines}).
     * </p>
     *
     * @param table      the root table, for child-table names
     * @param ownerChild the child table whose element is being walked, or null for the root entity
     * @param prefix     the absolute path of the owner (empty for the root entity)
     */
    private record Walker(String table, String ownerChild, List<String> prefix) {

        void add(List<String> path, IClass<?> type, Type generic, Set<IClass<?>> visiting,
                List<PgColumn> columns, List<PgChildTable> children) {
            String scalar = PgTypes.sqlTypeOf(type).orElse(null);
            if (scalar != null) {
                columns.add(column(path, scalar, PgColumnKind.SCALAR, type));
            } else if (IClass.getClass(IKey.class).isAssignableFrom(type)) {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.IKEY, type));
            } else if (IClass.getClass(GeoJsonObject.class).isAssignableFrom(type)) {
                columns.add(column(path, PgTypes.GEOMETRY, PgColumnKind.GEOMETRY, type));
            } else if (isCollection(type) || isMap(type)) {
                PgChildTable child = containerTable(path, type, generic, visiting);
                if (child == null) {
                    columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
                } else {
                    columns.add(column(path, PgTypes.BOOLEAN, PgColumnKind.PRESENCE, type));
                    children.add(child);
                }
            } else if (isFlattenable(type, visiting)) {
                // Flattening loses whether the POJO existed: null and "present with every field null"
                // both become all-NULL columns. MongoDB keeps them apart ({} vs absent), so the fact is
                // stored beside the columns.
                columns.add(column(path, PgTypes.BOOLEAN, PgColumnKind.PRESENCE, type));
                Set<IClass<?>> deeper = new HashSet<>(visiting);
                deeper.add(type);
                for (IField sub : persistedFields(type)) {
                    List<String> subPath = new ArrayList<>(path);
                    subPath.add(sub.getName());
                    add(subPath, sub.getType(), sub.getGenericType(), deeper, columns, children);
                }
            } else {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
            }
        }

        /**
         * The child table of a collection or map field at {@code path}, or null when it has no relational
         * form (an untyped element, a map with non-scalar keys, an element type that recurses).
         */
        private PgChildTable containerTable(List<String> path, IClass<?> type, Type generic,
                Set<IClass<?>> visiting) {
            List<String> absolute = concat(prefix, path);
            return build(PgNaming.childTable(table, absolute), path, absolute, type, generic, visiting);
        }

        private PgChildTable build(String name, List<String> path, List<String> absolute, IClass<?> type,
                Type generic, Set<IClass<?>> visiting) {
            boolean map = isMap(type);
            Type elementGeneric = type.isArray() ? null : typeArgument(generic, map ? 1 : 0);
            IClass<?> element = type.isArray() ? type.getComponentType() : rawOf(elementGeneric);
            IClass<?> key = map ? rawOf(typeArgument(generic, 0)) : null;
            if (element == null || (map && (key == null || !PgTypes.isScalar(key)))) {
                return null;
            }
            String scalar = PgTypes.sqlTypeOf(element).orElse(null);
            if (scalar != null) {
                PgColumn value = new PgColumn(PgChildTable.VALUE, scalar, PgColumnKind.SCALAR, List.of(), element);
                return new PgChildTable(name, map ? PgChildKind.MAP : PgChildKind.SCALAR_COLLECTION, path, type,
                        element, key, List.of(value), null, absolute, ownerChild, List.of());
            }
            if (isFlattenable(element, visiting)) {
                List<PgColumn> columns = new ArrayList<>();
                List<PgChildTable> children = new ArrayList<>();
                // A null element and an element whose fields are all null would both be an all-NULL row.
                columns.add(new PgColumn(PgChildTable.PRESENT, PgTypes.BOOLEAN, PgColumnKind.PRESENCE, List.of(), element));
                Walker inner = new Walker(table, name, absolute);
                Set<IClass<?>> deeper = new HashSet<>(visiting);
                deeper.add(element);
                for (IField sub : persistedFields(element)) {
                    inner.add(List.of(sub.getName()), sub.getType(), sub.getGenericType(), deeper, columns, children);
                }
                return new PgChildTable(name, map ? PgChildKind.MAP : PgChildKind.POJO_COLLECTION, path, type,
                        element, key, columns, null, absolute, ownerChild, children);
            }
            if (isCollection(element) || isMap(element)) {
                // The element IS a collection (List<List<String>>, Map<String, List<Book>>): it becomes a
                // table of its own, under this one, with an empty field path — "the element itself".
                PgChildTable itself = new Walker(table, name, absolute)
                        .build(name + PgNaming.PATH_SEPARATOR + ELEMENT_SUFFIX, List.of(), absolute, element,
                                elementGeneric, visiting);
                if (itself == null) {
                    return null;
                }
                PgColumn present = new PgColumn(PgChildTable.PRESENT, PgTypes.BOOLEAN, PgColumnKind.PRESENCE, List.of(), element);
                return new PgChildTable(name, map ? PgChildKind.MAP : PgChildKind.NESTED_COLLECTION, path, type,
                        element, key, List.of(present), null, absolute, ownerChild, List.of(itself));
            }
            return null;
        }

        private static PgColumn column(List<String> path, String sqlType, PgColumnKind kind, IClass<?> type) {
            return new PgColumn(PgNaming.column(path), sqlType, kind, path, type);
        }
    }

    private static void collectTree(PgChildTable child, List<PgChildTable> out) {
        out.add(child);
        child.children().forEach(c -> collectTree(c, out));
    }

    /** Suffix of the table holding an element that is itself a collection. */
    static final String ELEMENT_SUFFIX = "_e";

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The type argument at {@code index} of a parameterized type — possibly parameterized itself. */
    static Type typeArgument(Type generic, int index) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length > index) {
            return parameterized.getActualTypeArguments()[index];
        }
        return null;
    }

    /** The class of a type argument: the class itself, or the raw class of a parameterized type. */
    static IClass<?> rawOf(Type type) {
        if (type instanceof Class<?> c) {
            return IClass.getClass(c);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
            return IClass.getClass(c);
        }
        return null;
    }

    /**
     * Whether a type can be flattened into columns: a concrete class the reader can instantiate, not
     * a JDK type, and whose embedded POJO fields are flattenable in turn without meeting a type
     * already on the way down. The last condition is what catches {@code Node self}.
     */
    static boolean isFlattenable(IClass<?> type, Set<IClass<?>> visiting) {
        if (type == null || type.isInterface() || type.isArray() || type.isPrimitive() || type.isEnum()
                || Modifier.isAbstract(type.getModifiers()) || visiting.contains(type)
                || isJdkType(type)) {
            return false;
        }
        Set<IClass<?>> deeper = new HashSet<>(visiting);
        deeper.add(type);
        for (IField sub : persistedFields(type)) {
            IClass<?> subType = sub.getType();
            if (isStructuralPojo(subType) && !isFlattenable(subType, deeper)) {
                return false;
            }
        }
        return true;
    }

    /** A field type that is neither a leaf nor a container — i.e. one flattening would recurse into. */
    private static boolean isStructuralPojo(IClass<?> type) {
        return !PgTypes.isScalar(type) && !isCollection(type) && !isMap(type)
                && !IClass.getClass(IKey.class).isAssignableFrom(type)
                && !IClass.getClass(GeoJsonObject.class).isAssignableFrom(type)
                && !isJdkType(type) && !type.isInterface();
    }

    private static boolean isJdkType(IClass<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }

    static boolean isCollection(IClass<?> type) {
        return (type.isArray() && !type.represents(byte[].class))
                || IClass.getClass(Collection.class).isAssignableFrom(type);
    }

    static boolean isMap(IClass<?> type) {
        return IClass.getClass(Map.class).isAssignableFrom(type);
    }

    /** The concrete type argument at {@code index}, or null when it is not a plain class. */
    static IClass<?> elementType(Type generic, int index) {
        if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length > index
                && parameterized.getActualTypeArguments()[index] instanceof Class<?> c) {
            return IClass.getClass(c);
        }
        return null;
    }

    /** Instance fields that are persisted: not static, not transient, not synthetic, subclass first. */
    static List<IField> persistedFields(IClass<?> type) {
        Map<String, IField> fields = new LinkedHashMap<>();
        IClass<?> current = type;
        while (current != null && !current.represents(Object.class)) {
            for (IField field : current.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic()) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), field);
            }
            current = current.getSuperclass();
        }
        return new ArrayList<>(fields.values());
    }

    /**
     * Refuses a model in which two fields land on one name — which PostgreSQL would either reject at
     * DDL time or, worse, accept after silently truncating both to the same 63 bytes.
     */
    private static void refuseCollisions(String table, List<PgColumn> columns, List<PgChildTable> children) {
        Set<String> seen = new HashSet<>();
        for (PgColumn c : columns) {
            if (!seen.add(c.name())) {
                throw new IllegalArgumentException("Two fields of table '" + table + "' map to column '"
                        + c.name() + "' — rename one; a flattened path cannot share a name with a field.");
            }
        }
        Set<String> tables = new HashSet<>();
        tables.add(table);
        List<PgChildTable> all = new ArrayList<>();
        children.forEach(c -> collectTree(c, all));
        for (PgChildTable child : all) {
            if (!tables.add(child.name())) {
                throw new IllegalArgumentException("Two collections of table '" + table
                        + "' map to child table '" + child.name() + "'.");
            }
        }
    }
}
