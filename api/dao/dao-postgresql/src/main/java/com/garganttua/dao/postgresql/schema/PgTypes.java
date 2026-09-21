package com.garganttua.dao.postgresql.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.garganttua.core.reflection.IClass;

/**
 * Which Java types are SCALARS — one value, one column — and the SQL type each one gets.
 *
 * <p>
 * Everything not listed here is structural: a POJO to flatten, a collection to put in a child table,
 * or, failing both, a {@code JSONB} column. Keeping the list explicit is deliberate: a type that
 * silently fell into a {@code TEXT} column through {@code toString()} would write fine and never
 * read back.
 * </p>
 */
public final class PgTypes {

    /** PostGIS geometry column type, SRID 4326 (WGS 84, GeoJSON's reference system). */
    public static final String GEOMETRY = "geometry(Geometry, 4326)";

    /** The column type of {@link PgColumnKind#JSONB} and {@link PgColumnKind#IKEY} columns. */
    public static final String JSONB = "JSONB";

    /** The column type of {@link PgColumnKind#PRESENCE} columns. */
    public static final String BOOLEAN = "BOOLEAN";

    /** The column type of ids, references, enums and strings. */
    public static final String TEXT = "TEXT";

    /** The column type of {@code byte[]} values. */
    public static final String BYTEA = "BYTEA";

    private static final String SMALLINT = "SMALLINT";
    private static final String TIMESTAMPTZ = "TIMESTAMPTZ";

    private static final Map<Class<?>, String> SCALARS = Map.ofEntries(
            Map.entry(String.class, TEXT),
            Map.entry(Character.class, TEXT), Map.entry(char.class, TEXT),
            Map.entry(Integer.class, "INTEGER"), Map.entry(int.class, "INTEGER"),
            Map.entry(Long.class, "BIGINT"), Map.entry(long.class, "BIGINT"),
            Map.entry(Short.class, SMALLINT), Map.entry(short.class, SMALLINT),
            Map.entry(Byte.class, SMALLINT), Map.entry(byte.class, SMALLINT),
            Map.entry(Double.class, "DOUBLE PRECISION"), Map.entry(double.class, "DOUBLE PRECISION"),
            Map.entry(Float.class, "REAL"), Map.entry(float.class, "REAL"),
            Map.entry(Boolean.class, "BOOLEAN"), Map.entry(boolean.class, "BOOLEAN"),
            Map.entry(BigDecimal.class, "NUMERIC"),
            Map.entry(BigInteger.class, "NUMERIC"),
            Map.entry(Instant.class, TIMESTAMPTZ),
            Map.entry(java.util.Date.class, TIMESTAMPTZ),
            Map.entry(OffsetDateTime.class, TIMESTAMPTZ),
            Map.entry(ZonedDateTime.class, TIMESTAMPTZ),
            Map.entry(LocalDateTime.class, "TIMESTAMP"),
            Map.entry(LocalDate.class, "DATE"),
            Map.entry(LocalTime.class, "TIME"),
            Map.entry(UUID.class, "UUID"),
            Map.entry(byte[].class, BYTEA));

    private PgTypes() {
        // Static helpers
    }

    /**
     * The SQL type of a scalar Java type.
     *
     * @param type the Java type
     * @return its SQL type, or empty when the type is not a scalar
     */
    public static Optional<String> sqlTypeOf(IClass<?> type) {
        if (type == null) {
            return Optional.empty();
        }
        if (type.isEnum()) {
            return Optional.of(TEXT);
        }
        for (Map.Entry<Class<?>, String> scalar : SCALARS.entrySet()) {
            if (type.represents(scalar.getKey())) {
                return Optional.of(scalar.getValue());
            }
        }
        return Optional.empty();
    }

    /** {@return whether a Java type maps to one column} */
    public static boolean isScalar(IClass<?> type) {
        return sqlTypeOf(type).isPresent();
    }
}
