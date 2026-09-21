package com.garganttua.dao.postgresql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.postgresql.util.PGobject;

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
     * written as all its flattened columns NULL, and its presence column NULL — the bit the reader
     * recognises it by (a present POJO with every field null has the same columns, and presence TRUE). An
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
     * <p>
     * A text holding U+0000 is refused here, with the field named: PostgreSQL {@code TEXT} and
     * {@code JSONB} cannot store the NUL character, and the driver's own error ("invalid byte
     * sequence for encoding UTF8: 0x00") names neither the field nor the cause. MongoDB stores it;
     * escaping it here would be faithful only for storage — every regex, text search, sort and
     * prefix comparison would then see the escape instead of the character — so this is a documented
     * limit of the PostgreSQL DAO rather than a silent rewrite of the data.
     * </p>
     *
     * @param jdbcValue the converted value, possibly null
     * @throws SQLException when the driver refuses the value
     * @throws ApiException when the value holds a NUL character
     */
    static void bind(PreparedStatement statement, int index, PgColumn column, Object jdbcValue)
            throws SQLException, ApiException {
        refuseNul(column, jdbcValue);
        if (column.kind() == PgColumnKind.GEOMETRY) {
            statement.setObject(index, jdbcValue, Types.VARCHAR);
        } else {
            statement.setObject(index, jdbcValue);
        }
    }

    private static void refuseNul(PgColumn column, Object jdbcValue) throws ApiException {
        boolean nul = jdbcValue instanceof String text ? text.indexOf('\0') >= 0
                : jdbcValue instanceof PGobject json && escapesNul(json.getValue());
        if (nul) {
            String field = column.dottedPath().isEmpty() ? "a collection element (column '" + column.name() + "')"
                    : "field '" + column.dottedPath() + "'";
            throw new ApiException("Cannot store " + field + ": it contains the NUL character U+0000, which "
                    + "PostgreSQL TEXT and JSONB cannot hold");
        }
    }

    /** Whether a JSON text escapes U+0000: a backslash-u-0000 escape whose backslash is not itself escaped. */
    static boolean escapesNul(String json) {
        if (json == null) {
            return false;
        }
        for (int at = json.indexOf("\\u0000"); at >= 0; at = json.indexOf("\\u0000", at + 1)) {
            int backslashes = 0;
            for (int i = at; i >= 0 && json.charAt(i) == '\\'; i--) {
                backslashes++;
            }
            if (isOdd(backslashes)) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether n is odd} {@code % 2 != 0}, unlike {@code % 2 == 1}, also holds for negatives. */
    private static boolean isOdd(int n) {
        return n % 2 != 0;
    }
}
