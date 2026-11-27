package com.garganttua.dao.sql;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import com.garganttua.dao.sql.SqlOperand.JsonType;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * Filter values against typed operands, with MongoDB's type bracketing.
 *
 * <p>
 * MongoDB compares a value only with values of the same type class: numbers of any width together,
 * strings (enums and chars are stored as strings), booleans, dates. A value of another class never
 * matches and never fails: {@code {age: "18"}} finds nothing, and {@code {age: {$ne: "18"}}} finds
 * everything. The writer's conversion ({@link SqlValues}) is lenient on purpose — it parses
 * {@code "18"} into an INTEGER — so a filter value must NOT go through it blindly: that would widen
 * what matches, or throw on {@code "abc"} where MongoDB just answers nothing. This class decides,
 * per value, whether it can compare with the operand at all, and binds it when it can.
 * </p>
 *
 * <p>
 * <b>Numbers compare exactly</b>, as MongoDB compares int32, int64, double and decimal: nothing is
 * narrowed ({@code 18.5} against an INTEGER column stays 18.5, a long beyond the int range stays
 * itself). Against an integer or NUMERIC column the value is bound as {@code ?::numeric}, where
 * PostgreSQL promotes the column exactly. Against a DOUBLE/REAL column it is bound as a double — the
 * column cannot hold anything else — and a number no double can represent is moved to the nearest
 * double on the right side of the bound, which gives the exact answer. {@code NaN} follows MongoDB:
 * it equals NaN and satisfies no bound, where PostgreSQL would order it above every number.
 * </p>
 *
 * <p>
 * Dates go through {@link SqlValues} once their class is checked, so a filter value is truncated to
 * the milliseconds MongoDB keeps exactly like a written value is.
 * </p>
 */
public final class SqlFilterValues {

    /** The comparison classes of MongoDB (BSON type brackets) that a filter can meet. */
    enum Bracket {
        NUMBER, STRING, BOOLEAN, DATE, UUID, BINARY, OTHER
    }

    /**
     * A filter value made comparable with an operand.
     *
     * @param expr    the operand's expression, read as the value's type
     * @param value   the placeholder and its bound value
     * @param bracket the value's type class
     */
    public record Bound(SqlFragment expr, SqlFragment value, Bracket bracket) {
    }


    private SqlFilterValues() {
        // Static helpers
    }

    /** {@return the type class of a filter value} */
    static Bracket of(Object value) {
        return switch (value) {
            case Boolean _ -> Bracket.BOOLEAN;
            case Number _ -> Bracket.NUMBER;
            case CharSequence _, Character _, Enum<?> _ -> Bracket.STRING;
            case Instant _, Date _, OffsetDateTime _, ZonedDateTime _ -> Bracket.DATE;
            case LocalDateTime _, LocalDate _, LocalTime _ -> Bracket.DATE;
            case UUID _ -> Bracket.UUID;
            case byte[] _ -> Bracket.BINARY;
            default -> Bracket.OTHER;
        };
    }

    /** {@return the type class of what a column stores} */
    static Bracket of(SqlColumn column) {
        if (column.kind() == SqlColumnKind.ID || column.kind() == SqlColumnKind.COMPOSITION) {
            return Bracket.STRING;
        }
        if (column.kind() != SqlColumnKind.SCALAR) {
            return Bracket.OTHER;
        }
        return switch (column.sqlType()) {
            case SqlTypes.TEXT -> Bracket.STRING;
            case "INTEGER", "BIGINT", "SMALLINT", "DOUBLE PRECISION", "REAL", "NUMERIC" -> Bracket.NUMBER;
            case SqlTypes.BOOLEAN -> Bracket.BOOLEAN;
            case "TIMESTAMPTZ", "TIMESTAMP", "DATE", "TIME" -> Bracket.DATE;
            case "UUID" -> Bracket.UUID;
            case "BYTEA" -> Bracket.BINARY;
            default -> Bracket.OTHER;
        };
    }

