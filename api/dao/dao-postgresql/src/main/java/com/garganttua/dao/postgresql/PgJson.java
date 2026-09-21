package com.garganttua.dao.postgresql;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The JSON mappers every part of the DAO shares — so what one side writes, the other reads.
 *
 * <p>
 * {@link #MAPPER} (de)serialises {@code JSONB} columns. DTOs are field-based — private fields,
 * often no getters — so it reads and writes FIELDS and ignores accessors; with Jackson's default
 * getter-based detection, a getter-less DTO would serialise to {@code {}} and every JSONB column
 * would silently store nothing. It tolerates unknown properties, so a field removed from a DTO does
 * not make old rows unreadable.
 * </p>
 *
 * <p>
 * {@link #GEO} is a plain mapper for GeoJSON geometries: {@code org.geojson} types carry their own
 * Jackson annotations and getters, which the field-only configuration above would bypass.
 * </p>
 */
public final class PgJson {

    /** For {@code JSONB} columns: field-based, date-aware, lenient on unknown properties. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .visibility(PropertyAccessor.ALL, Visibility.NONE)
            .visibility(PropertyAccessor.FIELD, Visibility.ANY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** For GeoJSON geometries, which serialise through their own annotations. */
    public static final ObjectMapper GEO = new ObjectMapper();

    private PgJson() {
        // Static holder
    }
}
