package com.garganttua.dao.postgresql.schema;

import java.util.List;
import java.util.Objects;

import com.garganttua.core.reflection.IClass;

/**
 * One column: its SQL name and type, what it holds, and which DTO field it maps.
 *
 * @param name      the column name, UNQUOTED — quote with {@link PgNaming#quote(String)} in SQL
 * @param sqlType   the SQL type used in DDL (e.g. {@code TEXT}, {@code BIGINT}, {@code JSONB})
 * @param kind      what the column holds
 * @param fieldPath the DTO field names from the owning object down to the value: {@code [name]}
 *                  for a plain field, {@code [address, city]} for a flattened embedded POJO field.
 *                  For a child table's value columns the path is relative to the ELEMENT.
 * @param javaType  the Java type of the value at the end of the path — what the reader rebuilds
 */
public record PgColumn(String name, String sqlType, PgColumnKind kind, List<String> fieldPath,
        IClass<?> javaType) {

    public PgColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sqlType, "sqlType");
        Objects.requireNonNull(kind, "kind");
        fieldPath = List.copyOf(Objects.requireNonNull(fieldPath, "fieldPath"));
    }

    /** {@return the field path joined with dots — the form filters, sorts and projections use} */
    public String dottedPath() {
        return String.join(".", fieldPath);
    }
}
