package com.garganttua.dao.postgresql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * The cells of one row, whatever carries them: a JDBC {@link ResultSet}, or a row of a child table read
 * from the JSON aggregate the main query returns ({@link PgChildAggregate}).
 *
 * <p>
 * It exposes exactly what {@link PgJdbcDecoder} needs, so one decoder serves both and a value reads back
 * the same either way. Indexes are 1-based, as in JDBC.
 * </p>
 */
interface PgCells {

    /** {@return the cell as text, or null} */
    String text(int index) throws SQLException;

    /** {@return the cell as a boolean, or null} */
    Boolean bool(int index) throws SQLException;

    /** {@return a temporal cell in the requested type, or null} */
    <T> T temporal(int index, Class<T> type) throws SQLException;

    /** {@return a binary cell, or null} */
    byte[] bytes(int index) throws SQLException;

    /** {@return the cell as JDBC's untyped {@code getObject} returns it, or null} */
    Object raw(int index) throws SQLException;

    /** {@return a BIGINT cell} — 0 for NULL, as {@code ResultSet.getLong} */
    long integer(int index) throws SQLException;

    /** The cells of the current row of a result set. */
    static PgCells of(ResultSet rs) {
        return new PgCells() {
            @Override
            public String text(int index) throws SQLException {
                return rs.getString(index);
            }

            @Override
            public Boolean bool(int index) throws SQLException {
                return rs.getObject(index, Boolean.class);
            }

            @Override
            public <T> T temporal(int index, Class<T> type) throws SQLException {
                return rs.getObject(index, type);
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
        };
    }

    /**
     * The cells of one child row read from the JSON aggregate: each cell is the TEXT the aggregate wrote
     * for it ({@link PgChildAggregate#text}), decoded back by its SQL type exactly as pgjdbc decodes the
     * column — temporals from their exact epoch form, binary from hexadecimal, numbers in the Java type
     * JDBC would hand back.
     *
     * @param values   the cells, as text (null for SQL NULL); items past the last cell are never read
     * @param sqlTypes the SQL type of each cell, same order, UPPER-CASE
     * @return the cells
     */
    static PgCells of(List<?> values, List<String> sqlTypes) {
        return new JsonRow(values, sqlTypes);
    }

    /** A row of the JSON aggregate. */
    final class JsonRow implements PgCells {

        private static final long MICROS = 1_000_000L;
        private static final long NANOS_PER_MICRO = 1_000L;

        private final List<?> values;
        private final List<String> sqlTypes;

        JsonRow(List<?> values, List<String> sqlTypes) {
            this.values = values;
            this.sqlTypes = sqlTypes;
        }

        private String cell(int index) {
            return (String) this.values.get(index - 1);
        }

        /** The SQL type of a cell — given upper-case, so the hot path never case-folds. */
        private String type(int index) {
            return this.sqlTypes.get(index - 1);
        }

        @Override
        public String text(int index) {
            return cell(index);
        }

        @Override
        public Boolean bool(int index) {
            String text = cell(index);
            return text == null ? null : Boolean.valueOf("true".equals(text) || "t".equals(text));
        }

        @Override
        public <T> T temporal(int index, Class<T> type) throws SQLException {
            String text = cell(index);
            if (text == null) {
                return null;
            }
            String sqlType = type(index);
            Object value = switch (sqlType) {
                case "DATE" -> LocalDate.ofEpochDay(Long.parseLong(text));
                case "TIME" -> LocalTime.ofNanoOfDay(Long.parseLong(text) * NANOS_PER_MICRO);
                case "TIMESTAMP" -> LocalDateTime.ofInstant(instant(text), ZoneOffset.UTC);
                default -> instant(text).atOffset(ZoneOffset.UTC);
            };
            if (!type.isInstance(value)) {
                throw new SQLException("a " + sqlType + " cell cannot be read as a " + type.getName());
            }
            return type.cast(value);
        }

        /** Microseconds since the epoch, as the aggregate writes a TIMESTAMP[TZ]. */
        private static Instant instant(String micros) {
            long value = Long.parseLong(micros);
            return Instant.ofEpochSecond(Math.floorDiv(value, MICROS), Math.floorMod(value, MICROS) * NANOS_PER_MICRO);
        }

        @Override
        public byte[] bytes(int index) {
            String text = cell(index);
            return text == null ? null : HexFormat.of().parseHex(text);
        }

        @Override
        public Object raw(int index) {
            String text = cell(index);
            if (text == null) {
                return null;
            }
            return switch (type(index)) {
                case "INTEGER", "SMALLINT" -> Integer.valueOf(text);
                case "BIGINT" -> Long.valueOf(text);
                case "NUMERIC" -> new BigDecimal(text);
                case "DOUBLE PRECISION" -> Double.valueOf(text);
                case "REAL" -> Float.valueOf(text);
                case PgTypes.BOOLEAN -> bool(index);
                case "UUID" -> UUID.fromString(text);
                default -> text;
            };
        }

        @Override
        public long integer(int index) {
            String text = cell(index);
            return text == null ? 0L : Long.parseLong(text);
        }
    }
}
