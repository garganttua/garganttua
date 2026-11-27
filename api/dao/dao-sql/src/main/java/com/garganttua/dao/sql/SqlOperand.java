package com.garganttua.dao.sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * One single-valued thing a filter can compare: a column, or a path inside a {@code JSONB} column.
 *
 * <p>
 * The JSON case is why this exists. A column has one SQL type; a value inside a document has none:
 * {@code node.weight} may hold {@code 42} in one row and {@code "heavy"} in the next. MongoDB compares
 * a value only with values of the same type class (a number with numbers, a string with strings), so a
 * JSON value is read through a {@code CASE} on {@code jsonb_typeof}: as {@code numeric} only where it
 * really is a number, as text only where it really is a string, as a boolean only where it is one —
 * never a bare cast, which would abort the whole query on the first row holding another type. Where
 * the JSON type differs the read is NULL, so the comparison does not match, as on MongoDB.
 * </p>
 *
 * <p>
 * The path segments come from the filter, so they are bound as parameters of
 * {@code jsonb_extract_path(_text)} — never spliced into a {@code '{a,b}'} literal.
 * </p>
 *
 * @param alias    the table alias the column belongs to ({@code t} or {@code c})
 * @param column   the column
 * @param jsonPath the path inside the column when it is {@code JSONB}; empty for the column itself
 * @param dialect  the database the column lives on — it spells the reads into JSON
 */
public record SqlOperand(String alias, SqlColumn column, List<String> jsonPath, ISqlDialect dialect) {

    /** The JSON types {@link #jsonRead} can read, with the SQL that reads each. */
    public enum JsonType {
        /** A JSON string, read as text. */
        STRING,
        /** A JSON number, read as an exact number. */
        NUMBER,
        /** A JSON boolean. */
        BOOLEAN
    }

    public SqlOperand {
        jsonPath = Collections.unmodifiableList(new ArrayList<>(jsonPath));
    }

    /** {@return an operand on a whole column} */
    public static SqlOperand of(ISqlDialect dialect, String alias, SqlColumn column) {
        return new SqlOperand(alias, column, List.of(), dialect);
    }

    /**
     * {@return an operand on one element of a JSON array, as {@code jsonb_array_elements} yields it:
     * {@code <alias>."v"}}
     */
    public static SqlOperand jsonElement(ISqlDialect dialect, String alias) {
        return of(dialect, alias, new SqlColumn(dialect.jsonElementColumn(), SqlTypes.JSONB, SqlColumnKind.JSONB, List.of(),
                IClass.getClass(Object.class)));
    }

    /** {@return whether this operand is a path inside a JSONB document} */
    public boolean isJsonPath() {
        return !jsonPath.isEmpty();
    }

    /** {@return whether this operand holds a JSON value — a JSONB column or a path inside one} */
    public boolean isJson() {
        return isJsonPath() || column.kind() == SqlColumnKind.JSONB;
    }

    /** {@return the qualified, quoted column: {@code t."name"}} */
    public String qualifiedColumn() {
        return alias + "." + SqlNaming.quote(column.name());
    }

    /**
     * The SQL expression of this operand.
     *
     * @param numeric for a JSON path, whether to read it as a number (null where it is not one)
     * @return the expression and the bound path segments
     */
    public SqlFragment expr(boolean numeric) {
        if (!isJsonPath()) {
            return SqlFragment.of(dialect.scalarValue(qualifiedColumn(), column));
        }
        if (numeric) {
            return jsonRead(JsonType.NUMBER);
        }
        return dialect.jsonText(qualifiedColumn(), jsonPath);
    }

    /** {@return the raw {@code jsonb} value: the column itself, or {@code jsonb_extract_path} into it} */
    public SqlFragment json() {
        if (!isJsonPath()) {
            return SqlFragment.of(qualifiedColumn());
        }
        return dialect.jsonValue(qualifiedColumn(), jsonPath);
    }

    /**
     * The JSON value read as one SQL type, NULL where the JSON value is of another type.
     *
     * @param type the JSON type to read
     * @return {@code (CASE WHEN jsonb_typeof(j) = 'type' THEN <read> END)}
     */
    public SqlFragment jsonRead(JsonType type) {
        return dialect.jsonRead(json(), type);
    }

    /**
     * The column a compared value is converted for. For a JSON path, a synthetic TEXT or NUMERIC
     * column, so the conversion still goes through {@link PgValues} like every other bound value.
     *
     * @param numeric whether the operand is read as a number
     * @return the column to hand to {@link PgValues}
     */
    public SqlColumn bindColumn(boolean numeric) {
        if (!isJsonPath()) {
            return column;
        }
        return numeric
                ? new SqlColumn(column.name(), "NUMERIC", SqlColumnKind.SCALAR, column.fieldPath(),
                        IClass.getClass(BigDecimal.class))
                : new SqlColumn(column.name(), SqlTypes.TEXT, SqlColumnKind.SCALAR, column.fieldPath(),
                        IClass.getClass(String.class));
    }

    /** {@return whether the operand holds text — what {@code $regex} can match} */
    boolean isText() {
        if (isJsonPath()) {
            return true;
        }
        return column.kind() != SqlColumnKind.JSONB && column.kind() != SqlColumnKind.IKEY
                && column.kind() != SqlColumnKind.GEOMETRY && SqlTypes.TEXT.equals(column.sqlType());
    }
}
