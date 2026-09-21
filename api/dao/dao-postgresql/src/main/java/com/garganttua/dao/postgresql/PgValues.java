package com.garganttua.dao.postgresql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.postgresql.util.PGobject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.garganttua.api.commons.ApiException;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;

/**
 * Java values to JDBC bind values — ONE conversion, shared by the writer and the filter translator.
 *
 * <p>
 * It has to be one. If the writer stored an enum by its name and a filter bound it by its ordinal,
 * the row would be written correctly and never found again, with no error anywhere. Every value that
 * reaches a {@code PreparedStatement} — a column being written or a value being compared — goes
 * through {@link #toJdbc}, and every placeholder through {@link #placeholder}.
 * </p>
 *
 * <p>
 * Conversion is also lenient on INPUT, because filter values arrive from the transport as strings as
 * often as typed: {@code "42"} against an {@code INTEGER} column binds {@code 42}. Without that,
 * PostgreSQL would reject the comparison outright ({@code integer = character varying}). It does not
 * widen what matches: a value that does not parse to the column's type is an error, never a silent
 * mismatch.
 * </p>
 *
 * <p>
 * Two limits come from MongoDB, whose answers this DAO must reproduce. A BSON date holds
 * MILLISECONDS, so every instant and local date-time is truncated to the millisecond — on write AND
 * in filter values, since both come through here: a microsecond stored here and not there would make
 * the same equality match on one engine and miss on the other. A {@code Decimal128} holds 34
 * significant digits, so a {@code BigDecimal} or {@code BigInteger} beyond that is refused rather
 * than stored in a {@code NUMERIC} that MongoDB could never have held.
 * </p>
 */
public final class PgValues {

    private static final String JSONB = "jsonb";

    /** The significant digits of a BSON {@code Decimal128} — the widest decimal MongoDB stores. */
    static final int DECIMAL128_DIGITS = 34;

    private PgValues() {
        // Static helpers
    }

    /**
     * The SQL placeholder for one value of a column: {@code ?}, except for geometry, which is bound
     * as GeoJSON text and converted by PostGIS.
     *
     * @param column the column
     * @return the placeholder expression
     */
    public static String placeholder(PgColumn column) {
        return column.kind() == PgColumnKind.GEOMETRY ? "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)" : "?";
    }

    /**
     * Converts a Java value to what {@code PreparedStatement.setObject} expects for a column.
     *
     * @param column the column the value is written to or compared with
     * @param value  the value, possibly null
     * @return the bind value
     * @throws ApiException when the value cannot be represented in the column's type
     */
    public static Object toJdbc(PgColumn column, Object value) throws ApiException {
        if (value == null) {
            return null;
        }
        try {
            return switch (column.kind()) {
                case ID, COMPOSITION -> value.toString();
                case JSONB -> jsonb(PgJson.MAPPER.writeValueAsString(value));
                case IKEY -> jsonb(PgKeyCodec.toJson((IKey) value).toString());
                case GEOMETRY -> value instanceof String s ? s : PgJson.GEO.writeValueAsString(value);
                case SCALAR -> withinDecimal128(column, scalar(column.javaType(), value));
                // The value is the structure itself: present when non-null. NULL, not FALSE, so an
                // absent structure reads as absent in SQL too.
                case PRESENCE -> Boolean.TRUE;
            };
        } catch (ApiException e) {
            throw e;
        } catch (JsonProcessingException | SQLException e) {
            throw new ApiException("Cannot store a value in column '" + column.name() + "' ("
                    + column.sqlType() + "): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ApiException("Value '" + value + "' does not fit column '" + column.name() + "' ("
                    + column.sqlType() + "): " + e.getMessage(), e);
        }
    }

    /**
     * Refuses a decimal MongoDB could not hold. {@code BigInteger} is bound as a {@code BigDecimal},
     * so one check covers both.
     */
    private static Object withinDecimal128(PgColumn column, Object bound) throws ApiException {
        if (bound instanceof BigDecimal d && d.precision() > DECIMAL128_DIGITS) {
            String field = column.dottedPath().isEmpty() ? "A collection element"
                    : "Field '" + column.dottedPath() + "'";
            throw new ApiException(field + " (column '" + column.name() + "') holds " + d.precision()
                    + " significant digits: at most " + DECIMAL128_DIGITS + " can be stored (the Decimal128 limit of MongoDB, which this DAO keeps for parity)");
        }
        return bound;
    }

    private static PGobject jsonb(String json) throws SQLException {
        PGobject object = new PGobject();
        object.setType(JSONB);
        object.setValue(json);
        return object;
    }

    /** A scalar in the representation pgjdbc binds natively for the column's Java type. */
    @SuppressWarnings({ "PMD.CyclomaticComplexity", "PMD.NPathComplexity" })
    static Object scalar(IClass<?> type, Object value) {
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (type == null) {
            return value;
        }
        if (type.isEnum() || type.represents(String.class)) {
            return value.toString();
        }
        if (type.represents(Character.class) || type.represents(char.class)) {
            return value.toString();
        }
        if (type.represents(Integer.class) || type.represents(int.class)) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
        }
        if (type.represents(Long.class) || type.represents(long.class)) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
        }
        if (type.represents(Short.class) || type.represents(short.class)
                || type.represents(Byte.class) || type.represents(byte.class)) {
            return value instanceof Number n ? n.shortValue() : Short.parseShort(value.toString().trim());
        }
        if (type.represents(Double.class) || type.represents(double.class)) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
        }
        if (type.represents(Float.class) || type.represents(float.class)) {
            return value instanceof Number n ? n.floatValue() : Float.parseFloat(value.toString().trim());
        }
        if (type.represents(Boolean.class) || type.represents(boolean.class)) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString().trim());
        }
        if (type.represents(BigDecimal.class)) {
            return value instanceof BigDecimal d ? d : new BigDecimal(value.toString().trim());
        }
        if (type.represents(BigInteger.class)) {
            return new BigDecimal(value instanceof BigInteger b ? b : new BigInteger(value.toString().trim()));
        }
        if (type.represents(UUID.class)) {
            return value instanceof UUID u ? u : UUID.fromString(value.toString().trim());
        }
        return temporal(type, value);
    }

    /**
     * A temporal value in its column type, truncated to the millisecond a BSON date keeps.
     * Package-visible: the filter side binds its temporal values through it too.
     */
    static Object temporal(IClass<?> type, Object value) {
        if (type.represents(Instant.class) || type.represents(java.util.Date.class)
                || type.represents(OffsetDateTime.class) || type.represents(ZonedDateTime.class)) {
            return OffsetDateTime.ofInstant(instantOf(value).truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        }
        if (type.represents(LocalDateTime.class)) {
            LocalDateTime l = value instanceof LocalDateTime d ? d : LocalDateTime.parse(value.toString().trim());
            return l.truncatedTo(ChronoUnit.MILLIS);
        }
        if (type.represents(LocalDate.class)) {
            return value instanceof LocalDate l ? l : LocalDate.parse(value.toString().trim());
        }
        if (type.represents(LocalTime.class)) {
            LocalTime l = value instanceof LocalTime t ? t : LocalTime.parse(value.toString().trim());
            return l.truncatedTo(ChronoUnit.MILLIS);
        }
        return value;
    }

    private static Instant instantOf(Object value) {
        return switch (value) {
            case Instant i -> i;
            case java.util.Date d -> d.toInstant();
            case OffsetDateTime o -> o.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case Number n -> Instant.ofEpochMilli(n.longValue());
            default -> OffsetDateTime.parse(value.toString().trim()).toInstant();
        };
    }
}
