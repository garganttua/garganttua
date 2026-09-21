package com.garganttua.dao.postgresql;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgColumn;

/**
 * JDBC values to Java values — the read-side inverse of {@link PgValues}.
 *
 * <p>
 * The target is always the FIELD's declared type, never the column's SQL type. The two agree when
 * the table was created from the current DTO, but a schema lives longer than a class: a field
 * widened from {@code int} to {@code long} still reads an {@code INTEGER} column, and pgjdbc hands
 * back an {@code Integer} that {@code field.set} would refuse on a {@code Long} field. Numbers are
 * therefore narrowed or widened to the declared type, and text is parsed when the declared type is
 * not text.
 * </p>
 *
 * <p>
 * Temporal columns are read with a TYPED {@code getObject}: the untyped call returns
 * {@code java.sql.Timestamp}, which interprets a {@code TIMESTAMP} in the JVM's zone and would
 * shift every value by the host's offset.
 * </p>
 */
final class PgJdbcDecoder {

    private PgJdbcDecoder() {
        // Static helpers
    }

    /**
     * Reads one cell into the declared Java type.
     *
     * @param rs      the result set, positioned on a row
     * @param index   the 1-based column index
     * @param column  the column model — its kind decides the decoding
     * @param type    the declared type of the target field (or element)
     * @param generic the declared generic type, for {@code JSONB} (a {@code List<Node>} must not
     *                come back as a list of maps)
     * @return the value, or null for SQL NULL
     * @throws ApiException when the stored value cannot become the declared type
     */
    static Object decode(ResultSet rs, int index, PgColumn column, IClass<?> type, Type generic)
            throws ApiException {
        try {
            return switch (column.kind()) {
                case ID, COMPOSITION -> fromText(rs.getString(index), type);
                case JSONB -> json(rs.getString(index), generic == null ? type.getType() : generic);
                case IKEY -> key(rs.getString(index));
                case GEOMETRY -> geometry(rs.getString(index), type);
                case SCALAR -> scalar(rs, index, type);
                case PRESENCE -> rs.getObject(index, Boolean.class);
            };
        } catch (SQLException | JsonProcessingException | RuntimeException e) {
            throw new ApiException("Cannot read column '" + column.name() + "' (" + column.sqlType()
                    + ") into a " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    private static Object json(String json, Type generic) throws JsonProcessingException {
        if (json == null) {
            return null;
        }
        return PgJson.MAPPER.readValue(json, PgJson.MAPPER.getTypeFactory().constructType(generic));
    }

    private static Object key(String json) throws JsonProcessingException, ApiException {
        return json == null ? null : PgKeyCodec.fromJson(PgJson.MAPPER.readTree(json));
    }

    private static Object geometry(String geoJson, IClass<?> type) throws JsonProcessingException {
        return geoJson == null ? null : PgJson.GEO.readValue(geoJson, (Class<?>) type.getType());
    }

    private static Object scalar(ResultSet rs, int index, IClass<?> type) throws SQLException {
        Object temporal = temporal(rs, index, type);
        if (temporal != null || isTemporal(type)) {
            return temporal;
        }
        if (type.represents(byte[].class)) {
            return rs.getBytes(index);
        }
        Object raw = rs.getObject(index);
        return raw == null ? null : convert(raw, type);
    }

    private static boolean isTemporal(IClass<?> type) {
        return type.represents(Instant.class) || type.represents(java.util.Date.class)
                || type.represents(OffsetDateTime.class) || type.represents(ZonedDateTime.class)
                || type.represents(LocalDateTime.class) || type.represents(LocalDate.class)
                || type.represents(LocalTime.class);
    }

    /** A temporal cell in the declared type, or null (also when the type is not temporal). */
    private static Object temporal(ResultSet rs, int index, IClass<?> type) throws SQLException {
        if (type.represents(LocalDateTime.class)) {
            return rs.getObject(index, LocalDateTime.class);
        }
        if (type.represents(LocalDate.class)) {
            return rs.getObject(index, LocalDate.class);
        }
        if (type.represents(LocalTime.class)) {
            return rs.getObject(index, LocalTime.class);
        }
        if (!isTemporal(type)) {
            return null;
        }
        OffsetDateTime stamp = rs.getObject(index, OffsetDateTime.class);
        if (stamp == null) {
            return null;
        }
        if (type.represents(Instant.class)) {
            return stamp.toInstant();
        }
        if (type.represents(java.util.Date.class)) {
            return java.util.Date.from(stamp.toInstant());
        }
        return type.represents(ZonedDateTime.class) ? stamp.toZonedDateTime() : stamp;
    }

    /** A text cell (ids, references) into the declared type — normally String, possibly UUID. */
    private static Object fromText(String text, IClass<?> type) {
        if (text == null || type.represents(String.class) || type.isInstance(text)) {
            return text;
        }
        return convert(text, type);
    }

    /**
     * A non-temporal JDBC value into the declared type.
     *
     * @throws IllegalArgumentException when the value has no faithful form in the declared type
     */
    static Object convert(Object raw, IClass<?> type) {
        if (type.represents(String.class)) {
            return raw.toString();
        }
        if (type.represents(Character.class) || type.represents(char.class)) {
            String text = raw.toString();
            return text.isEmpty() ? null : text.charAt(0);
        }
        if (type.isEnum()) {
            return enumConstant(raw.toString(), type);
        }
        Object number = number(raw, type);
        if (number != null) {
            return number;
        }
        if (type.represents(Boolean.class) || type.represents(boolean.class)) {
            return raw instanceof Boolean b ? b : Boolean.valueOf(raw.toString().trim());
        }
        if (type.represents(UUID.class)) {
            return raw instanceof UUID u ? u : UUID.fromString(raw.toString().trim());
        }
        if (type.isInstance(raw) || type.represents(Object.class)) {
            return raw;
        }
        throw new IllegalArgumentException("a stored " + raw.getClass().getName() + " has no conversion to "
                + type.getName());
    }

    private static Object enumConstant(String name, IClass<?> type) {
        for (Object constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("'" + name + "' is not a constant of enum " + type.getName()
                + " (renamed or removed since the row was written?)");
    }

    /** A number in the declared numeric type, or null when the type is not numeric. */
    @SuppressWarnings({ "PMD.CyclomaticComplexity", "PMD.NPathComplexity" })
    private static Object number(Object raw, IClass<?> type) {
        if (type.represents(BigDecimal.class)) {
            return raw instanceof BigDecimal d ? d : new BigDecimal(raw.toString().trim());
        }
        if (type.represents(BigInteger.class)) {
            return raw instanceof BigDecimal d ? d.toBigIntegerExact() : new BigInteger(raw.toString().trim());
        }
        boolean isNumber = raw instanceof Number;
        String text = isNumber ? null : raw.toString().trim();
        Number n = isNumber ? (Number) raw : null;
        if (type.represents(Integer.class) || type.represents(int.class)) {
            return isNumber ? (Object) n.intValue() : Integer.valueOf(text);
        }
        if (type.represents(Long.class) || type.represents(long.class)) {
            return isNumber ? (Object) n.longValue() : Long.valueOf(text);
        }
        if (type.represents(Short.class) || type.represents(short.class)) {
            return isNumber ? (Object) n.shortValue() : Short.valueOf(text);
        }
        if (type.represents(Byte.class) || type.represents(byte.class)) {
            return isNumber ? (Object) n.byteValue() : Byte.valueOf(text);
        }
        if (type.represents(Double.class) || type.represents(double.class)) {
            return isNumber ? (Object) n.doubleValue() : Double.valueOf(text);
        }
        if (type.represents(Float.class) || type.represents(float.class)) {
            return isNumber ? (Object) n.floatValue() : Float.valueOf(text);
        }
        return null;
    }
}
