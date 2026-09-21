package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One comparison of a {@code $field} filter, lifted out of the {@code IFilter} tree.
 *
 * @param op     the operator ({@code $eq}, {@code $in}, …)
 * @param value  the compared value (single-valued operators)
 * @param values the listed values ({@code $in}, {@code $nin}); empty otherwise
 * @param field  the field name as the filter wrote it — for error messages only, never for SQL
 */
record PgCondition(String op, Object value, List<Object> values, String field) {

    PgCondition {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** {@return the same comparison under another operator — {@code $ne} is tested as NOT {@code $eq}} */
    PgCondition as(String otherOp) {
        return new PgCondition(otherOp, value, values, field);
    }

    /** {@return whether the listed values include null} */
    boolean listsNull() {
        return values.stream().anyMatch(Objects::isNull);
    }

    /** {@return the non-null listed values} */
    List<Object> listedValues() {
        return values.stream().filter(Objects::nonNull).toList();
    }

    /**
     * Whether this comparison is satisfied by a MISSING value — Mongo's {@code {f: null}},
     * {@code {f: {$in: [null, …]}}}, {@code {f: {$gte: null}}} all match documents without {@code f}.
     * On a collection, the path must really be missing — the collection absent, or the map key
     * absent — or an element null: an EMPTY array is present, and does not match.
     *
     * @return whether a missing value matches
     */
    boolean matchesMissing() {
        return switch (op) {
            case "$eq", "$gte", "$lte" -> value == null;
            case "$in" -> listsNull();
            default -> false;
        };
    }
}
