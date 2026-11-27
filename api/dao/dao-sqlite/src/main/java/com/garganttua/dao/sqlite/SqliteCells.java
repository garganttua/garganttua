package com.garganttua.dao.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.garganttua.dao.sql.SqlCells;

/** The cells of a row, read back from SQLite's storage classes. */
final class SqliteCells implements SqlCells {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long NANOS_PER_MICRO = 1_000L;

    private final ResultSet rs;

    SqliteCells(ResultSet rs) {
        this.rs = rs;
    }

    @Override
    public String text(int index) throws SQLException {
        return rs.getString(index);
    }

    @Override
    public Boolean bool(int index) throws SQLException {
        Object value = rs.getObject(index);
        return switch (value) {
            case null -> null;
            case Number n -> n.longValue() != 0;
            default -> "1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString());
        };
    }

    @Override
    public <T> T temporal(int index, Class<T> type) throws SQLException {
        long value = rs.getLong(index);
        if (rs.wasNull()) {
            return null;
        }
        Object temporal;
        if (type == LocalDate.class) {
            temporal = LocalDate.ofEpochDay(value);
        } else if (type == LocalTime.class) {
            temporal = LocalTime.ofNanoOfDay(value * NANOS_PER_MICRO);
        } else {
            Instant instant = Instant.ofEpochSecond(Math.floorDiv(value, MICROS_PER_SECOND),
                    Math.floorMod(value, MICROS_PER_SECOND) * NANOS_PER_MICRO);
            temporal = type == LocalDateTime.class ? LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                    : instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
        if (!type.isInstance(temporal)) {
            throw new SQLException("a stored temporal cannot be read as a " + type.getName());
        }
        return type.cast(temporal);
    }

    @Override
    public byte[] bytes(int index) throws SQLException {
        return rs.getBytes(index);
    }

    @Override
    public Object raw(int index) throws SQLException {
        return rs.getObject(index);
    }

    @Override
    public long integer(int index) throws SQLException {
        return rs.getLong(index);
    }
}
