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
 */
record PgOperand(String alias, PgColumn column, List<String> jsonPath) {

    /** The JSON types {@link #jsonRead} can read, with the SQL that reads each. */
    enum JsonType {
        /** A JSON string, read as text. */
        STRING("string", "(", " #>> '{}')"),
        /** A JSON number, read as {@code numeric}. */
        NUMBER("number", "(", ")::numeric"),
        /** A JSON boolean. */
        BOOLEAN("boolean", "(", ")::boolean");

        private final String typeName;
        private final String readPrefix;
        private final String readSuffix;

        JsonType(String typeName, String readPrefix, String readSuffix) {
            this.typeName = typeName;
            this.readPrefix = readPrefix;
            this.readSuffix = readSuffix;
        }
    }

    PgOperand {
        jsonPath = Collections.unmodifiableList(new ArrayList<>(jsonPath));
    }

    /** {@return an operand on a whole column} */
    static PgOperand of(String alias, PgColumn column) {
        return new PgOperand(alias, column, List.of());
    }

    /**
     * {@return an operand on one element of a JSON array, as {@code jsonb_array_elements} yields it:
     * {@code <alias>."v"}}
     */
    static PgOperand jsonElement(String alias) {
        return of(alias, new PgColumn("v", PgTypes.JSONB, PgColumnKind.JSONB, List.of(),
                IClass.getClass(Object.class)));
    }

    /** {@return whether this operand is a path inside a JSONB document} */
    boolean isJsonPath() {
        return !jsonPath.isEmpty();
    }

    /** {@return whether this operand holds a JSON value — a JSONB column or a path inside one} */
    boolean isJson() {
        return isJsonPath() || column.kind() == PgColumnKind.JSONB;
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
        if (numeric) {
            return jsonRead(JsonType.NUMBER);
        }
        String marks = String.join(", ", Collections.nCopies(jsonPath.size(), "?"));
        return new PgSql("jsonb_extract_path_text(" + qualifiedColumn() + ", " + marks + ")",
                new ArrayList<>(jsonPath));
    }

    /** {@return the raw {@code jsonb} value: the column itself, or {@code jsonb_extract_path} into it} */
    PgSql json() {
        if (!isJsonPath()) {
            return PgSql.of(qualifiedColumn());
        }
        String marks = String.join(", ", Collections.nCopies(jsonPath.size(), "?"));
        return new PgSql("jsonb_extract_path(" + qualifiedColumn() + ", " + marks + ")", new ArrayList<>(jsonPath));
    }

    /**
     * The JSON value read as one SQL type, NULL where the JSON value is of another type.
     *
     * @param type the JSON type to read
     * @return {@code (CASE WHEN jsonb_typeof(j) = 'type' THEN <read> END)}
     */
    PgSql jsonRead(JsonType type) {
        PgSql json = json();
        return json.wrap("(CASE WHEN jsonb_typeof(", ") = '" + type.typeName + "' THEN ")
                .then(json.wrap(type.readPrefix, type.readSuffix)).then(" END)");
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
