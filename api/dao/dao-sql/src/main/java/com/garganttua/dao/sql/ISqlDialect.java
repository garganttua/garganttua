package com.garganttua.dao.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
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

    /**
     * {@return the condition suffix that is false for NaN} — appended to a floating-point expression under a lower
     * bound: BSON puts NaN below every number, so {@code > x} never holds for it.
     */
    String nanExclusion();

    // ------------------------------------------------------------------
    // BSON order of whole structures — documents, arrays, maps
    // ------------------------------------------------------------------

    /**
     * {@return {@code ref} as a value that orders as BSON orders its column} — text by code point, binary by length
     * then bytes, NaN below every number — and that composes into {@link #orderRow}.
     */
    String orderValue(String ref, SqlColumn column);

    /** {@return a record of {@code parts}, ordered component by component, a NULL component above any value} */
    String orderRow(List<String> parts);

    /**
     * {@return the sequence of {@code row}s read from {@code from} where {@code where}, in {@code orderBy} order} —
     * ordered element by element, a sequence that is a prefix of another before it.
     */
    String orderRows(String row, String from, String where, String orderBy);

    /** {@return the empty sequence} — before every other. */
    String orderNoRows();

    /** {@return a map key, as text ordered by code point, composable into {@link #orderRow}} */
    String orderMapKey(String keyRef);

    /** {@return a table of literal {@code rows}, its columns named {@code columns}, under {@code alias}} */
    String valuesTable(List<List<String>> rows, List<String> columns, String alias);

    /**
     * {@return whether a subquery in FROM may read the columns of the tables before it} ({@code LATERAL}) — when
     * not, a path through nested collections is followed by joins.
     */
    boolean lateral();

    // ------------------------------------------------------------------
    // JSON values
    // ------------------------------------------------------------------

    /** {@return the value at {@code path} inside the JSON column {@code column}, as text} — strings unquoted. */
    SqlFragment jsonText(String column, List<String> path);

    /** {@return the value at {@code path} inside the JSON column {@code column}, as JSON} */
    SqlFragment jsonValue(String column, List<String> path);

    /** {@return the JSON value {@code json} read as {@code type}, NULL where it is of another JSON type} */
    SqlFragment jsonRead(SqlFragment json, SqlOperand.JsonType type);

    /** {@return the name of the element column of {@link #jsonArrayAny}'s array traversal} */
    String jsonElementColumn();

    // ------------------------------------------------------------------
    // Connections, values and storage
    // ------------------------------------------------------------------

    /**
     * Readies a connection just taken from the data source, before the DAO uses it — the functions and settings the
     * database needs per connection.
     *
     * @param connection the connection
     * @throws SQLException when it cannot be readied
     */
    void prepareConnection(Connection connection) throws SQLException;

    /**
     * Sets how the transaction about to open locks — called before auto-commit is turned off.
     *
     * @param connection the connection
     * @param write      whether the transaction writes
     * @throws SQLException when it cannot be set
     */
    void beginTransaction(Connection connection, boolean write) throws SQLException;

    /** {@return {@code value} — already what {@link SqlValues#toJdbc} gives — as this database's driver binds it} */
    Object parameter(Object value);

    /** {@return the cells of the current row of {@code rs}, read as this database stores them} */
    SqlCells cells(ResultSet rs);

    /** {@return the column {@code ref} as the value it compares and sorts by} */
    String scalarValue(String ref, SqlColumn column);

    /** {@return the clause that stands for "no limit" before an {@code OFFSET}, or "" when OFFSET goes alone} */
    String unboundedLimit();

    /** {@return the column type this database declares for the logical type {@code logicalType}} */
    String ddlType(String logicalType);

    /** {@return the declaration of a child table's generated row identifier, after its name} */
    String childIdentity();

    /**
     * {@return the constraint keying a child row by its scope and position}
     *
     * @param columns  the quoted scope and position columns, comma separated
     * @param identity whether the table has the generated identifier of {@link #childIdentity()}
     */
    String childKey(String columns, boolean identity);

    /** {@return a geometry column read back as its GeoJSON text} */
    String geometryAsGeoJson(String ref);

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

    // ------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------

    /**
     * {@return the statement that opens a read-only snapshot in a just-begun transaction} — for a read of several
     * statements, which must not see a write committed between two of them; empty when a transaction already is one.
     */
    Optional<String> snapshotStatement();
}