    /**
     * The predicate {@code operand <op> value}.
     *
     * @param o     the operand
     * @param op    one of {@code = > >= < <=}
     * @param value the non-null filter value
     * @return the predicate — {@link SqlFragment#FALSE} when no stored value of the operand can satisfy it
     */
    public static SqlFragment compare(ISqlDialect dialect, SqlOperand o, String op, Object value) {
        if (!o.isJson() && of(o.column()) == Bracket.NUMBER && value instanceof Number n) {
            return SqlFilterNumbers.compare(o.expr(false), isFloat(o.column()), op, n);
        }
        Optional<Bound> bound = bound(dialect, o, value);
        if (bound.isEmpty()) {
            return SqlFragment.FALSE;
        }
        Bound b = bound.get();
        boolean ordering = !"=".equals(op);
        if (ordering && b.bracket() == Bracket.OTHER) {
            return SqlFragment.FALSE;
        }
        SqlFragment expr = ordering && b.bracket() == Bracket.STRING ? b.expr().then(dialect.binaryCollation()) : b.expr();
        return expr.then(" " + op + " ").then(b.value());
    }

    /**
     * A filter value bound for an equality or an {@code IN} list on an operand.
     *
     * @param o     the operand
     * @param value the non-null filter value
     * @return the bound value, or empty when no stored value of the operand can equal it
     */
    public static Optional<Bound> bound(ISqlDialect dialect, SqlOperand o, Object value) {
        if (o.isJson()) {
            return json(dialect, o, value);
        }
        Bracket bracket = of(value);
        if (bracket == Bracket.OTHER || bracket != of(o.column())) {
            return Optional.empty();
        }
        SqlFragment expr = o.expr(false);
        return switch (bracket) {
            case NUMBER -> SqlFilterNumbers.equality(isFloat(o.column()), (Number) value)
                    .map(v -> new Bound(expr, v, bracket));
            case STRING -> Optional.of(new Bound(expr, SqlFragment.of("?", text(value)), bracket));
            case DATE -> date(dialect, o.column(), value).map(v -> new Bound(expr, SqlFragment.of("?", v), bracket));
            default -> Optional.of(new Bound(expr, SqlFragment.of("?", value), bracket));
        };
    }

    /** A JSON value read as the filter value's type; anything else compared as JSON, for equality only. */
    private static Optional<Bound> json(ISqlDialect dialect, SqlOperand o, Object value) {
        Bracket bracket = of(value);
        return switch (bracket) {
            case NUMBER -> SqlFilterNumbers.exact((Number) value)
                    .map(d -> new Bound(o.jsonRead(JsonType.NUMBER), SqlFragment.of(dialect.exactNumberPlaceholder(), d), bracket));
            case STRING -> Optional.of(new Bound(o.jsonRead(JsonType.STRING), SqlFragment.of("?", text(value)), bracket));
            case BOOLEAN -> Optional.of(new Bound(o.jsonRead(JsonType.BOOLEAN), SqlFragment.of("?", value), bracket));
            default -> Optional.of(new Bound(o.json(), SqlFragment.of("?", SqlValues.toJdbc(dialect, o.column(), value)),
                    Bracket.OTHER));
        };
    }

    /** {@return the text a string-class value is stored as: an enum by its name} */
    static String text(Object value) {
        return value instanceof Enum<?> e ? e.name() : value.toString();
    }

    /** {@return whether the column stores binary floating point} */
    static boolean isFloat(SqlColumn column) {
        return "DOUBLE PRECISION".equals(column.sqlType()) || "REAL".equals(column.sqlType());
    }

    /**
     * A date value converted to the column's temporal type, then to its bind value through
     * {@link SqlValues} (millisecond truncation included). MongoDB stores every date class as one
     * UTC instant, so any of them compares with a timestamp column; a DATE or TIME column only
     * takes its own class.
     */
    private static Optional<Object> date(ISqlDialect dialect, SqlColumn column, Object value) {
        Object converted = switch (column.sqlType()) {
            case "TIMESTAMPTZ" -> instant(value);
            case "TIMESTAMP" -> LocalDateTime.ofInstant(instant(value), ZoneOffset.UTC);
            case "DATE" -> value instanceof LocalDate ? value : null;
            case "TIME" -> value instanceof LocalTime ? value : null;
            default -> null;
        };
        return converted == null ? Optional.empty() : Optional.ofNullable(SqlValues.toJdbc(dialect, column, converted));
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case Date d -> d.toInstant();
            case OffsetDateTime o -> o.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case LocalDateTime l -> l.toInstant(ZoneOffset.UTC);
            case LocalDate l -> l.atStartOfDay().toInstant(ZoneOffset.UTC);
            case LocalTime l -> LocalDate.EPOCH.atTime(l).toInstant(ZoneOffset.UTC);
            default -> throw new IllegalArgumentException("Not a date: " + value.getClass().getName());
        };
    }
}
