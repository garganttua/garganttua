package com.garganttua.dao.postgresql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * One single-valued thing a filter can compare: a column, or a path inside a {@code JSONB} column.
 *
 * <p>
 * The JSON case is why this exists. A column has one SQL type, and {@link PgValues#toJdbc} converts
 * the compared value to it. A path inside a document has no declared type: {@code node.weight} may
 * hold {@code 42} in one row and {@code "heavy"} in the next. The operand is therefore read as TEXT,
 * except when the filter compares it to a Number — then it is read as NUMERIC, and only where the
 * JSON value really is a number (a {@code CASE} on {@code jsonb_typeof}, never a bare cast, which
 * would abort the whole query on the first row holding a string). Comparing numbers as text would
 * make {@code 10 > 9} false.
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
 */
record PgOperand(String alias, PgColumn column, List<String> jsonPath) {

    PgOperand {
        jsonPath = Collections.unmodifiableList(new ArrayList<>(jsonPath));
    }

    /** {@return an operand on a whole column} */
    static PgOperand of(String alias, PgColumn column) {
        return new PgOperand(alias, column, List.of());
    }

    /** {@return whether this operand is a path inside a JSONB document} */
    boolean isJsonPath() {
        return !jsonPath.isEmpty();
    }

    /** {@return the qualified, quoted column: {@code t."name"}} */
    String qualifiedColumn() {
        return alias + "." + PgNaming.quote(column.name());
    }

    /**
     * The SQL expression of this operand.
     *
     * @param numeric for a JSON path, whether to read it as a number (null where it is not one)
     * @return the expression and the bound path segments
     */
    PgSql expr(boolean numeric) {
        if (!isJsonPath()) {
            return PgSql.of(qualifiedColumn());
        }
        String marks = String.join(", ", Collections.nCopies(jsonPath.size(), "?"));
        String call = "(" + qualifiedColumn() + ", " + marks + ")";
        if (!numeric) {
            return new PgSql("jsonb_extract_path_text" + call, new ArrayList<>(jsonPath));
        }
        String json = "jsonb_extract_path" + call;
        List<Object> params = new ArrayList<>(jsonPath);
        params.addAll(jsonPath);
        return new PgSql("(CASE WHEN jsonb_typeof(" + json + ") = 'number' THEN (" + json
                + ")::numeric END)", params);
    }

    /**
     * The column a compared value is converted for. For a JSON path, a synthetic TEXT or NUMERIC
     * column, so the conversion still goes through {@link PgValues} like every other bound value.
     *
     * @param numeric whether the operand is read as a number
     * @return the column to hand to {@link PgValues}
     */
    PgColumn bindColumn(boolean numeric) {
        if (!isJsonPath()) {
            return column;
        }
        return numeric
                ? new PgColumn(column.name(), "NUMERIC", PgColumnKind.SCALAR, column.fieldPath(),
                        IClass.getClass(BigDecimal.class))
                : new PgColumn(column.name(), PgTypes.TEXT, PgColumnKind.SCALAR, column.fieldPath(),
                        IClass.getClass(String.class));
    }

    /** {@return whether the operand holds text — what {@code $regex} can match} */
    boolean isText() {
        if (isJsonPath()) {
            return true;
        }
        return column.kind() != PgColumnKind.JSONB && column.kind() != PgColumnKind.IKEY
                && column.kind() != PgColumnKind.GEOMETRY && PgTypes.TEXT.equals(column.sqlType());
    }
}
