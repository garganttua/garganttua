package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The collections a read now takes from the JSON aggregate of the main query come back EXACTLY as they
 * were written — at the edges where a text form would lie: before 1970, beyond year 9999, before year 1,
 * to the millisecond (what the DAO keeps, as BSON does), NaN and infinities, numbers wider than a double
 * (up to the 34 digits of Decimal128), bytes, UUIDs, map keys.
 */
@DisplayName("Collections read from the aggregate — exact at the edges")
class PgAggregateFidelityTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Moment {
        Instant instant;
        LocalDateTime local;
        LocalDate date;
        LocalTime time;
        OffsetDateTime offset;
    }

    public static class Holder {
        String uuid;
        List<Instant> instants;
        List<LocalDate> dates;
        List<LocalTime> times;
        List<LocalDateTime> locals;
        List<Double> doubles;
        List<BigDecimal> decimals;
        List<Long> longs;
        List<UUID> uuids;
        List<Boolean> flags;
        List<byte[]> blobs;
        Map<LocalDate, String> byDate;
        List<Moment> moments;
    }

    private PgDao holders;

    @BeforeEach
    void setUp() {
        holders = new PgDao(PgTestDatabase.freshDatabase(), "holders", new PgSchemaRegistry(), SchemaMode.CREATE,
                PgIndexMode.NONE);
        holders.registerDomain(TestDomains.definition(Holder.class, Map.of()));
    }

    private static final List<Instant> INSTANTS = List.of(Instant.EPOCH, Instant.parse("1969-12-31T23:59:59.501Z"),
            Instant.parse("2026-11-04T10:15:30.123Z"), Instant.parse("+12345-06-07T08:09:10.001Z"),
            Instant.parse("-0044-03-15T12:00:00Z"));

    private Holder read() throws ApiException {
        return (Holder) holders.find(Optional.empty(), Optional.empty(), Optional.empty()).get(0);
    }

    @Test
    @DisplayName("temporals: before 1970, beyond 9999, before year 1, to the millisecond")
    void temporals() throws ApiException {
        Holder holder = new Holder();
        holder.uuid = "h";
        holder.instants = INSTANTS;
        holder.dates = List.of(LocalDate.of(1, 1, 1), LocalDate.of(-500, 2, 28), LocalDate.of(1969, 12, 31),
                LocalDate.of(99999, 12, 31));
        holder.times = List.of(LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_000_000), LocalTime.of(12, 0, 0, 1_000_000));
        holder.locals = List.of(LocalDateTime.of(1900, 1, 1, 0, 0, 0, 7_000_000), LocalDateTime.of(10000, 1, 1, 0, 0));
        holder.moments = new ArrayList<>();
        Moment moment = new Moment();
        moment.instant = INSTANTS.get(1);
        moment.local = LocalDateTime.of(1955, 11, 5, 6, 15, 0, 500_000_000);
        moment.date = LocalDate.of(-1, 1, 1);
        moment.time = LocalTime.of(0, 0, 0, 1_000_000);
        moment.offset = OffsetDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.ofHours(2));
        holder.moments.add(moment);
        holder.byDate = new LinkedHashMap<>(Map.of(LocalDate.of(-10, 6, 1), "ancient", LocalDate.of(2026, 11, 4), "now"));
        holders.save(holder);

        Holder read = read();
        assertEquals(INSTANTS, read.instants);
        assertEquals(holder.dates, read.dates);
        assertEquals(holder.times, read.times);
        assertEquals(holder.locals, read.locals);
        assertEquals(holder.byDate, read.byDate);
        Moment back = read.moments.get(0);
        assertEquals(moment.instant, back.instant);
        assertEquals(moment.local, back.local);
        assertEquals(moment.date, back.date);
        assertEquals(moment.time, back.time);
        assertTrue(moment.offset.isEqual(back.offset), back.offset::toString);
    }

    @Test
    @DisplayName("numbers, UUIDs, booleans and bytes")
    void scalars() throws ApiException {
        Holder holder = new Holder();
        holder.uuid = "h";
        holder.doubles = List.of(0.1, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE, Double.MAX_VALUE);
        holder.decimals = List.of(new BigDecimal("1234567890123456789012.345678901234"), new BigDecimal("1.00"));
        holder.longs = List.of(Long.MIN_VALUE, Long.MAX_VALUE);
        holder.uuids = List.of(UUID.fromString("01a19c17-20fd-7f12-872b-8ddcf44799d0"));
        holder.flags = new ArrayList<>(java.util.Arrays.asList(true, null, false));
        holder.blobs = List.of(new byte[] { 0, -1, 127, -128 }, new byte[0]);
        holders.save(holder);

        Holder read = read();
        assertEquals(holder.doubles, read.doubles);
        assertEquals(holder.decimals, read.decimals);
        assertEquals(holder.longs, read.longs);
        assertEquals(holder.uuids, read.uuids);
        assertEquals(holder.flags, read.flags);
        assertArrayEquals(holder.blobs.get(0), read.blobs.get(0));
        assertArrayEquals(holder.blobs.get(1), read.blobs.get(1));
    }
}
