package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A translated read: the pieces the query builder produces and the reader executes.
 *
 * <p>
 * All SQL fragments refer to the main table under the alias {@link #ALIAS}. Every value reaches
 * the database as a bind parameter — never concatenated into the SQL — and every identifier was
 * resolved against the table model before being quoted in.
 * </p>
 *
 * @param where      a boolean SQL expression over {@code t} ({@code TRUE} when there is no filter)
 * @param params     the bind parameters of {@code where}, in order, already JDBC-ready
 * @param orderBy    the {@code ORDER BY …} clause including the keyword, or an empty string
 * @param limit      the page size, or null for no limit
 * @param offset     the rows to skip, or null — a {@code long}: {@code index * size} may exceed an int
 * @param projection the DTO field paths to load (dotted), or null to load everything
 */
@SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "the compact constructor stores unmodifiable copies "
        + "(params: Collections.unmodifiableList over a fresh ArrayList, as bind values may be null, which "
        + "List.copyOf refuses; projection: List.copyOf); SpotBugs cannot see through the wrapper")
public record PgQuery(String where, List<Object> params, String orderBy, Integer limit, Long offset,
        List<String> projection) {

    /** The alias of the main table in every generated statement. */
    public static final String ALIAS = "t";

    /**
     * Normalises and defensively copies the components. A null {@code projection} is kept null on
     * purpose: it is the documented "load everything" value, distinct from an empty selection.
     */
    @SuppressWarnings("PMD.NullAssignment")
    public PgQuery {
        Objects.requireNonNull(where, "where");
        params = params == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(params));
        orderBy = orderBy == null ? "" : orderBy;
        projection = projection == null ? null : List.copyOf(projection);
    }

    /** {@return a query matching every row, unordered, unpaged, unprojected} */
    public static PgQuery all() {
        return new PgQuery("TRUE", List.of(), "", null, null, null);
    }
}
