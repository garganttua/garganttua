package com.garganttua.dao.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;

import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * What one database does differently from the others, for the relational core shared by the SQL DAOs.
 *
 * <p>
 * The core owns the semantics — how a DTO becomes tables, and how MongoDB's filters, sorts, pages and projections
 * read over them. A dialect owns only how that is spelled on its database: how a value is bound, how a type is
 * declared, which function computes what. The core never names a database; a dialect never decides what a filter
 * means.
 * </p>
 *
 * <p>
 * The contract grows one concern at a time, each moved out of the PostgreSQL DAO into the core behind it.
 * </p>
 */
public interface ISqlDialect {

    /** {@return the database's name, for messages} — e.g. {@code PostgreSQL}. */
    String name();

    /**
     * {@return the SQL that binds one value of {@code column}} — {@code ?} for most columns; a geometry column wraps
     * it in the database's GeoJSON constructor.
     */
    String placeholder(SqlColumn column);

    /** {@return a JSON document, as the database's driver binds it} — e.g. a typed JSON object, or plain text. */
    Object json(String json) throws SQLException;

    // ------------------------------------------------------------------
    // Comparisons
    // ------------------------------------------------------------------

    /**
     * {@return the SQL that binds a number to compare exactly} with an integer or decimal column, whatever their
     * widths — {@code 18.5} against an {@code int} column stays {@code 18.5}.
     */
    String exactNumberPlaceholder();

    /**
     * {@return the collation suffix that orders and compares strings by binary code point}, as BSON does, whatever
     * the database's own collation — appended to a text expression, leading space included; empty if the database
     * already does.
     */
    String binaryCollation();

    /**
     * {@return a SQL literal of {@code text} that no content can escape}, converted to the column type
     * {@code logicalType} (a {@link com.garganttua.dao.sql.schema.SqlTypes} name) — used where a key must be
     * written into the statement rather than bound.
     */
    String textLiteral(String text, String logicalType);

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    /**
     * {@return a boolean sort key, false for NaN} — sorted before {@code ref}, it puts NaN below every number, as
     * BSON does.
     */
    String notNaN(String ref);

    /** {@return the length in bytes of a binary value} — binary values sort by length first, as BSON's do. */
    String byteLength(String ref);

    // ------------------------------------------------------------------
    // Predicates the core cannot spell: JSON arrays, regex, geography, text
    // ------------------------------------------------------------------

    /**
     * {@return a predicate true when some element of the JSON array {@code json} satisfies {@code inElement}} — false
     * when {@code json} is not an array. {@code inElement} reads the element through {@code alias}
     * ({@link SqlOperand#jsonElement(String)}).
     */
    SqlFragment jsonArrayAny(SqlFragment json, String alias, SqlFragment inElement);

    /** {@return the predicate of a {@code $regex} condition} — PCRE semantics, a construct the database cannot express refused by name. */
    SqlFragment regex(SqlOperand operand, SqlCondition condition, String domain) throws ApiException;

    /** {@return the predicate of {@code $geoWithin} / {@code $geoWithinSphere}}, on the sphere. */
    SqlFragment geoWithin(SqlOperand operand, SqlCondition condition, String domain) throws ApiException;

    /** {@return the predicate of {@code $geoIntersects}}, on the sphere. */
    SqlFragment geoIntersects(SqlOperand operand, SqlCondition condition, String domain) throws ApiException;

    /** {@return the predicate of {@code $near} / {@code $nearSphere}}, distances in metres. */
    SqlFragment geoNear(SqlOperand operand, SqlCondition condition, String domain) throws ApiException;

    /**
     * {@return the ORDER BY key a {@code $near} implies} — nearest first — when the filter holds one, refused where
     * MongoDB refuses it (under {@code $or} / {@code $nor}, twice, on a field held in a collection).
     */
    Optional<String> geoNearOrder(SqlTable table, IFilter filter) throws ApiException;

    /** {@return the predicate of a {@code $text} search over every string value of the entity} */
    SqlFragment text(SqlTable table, Object search);

    /** {@return the predicate of a {@code $text} search served by the declared text index of {@code column}} */
    SqlFragment textOnIndexed(SqlColumn column, Object search);

    // ------------------------------------------------------------------
    // Child aggregation: the collections of a page, inside the main query
    // ------------------------------------------------------------------

    /** {@return the most cells one aggregated row may hold} — beyond, the collection is read by its own query. */
    int maxAggregateCells();

    /**
     * {@return one cell of an aggregated row, as the text {@link SqlCells#of(java.util.List, java.util.List)} reads
     * back} — temporals as integer counts (microseconds since the epoch, days since 1970-01-01, microseconds since
     * midnight), binary as hexadecimal, a geometry as GeoJSON, booleans as {@code t}/{@code true}, anything else as its
     * text.
     */
    String aggregateCell(String ref, String logicalType, SqlColumnKind kind);

    /** {@return the aggregate of the rows of one group}: each row a JSON array of {@code cells}, ordered by {@code orderBy}. */
    String aggregate(String cells, String orderBy);

    // ------------------------------------------------------------------
    // Lists of values, bound as one parameter
    // ------------------------------------------------------------------

    /** {@return a predicate true when {@code column} equals one of the values bound to its single parameter} */
    String anyOf(String column);

    /** {@return the bind value of {@link #anyOf}'s parameter} — every value of {@code values}, as text. */
    Object anyOfValues(Connection connection, Collection<String> values) throws SQLException;

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** A unique index refused a write: which one, when the database says, and its own words. */
    record UniqueViolation(Optional<String> index, String detail) {
    }

    /** {@return the unique violation {@code failure} reports}, empty when it is another failure. */
    Optional<UniqueViolation> uniqueViolation(SQLException failure);

    /** {@return the JSON text of a value {@link #json(String)} bound}, empty for any other value. */
    Optional<String> boundJsonText(Object bound);

    /**
     * {@return whether the database stores the NUL character U+0000} in text and JSON — when it does not, a value
     * holding it is refused by name, before the database fails on it.
     */
    boolean storesNul();

    /**
     * {@return whether one batched INSERT returns the generated key of every row} — when not, rows whose keys are
     * needed are inserted one at a time.
     */
    boolean batchesGeneratedKeys();

    /** {@return the digest capability}, empty when the database does not provide it: every write rewrites every collection. */
    Optional<ISqlListDigests> listDigests();
}
