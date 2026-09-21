package com.garganttua.dao.postgresql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Numeric filter values against numeric columns, compared EXACTLY — as MongoDB compares int32,
 * int64, double and decimal128 with one another.
 *
 * <p>
 * The writer narrows a number to its column ({@code 18.5} into an INTEGER is 18); a filter must not,
 * or {@code age = 18.5} would find the 18-year-olds and {@code age < 2^32 + 16} would wrap to 16.
 * Integer and NUMERIC columns are therefore compared in {@code numeric}, which holds every finite
 * value exactly: the value is bound as {@code ?::numeric} and PostgreSQL promotes the column.
 * </p>
 *
 * <p>
 * A DOUBLE PRECISION or REAL column cannot be promoted that way — PostgreSQL's float-to-numeric cast
 * rounds to 15 digits — so the comparison stays in {@code float8}, and a value no double represents
 * (a long beyond 2^53, a decimal such as 0.1) is replaced by the nearest double on the correct side
 * of the bound: {@code x > v} is {@code x >= up(v)}, where {@code up(v)} is the smallest double above
 * v. No stored double can equal such a value. {@code NaN} obeys MongoDB, which lets it equal NaN and
 * satisfy no bound; PostgreSQL would order it above every number, so a lower bound on a float column
 * excludes it explicitly.
 * </p>
 */
final class PgFilterNumbers {

    private static final String EQUALS = "=";
    private static final String DOUBLE_PLACEHOLDER = "?";
    private static final String NUMERIC_PLACEHOLDER = "?::numeric";

    private PgFilterNumbers() {
        // Static helpers
    }

    /**
     * The exact decimal value of a finite number.
     *
     * @param n the number
     * @return its exact value, or empty for NaN and the infinities
     */
    static Optional<BigDecimal> exact(Number n) {
        return switch (n) {
            case BigDecimal d -> Optional.of(d);
            case BigInteger b -> Optional.of(new BigDecimal(b));
            case Double d -> finite(d);
            case Float f -> finite(f.doubleValue());
            case Long l -> Optional.of(BigDecimal.valueOf(l));
            case Integer i -> Optional.of(BigDecimal.valueOf(i));
            case Short s -> Optional.of(BigDecimal.valueOf(s));
            case Byte b -> Optional.of(BigDecimal.valueOf(b));
            default -> parse(n);
        };
    }

    /**
     * The bind value of a number an equality or an {@code IN} list compares with a numeric column.
     *
     * @param floatColumn whether the column is DOUBLE PRECISION / REAL
     * @param n           the number
     * @return the placeholder and value, or empty when no value of the column can equal it
     */
    static Optional<PgSql> equality(boolean floatColumn, Number n) {
        if (isNonFinite(n)) {
            return floatColumn ? Optional.of(PgSql.of(DOUBLE_PLACEHOLDER, n.doubleValue())) : Optional.empty();
        }
        Optional<BigDecimal> exact = exact(n);
        if (exact.isEmpty()) {
            return Optional.empty();
        }
        if (!floatColumn) {
            return Optional.of(PgSql.of(NUMERIC_PLACEHOLDER, exact.get()));
        }
        double nearest = exact.get().doubleValue();
        return side(nearest, exact.get()) == 0 ? Optional.of(PgSql.of(DOUBLE_PLACEHOLDER, nearest)) : Optional.empty();
    }

    /**
     * The predicate {@code expr <op> n} on a numeric column.
     *
     * @param expr        the column expression
     * @param floatColumn whether the column is DOUBLE PRECISION / REAL
     * @param op          one of {@code = > >= < <=}
     * @param n           the number
     * @return the predicate, {@link PgSql#FALSE} when no value of the column satisfies it
     */
    static PgSql compare(PgSql expr, boolean floatColumn, String op, Number n) {
        if (EQUALS.equals(op)) {
            return equality(floatColumn, n).map(v -> expr.then(" = ").then(v)).orElse(PgSql.FALSE);
        }
        if (isNaN(n)) {
            return floatColumn && op.length() == 2
                    ? expr.then(" = ").then(PgSql.of(DOUBLE_PLACEHOLDER, Double.NaN))
                    : PgSql.FALSE;
        }
        PgSql predicate = bounded(expr, floatColumn, op, n);
        if (floatColumn && op.startsWith(">")) {
            return PgSql.all(List.of(predicate, expr.then(" <> 'NaN'::float8")));
        }
        return predicate;
    }

    /** {@code expr <op> n} for a non-NaN number and an ordering operator. */
    private static PgSql bounded(PgSql expr, boolean floatColumn, String op, Number n) {
        if (isNonFinite(n)) {
            return expr.then(" " + op + " ").then(PgSql.of(DOUBLE_PLACEHOLDER, n.doubleValue()));
        }
        BigDecimal exact = exact(n).orElseThrow();
        if (!floatColumn) {
            return expr.then(" " + op + " ").then(PgSql.of(NUMERIC_PLACEHOLDER, exact));
        }
        double nearest = exact.doubleValue();
        int side = side(nearest, exact);
        if (side == 0) {
            return expr.then(" " + op + " ").then(PgSql.of(DOUBLE_PLACEHOLDER, nearest));
        }
        if (op.startsWith(">")) {
            double up = side > 0 ? nearest : Math.nextUp(nearest);
            return expr.then(" >= ").then(PgSql.of(DOUBLE_PLACEHOLDER, up));
        }
        double down = side < 0 ? nearest : Math.nextDown(nearest);
        return expr.then(" <= ").then(PgSql.of(DOUBLE_PLACEHOLDER, down));
    }

    /** {@return the sign of {@code nearest - exact}: where the double landed relative to the value} */
    // new BigDecimal(double) on purpose: the comparison needs the double's EXACT binary value;
    // BigDecimal.valueOf would round it through its shortest decimal text and hide the gap.
    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static int side(double nearest, BigDecimal exact) {
        if (Double.isInfinite(nearest)) {
            return nearest > 0 ? 1 : -1;
        }
        return new BigDecimal(nearest).compareTo(exact);
    }

    private static boolean isNonFinite(Number n) {
        return (n instanceof Double || n instanceof Float) && !Double.isFinite(n.doubleValue());
    }

    private static boolean isNaN(Number n) {
        return (n instanceof Double || n instanceof Float) && Double.isNaN(n.doubleValue());
    }

    // new BigDecimal(double) on purpose: MongoDB compares numbers by exact value, so a double stands for
    // its exact binary value, not for the shortest decimal that rounds to it (BigDecimal.valueOf).
    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static Optional<BigDecimal> finite(double d) {
        return Double.isFinite(d) ? Optional.of(new BigDecimal(d)) : Optional.empty();
    }

    /** Other Number types (AtomicLong, …): their decimal text, or their double value as a last resort. */
    private static Optional<BigDecimal> parse(Number n) {
        try {
            return Optional.of(new BigDecimal(n.toString()));
        } catch (NumberFormatException e) {
            return finite(n.doubleValue());
        }
    }
}
