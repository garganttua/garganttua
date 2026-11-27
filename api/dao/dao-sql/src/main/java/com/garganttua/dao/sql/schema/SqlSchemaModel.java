package com.garganttua.dao.sql.schema;

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
import com.garganttua.dao.sql.ISqlDialect;
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
 * <tr><td>a scalar ({@link SqlTypes})</td><td>one typed column</td></tr>
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
 * collection gets a {@link SqlColumnKind#PRESENCE} column on its owner — {@code TRUE} when the value
 * was there, {@code NULL} when it was not — and every POJO element of a child table a
 * {@link SqlChildTable#PRESENT} column, for the same reason one level down.
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
 * <b>Nested tables.</b> A collection or map inside a collection element (a {@code List<String>} in a
 * POJO element, a {@code List<List<String>>}, a {@code Map<String, List<Book>>}) is a child table of the
 * element's table, at any depth: its rows point at their element through {@code _parent} (the element
 * row's {@code _id}) and at the root entity through {@code _owner}. An element that is itself a
 * collection gets a table of its own, with an empty field path ({@link SqlChildTable#elementTable()}).
 * </p>
 *
 * <p>
 * Bounds that keep the mapping finite: an element type that recurses, an untyped element or a map with
 * non-scalar keys stays {@code JSONB}, and an abstract class or an interface is never flattened (the
 * reader could not instantiate it).
 * </p>
 */
public final class SqlSchemaModel {

    private SqlSchemaModel() {
        // Static factory
    }

    /**
     * Builds the relational shape of one domain.
     *
     * @param dialect      the database the tables live on — carried by the model to every collaborator
     * @param domainName   the domain name — the main table is named after it
     * @param dtoClass     the domain's persisted DTO class
     * @param uuidField    the DTO field holding the uuid — the primary key
     * @param compositions the {@code @Composed} fields: DTO field name to target domain name
     * @return the table model
     * @throws IllegalArgumentException if the DTO has no uuid field, or two fields map to one column
     */
    public static SqlTable of(ISqlDialect dialect, String domainName, IClass<?> dtoClass, String uuidField,
            Map<String, String> compositions) {
        String table = SqlNaming.table(domainName);
        List<SqlColumn> columns = new ArrayList<>();
        List<SqlChildTable> children = new ArrayList<>();
        SqlColumn id = null;

        for (IField field : persistedFields(dtoClass)) {
            String name = field.getName();
            List<String> path = List.of(name);
            if (name.equals(uuidField)) {
                id = new SqlColumn(SqlNaming.column(path), SqlTypes.TEXT, SqlColumnKind.ID, path, field.getType());
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
        List<SqlColumn> all = new ArrayList<>();
        all.add(id);
        all.addAll(columns);
        refuseCollisions(table, all, children);
        return new SqlTable(table, id, all, children, composedFields(dtoClass, compositions), dialect);
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
            List<SqlColumn> columns, List<SqlChildTable> children) {
        if (isCollection(field.getType())) {
            SqlColumn value = new SqlColumn(SqlChildTable.VALUE, SqlTypes.TEXT, SqlColumnKind.COMPOSITION,
                    List.of(), IClass.getClass(String.class));
            columns.add(new SqlColumn(SqlNaming.column(path), SqlTypes.BOOLEAN, SqlColumnKind.PRESENCE, path,
                    field.getType()));
            children.add(new SqlChildTable(SqlNaming.childTable(table, path),
                    SqlChildKind.COMPOSITION_COLLECTION, path, field.getType(),
                    elementType(field.getGenericType(), 0), null, List.of(value), target));
        } else {
            columns.add(new SqlColumn(SqlNaming.column(path), SqlTypes.TEXT, SqlColumnKind.COMPOSITION, path,
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
                List<SqlColumn> columns, List<SqlChildTable> children) {
            String scalar = SqlTypes.sqlTypeOf(type).orElse(null);
            if (scalar != null) {
                columns.add(column(path, scalar, SqlColumnKind.SCALAR, type));
            } else if (IClass.getClass(IKey.class).isAssignableFrom(type)) {
                columns.add(column(path, SqlTypes.JSONB, SqlColumnKind.IKEY, type));
            } else if (IClass.getClass(GeoJsonObject.class).isAssignableFrom(type)) {
                columns.add(column(path, SqlTypes.GEOMETRY, SqlColumnKind.GEOMETRY, type));
            } else if (isCollection(type) || isMap(type)) {
                SqlChildTable child = containerTable(path, type, generic, visiting);
                if (child == null) {
                    columns.add(column(path, SqlTypes.JSONB, SqlColumnKind.JSONB, type));
                } else {
                    columns.add(column(path, SqlTypes.BOOLEAN, SqlColumnKind.PRESENCE, type));
                    children.add(child);
                }
            } else if (isFlattenable(type, visiting)) {
                // Flattening loses whether the POJO existed: null and "present with every field null"
                // both become all-NULL columns. MongoDB keeps them apart ({} vs absent), so the fact is
                // stored beside the columns.
                columns.add(column(path, SqlTypes.BOOLEAN, SqlColumnKind.PRESENCE, type));
                Set<IClass<?>> deeper = new HashSet<>(visiting);
                deeper.add(type);
                for (IField sub : persistedFields(type)) {
                    List<String> subPath = new ArrayList<>(path);
                    subPath.add(sub.getName());
                    add(subPath, sub.getType(), sub.getGenericType(), deeper, columns, children);
                }
            } else {
                columns.add(column(path, SqlTypes.JSONB, SqlColumnKind.JSONB, type));
            }
        }

        /**
         * The child table of a collection or map field at {@code path}, or null when it has no relational
         * form (an untyped element, a map with non-scalar keys, an element type that recurses).
         */
        private SqlChildTable containerTable(List<String> path, IClass<?> type, Type generic,
                Set<IClass<?>> visiting) {
            List<String> absolute = concat(prefix, path);
            return build(SqlNaming.childTable(table, absolute), path, absolute, type, generic, visiting);
        }

        private SqlChildTable build(String name, List<String> path, List<String> absolute, IClass<?> type,
                Type generic, Set<IClass<?>> visiting) {
            boolean map = isMap(type);
            Type elementGeneric = type.isArray() ? null : typeArgument(generic, map ? 1 : 0);
            IClass<?> element = type.isArray() ? type.getComponentType() : rawOf(elementGeneric);
            IClass<?> key = map ? rawOf(typeArgument(generic, 0)) : null;
            if (element == null || (map && (key == null || !SqlTypes.isScalar(key)))) {
                return null;
            }
            String scalar = SqlTypes.sqlTypeOf(element).orElse(null);
            if (scalar != null) {
                SqlColumn value = new SqlColumn(SqlChildTable.VALUE, scalar, SqlColumnKind.SCALAR, List.of(), element);
                return new SqlChildTable(name, map ? SqlChildKind.MAP : SqlChildKind.SCALAR_COLLECTION, path, type,
                        element, key, List.of(value), null, absolute, ownerChild, List.of());
            }
            if (isFlattenable(element, visiting)) {
                return pojoTable(name, path, absolute, type, element, key, visiting);
            }
            if (isCollection(element) || isMap(element)) {
                SqlChildTable itself = elementTable(name, absolute, element, elementGeneric, visiting);
                return itself == null ? null
                        : new SqlChildTable(name, map ? SqlChildKind.MAP : SqlChildKind.NESTED_COLLECTION, path, type,
                                element, key, List.of(present(element)), null, absolute, ownerChild, List.of(itself));
            }
            return null;
        }

        /** A table of POJO elements (or map values): their fields, walked one level down. */
        private SqlChildTable pojoTable(String name, List<String> path, List<String> absolute, IClass<?> type,
                IClass<?> element, IClass<?> key, Set<IClass<?>> visiting) {
            List<SqlColumn> columns = new ArrayList<>();
            List<SqlChildTable> children = new ArrayList<>();
            // A null element and an element whose fields are all null would both be an all-NULL row.
            columns.add(present(element));
            Walker inner = new Walker(table, name, absolute);
            Set<IClass<?>> deeper = new HashSet<>(visiting);
            deeper.add(element);
            for (IField sub : persistedFields(element)) {
                inner.add(List.of(sub.getName()), sub.getType(), sub.getGenericType(), deeper, columns, children);
            }
            return new SqlChildTable(name, isMap(type) ? SqlChildKind.MAP : SqlChildKind.POJO_COLLECTION, path, type,
                    element, key, columns, null, absolute, ownerChild, children);
        }

        /**
         * The table of an element that IS a collection ({@code List<List<String>>},
         * {@code Map<String, List<Book>>}): a table of its own, under the table {@code name}, with an empty
         * field path — "the element itself". Null when that inner collection has no relational form.
         */
        private SqlChildTable elementTable(String name, List<String> absolute, IClass<?> element,
                Type elementGeneric, Set<IClass<?>> visiting) {
            return new Walker(table, name, absolute).build(name + SqlNaming.PATH_SEPARATOR + ELEMENT_SUFFIX,
                    List.of(), absolute, element, elementGeneric, visiting);
        }

        /** The {@code _present} column of an element: a null element and an all-null one differ. */
        private static SqlColumn present(IClass<?> element) {
            return new SqlColumn(SqlChildTable.PRESENT, SqlTypes.BOOLEAN, SqlColumnKind.PRESENCE, List.of(), element);
        }

        private static SqlColumn column(List<String> path, String sqlType, SqlColumnKind kind, IClass<?> type) {
            return new SqlColumn(SqlNaming.column(path), sqlType, kind, path, type);
        }
    }

    private static void collectTree(SqlChildTable child, List<SqlChildTable> out) {
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
        return !SqlTypes.isScalar(type) && !isCollection(type) && !isMap(type)
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
    private static void refuseCollisions(String table, List<SqlColumn> columns, List<SqlChildTable> children) {
        Set<String> seen = new HashSet<>();
        for (SqlColumn c : columns) {
            if (!seen.add(c.name())) {
                throw new IllegalArgumentException("Two fields of table '" + table + "' map to column '"
                        + c.name() + "' — rename one; a flattened path cannot share a name with a field.");
            }
        }
        Set<String> tables = new HashSet<>();
        tables.add(table);
        List<SqlChildTable> all = new ArrayList<>();
        children.forEach(c -> collectTree(c, all));
        for (SqlChildTable child : all) {
            if (!tables.add(child.name())) {
                throw new IllegalArgumentException("Two collections of table '" + table
                        + "' map to child table '" + child.name() + "'.");
            }
        }
    }
}
