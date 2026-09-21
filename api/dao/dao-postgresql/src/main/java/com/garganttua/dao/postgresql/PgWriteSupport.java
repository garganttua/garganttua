package com.garganttua.dao.postgresql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * What the writer's parts share: reading a value down a field path, and binding a converted value.
 *
 * <p>
 * Field values are read from the RUNTIME class of each object on the path, not from the declared
 * type the model was built from: a subclass instance stored under its parent's domain still carries
 * the declared fields, and reading them through its own class hierarchy finds them wherever they
 * are declared.
 * </p>
 */
final class PgWriteSupport {

    private PgWriteSupport() {
        // Static helpers
    }

    /**
     * The value at the end of a field path.
     *
     * <p>
     * A null object anywhere on the way down makes the value null: an absent embedded POJO is
     * written as all its flattened columns NULL — which is also how the reader recognises it. An
     * EMPTY path answers the root itself: that is how the single {@code value} column of a scalar or
     * reference collection reads its element.
     * </p>
     *
     * @param root the object the path starts from, possibly null
     * @param path the field names, outermost first
     * @return the value, possibly null
     * @throws ApiException when an object on the path has no such field
     */
    static Object valueAt(Object root, List<String> path) throws ApiException {
        Object current = root;
        for (String name : path) {
            if (current == null) {
                return null;
            }
            current = read(current, name);
        }
        return current;
    }

    /**
     * Reads one field of an object, looking it up through the object's class hierarchy.
     *
     * @param owner the object, not null
     * @param name  the field name
     * @return the field value
     * @throws ApiException when no class of the hierarchy declares the field, or it cannot be read
     */
    static Object read(Object owner, String name) throws ApiException {
        IField field = field(owner, name).orElseThrow(() -> new ApiException("Type "
                + owner.getClass().getName() + " has no field '" + name + "' — the table model of this "
                + "domain expects one; was the DTO class changed without rebuilding the DAO?"));
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new ApiException("Cannot read field '" + name + "' of " + owner.getClass().getName()
                    + ": " + e.getMessage(), e);
        }
    }

    private static Optional<IField> field(Object owner, String name) {
        IClass<?> current = IClass.getClass(owner.getClass());
        while (current != null) {
            Optional<IField> found = current.findDeclaredField(name);
            if (found.isPresent()) {
                return found;
            }
            current = current.getSuperclass();
        }
        return Optional.empty();
    }

    /**
     * Binds a value already converted by {@link PgValues#toJdbc} to its placeholder.
     *
     * <p>
     * Geometry is bound as explicit text: its placeholder wraps it in {@code ST_GeomFromGeoJSON},
     * which PostGIS overloads for {@code text}, {@code json} and {@code jsonb}, and an untyped NULL
     * would leave PostgreSQL unable to choose between them. Every other value — NULL included —
     * goes through {@code setObject}, which pgjdbc binds with the type the column declares.
     * </p>
     *
     * @param statement the statement
     * @param index     the 1-based parameter index
     * @param column    the column the value belongs to
     * @param jdbcValue the converted value, possibly null
     * @throws SQLException when the driver refuses the value
     */
    static void bind(PreparedStatement statement, int index, PgColumn column, Object jdbcValue)
            throws SQLException {
        if (column.kind() == PgColumnKind.GEOMETRY) {
            statement.setObject(index, jdbcValue, Types.VARCHAR);
        } else {
            statement.setObject(index, jdbcValue);
        }
    }
}
