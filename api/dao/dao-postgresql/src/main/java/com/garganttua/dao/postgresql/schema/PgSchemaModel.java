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
            new Walker(table, true).add(path, field.getType(), field.getGenericType(), Set.of(),
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
        Map<String, String> composed = new LinkedHashMap<>();
        if (compositions != null) {
            for (IField field : persistedFields(dtoClass)) {
                String target = compositions.get(field.getName());
                if (target != null) {
                    composed.put(field.getName(), target);
                }
            }
        }
        return new PgTable(table, id, all, children, composed);
    }

    private static void addComposition(String table, IField field, List<String> path, String target,
            List<PgColumn> columns, List<PgChildTable> children) {
        if (isCollection(field.getType())) {
            PgColumn value = new PgColumn(PgChildTable.VALUE, PgTypes.TEXT, PgColumnKind.COMPOSITION,
                    List.of(), IClass.getClass(String.class));
            children.add(new PgChildTable(PgNaming.childTable(table, path),
                    PgChildKind.COMPOSITION_COLLECTION, path, field.getType(),
                    elementType(field.getGenericType(), 0), null, List.of(value), target));
        } else {
            columns.add(new PgColumn(PgNaming.column(path), PgTypes.TEXT, PgColumnKind.COMPOSITION, path,
                    field.getType()));
        }
    }

    /**
     * Classifies values and emits their columns and child tables.
     *
     * @param table         the owning table, for child-table names
     * @param allowChildren false inside a child table's element: collections there become JSONB
     */
    private record Walker(String table, boolean allowChildren) {

        void add(List<String> path, IClass<?> type, Type generic, Set<IClass<?>> visiting,
                List<PgColumn> columns, List<PgChildTable> children) {
            String scalar = PgTypes.sqlTypeOf(type).orElse(null);
            if (scalar != null) {
                columns.add(column(path, scalar, PgColumnKind.SCALAR, type));
            } else if (IClass.getClass(IKey.class).isAssignableFrom(type)) {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.IKEY, type));
            } else if (IClass.getClass(GeoJsonObject.class).isAssignableFrom(type)) {
                columns.add(column(path, PgTypes.GEOMETRY, PgColumnKind.GEOMETRY, type));
            } else if (isCollection(type)) {
                addCollection(path, type, generic, visiting, columns, children);
            } else if (isMap(type)) {
                addMap(path, type, generic, visiting, columns, children);
            } else if (isFlattenable(type, visiting)) {
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

        private void addCollection(List<String> path, IClass<?> type, Type generic,
                Set<IClass<?>> visiting, List<PgColumn> columns, List<PgChildTable> children) {
            IClass<?> element = type.isArray() ? type.getComponentType() : elementType(generic, 0);
            if (!allowChildren || element == null) {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
                return;
            }
            String scalar = PgTypes.sqlTypeOf(element).orElse(null);
            if (scalar != null) {
                PgColumn value = new PgColumn(PgChildTable.VALUE, scalar, PgColumnKind.SCALAR, List.of(), element);
                children.add(new PgChildTable(PgNaming.childTable(table, path), PgChildKind.SCALAR_COLLECTION,
                        path, type, element, null, List.of(value), null));
            } else if (isFlattenable(element, visiting)) {
                children.add(new PgChildTable(PgNaming.childTable(table, path), PgChildKind.POJO_COLLECTION,
                        path, type, element, null, elementColumns(element, visiting), null));
            } else {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
            }
        }

        private void addMap(List<String> path, IClass<?> type, Type generic, Set<IClass<?>> visiting,
                List<PgColumn> columns, List<PgChildTable> children) {
            IClass<?> key = elementType(generic, 0);
            IClass<?> value = elementType(generic, 1);
            if (!allowChildren || key == null || value == null || !PgTypes.isScalar(key)) {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
                return;
            }
            String scalar = PgTypes.sqlTypeOf(value).orElse(null);
            List<PgColumn> valueColumns;
            if (scalar != null) {
                valueColumns = List.of(new PgColumn(PgChildTable.VALUE, scalar, PgColumnKind.SCALAR, List.of(), value));
            } else if (isFlattenable(value, visiting)) {
                valueColumns = elementColumns(value, visiting);
            } else {
                columns.add(column(path, PgTypes.JSONB, PgColumnKind.JSONB, type));
                return;
            }
            children.add(new PgChildTable(PgNaming.childTable(table, path), PgChildKind.MAP, path, type,
                    value, key, valueColumns, null));
        }

        /** The flattened columns of a collection element, paths relative to the element. */
        private List<PgColumn> elementColumns(IClass<?> element, Set<IClass<?>> visiting) {
            List<PgColumn> out = new ArrayList<>();
            Walker inner = new Walker(table, false);
            Set<IClass<?>> deeper = new HashSet<>(visiting);
            deeper.add(element);
            for (IField sub : persistedFields(element)) {
                inner.add(List.of(sub.getName()), sub.getType(), sub.getGenericType(), deeper, out, new ArrayList<>());
            }
            return out;
        }

        private static PgColumn column(List<String> path, String sqlType, PgColumnKind kind, IClass<?> type) {
            return new PgColumn(PgNaming.column(path), sqlType, kind, path, type);
        }
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
        for (PgChildTable child : children) {
            if (!tables.add(child.name())) {
                throw new IllegalArgumentException("Two collections of table '" + table
                        + "' map to child table '" + child.name() + "'.");
            }
        }
    }
}
