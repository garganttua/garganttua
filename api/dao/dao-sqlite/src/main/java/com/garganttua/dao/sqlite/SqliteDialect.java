package com.garganttua.dao.sqlite;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

import org.geojson.Point;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.GeoNear;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.ISqlDialect;
import com.garganttua.dao.sql.ISqlListDigests;
import com.garganttua.dao.sql.SqlBsonOrder;
import com.garganttua.dao.sql.SqlCells;
import com.garganttua.dao.sql.SqlCondition;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlGeoFilters;
import com.garganttua.dao.sql.SqlJson;
import com.garganttua.dao.sql.SqlOperand;
import com.garganttua.dao.sql.SqlQuery;
import com.garganttua.dao.sql.SqlValues;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * What SQLite spells differently: the SQLite side of {@link ISqlDialect}.
 *
 * <p>
 * <b>Storage.</b> SQLite has five storage classes; each logical type of the table model is stored so that SQLite's
 * own comparison orders it as BSON does: integers, booleans (0/1) and every temporal value as an INTEGER — a
 * timestamp in microseconds since the epoch in UTC, a date in days, a time in microseconds of the day — doubles as
 * REAL values in a column without affinity (a REAL column would store -0.0 as the integer 0), except NaN, which SQLite turns into NULL and is therefore stored as the text {@code 'NaN'}; text, UUIDs and
 * JSON as TEXT; a decimal as its exact TEXT, compared through {@code CAST(… AS NUMERIC)}; bytes as a BLOB; a geometry
 * as its GeoJSON text.
 * </p>
 *
 * <p>
 * <b>Structures.</b> SQLite cannot sort on a row value, so the BSON order of a document, an array or a map is built
 * as text by {@link SqliteOrderKey}, through the functions {@link SqliteFunctions} registers on each connection, and
 * a path through nested collections is followed by joins rather than {@code LATERAL}.
 * </p>
 *
 * <p>
 * <b>Transactions.</b> A write transaction starts {@code IMMEDIATE}: it takes the write lock up front, so two writers
 * queue on the busy timeout instead of one failing when it upgrades a read lock. A read stays {@code DEFERRED} — a
 * snapshot in WAL mode, and never blocking a writer.
 * </p>
 */
public final class SqliteDialect implements ISqlDialect {

    /** The one instance: the dialect holds no state. */
    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private static final String TEXT_TYPE = "TEXT";
    private static final String INTEGER = "INTEGER";
    private static final String NAN = "NaN";
    private static final String CASE_WHEN_JSON_TYPE = "(CASE WHEN json_type(";
    private static final String THEN = " THEN ";
    private static final String END = " END)";
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long NANOS_PER_MICRO = 1_000L;
    private static final int MAX_CELLS = 100;

    private SqliteDialect() {
    }

    @Override
    public String name() {
        return "SQLite";
    }

    @Override
    public String placeholder(SqlColumn column) {
        return "?";
    }

    @Override
    public Object json(String json) {
        return json;
    }

    @Override
    public String exactNumberPlaceholder() {
        return "CAST(? AS NUMERIC)";
    }

    @Override
    public String binaryCollation() {
        return "";
    }

    @Override
    public String textLiteral(String text, String logicalType) {
        String literal = "CAST(x'" + HexFormat.of().withUpperCase().formatHex(text.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)) + "' AS TEXT)";
        String type = ddlType(logicalType);
        return TEXT_TYPE.equals(type) ? literal : "CAST(" + literal + " AS " + type + ")";
    }

    @Override
    public String notNaN(String ref) {
        return "(" + ref + " <> '" + NAN + "')";
    }

    @Override
    public String byteLength(String ref) {
        return "length(" + ref + ")";
    }

    @Override
    public String nanExclusion() {
        return " <> '" + NAN + "'";
    }

    // ------------------------------------------------------------------ BSON order of structures

    @Override
    public String orderValue(String ref, SqlColumn column) {
        String function = SqlBsonOrder.floating(column) ? SqliteFunctions.FLOAT_ORDER_KEY : SqliteFunctions.ORDER_KEY;
        return function + "(" + scalarValue(ref, column) + ")";
    }

    @Override
    public String orderRow(List<String> parts) {
        StringJoiner row = new StringJoiner(" || ", "(", ")");
        parts.forEach(p -> row.add(SqliteFunctions.ORDER_PART + "(" + p + ")"));
        return row.toString();
    }

    @Override
    public String orderRows(String row, String from, String where, String orderBy) {
        return "(SELECT coalesce(group_concat('" + SqliteOrderKey.ELEMENT + "' || " + row + ", '' ORDER BY " + orderBy
                + "), '') || '" + SqliteOrderKey.END + "' FROM " + from + " WHERE " + where + ")";
    }

    @Override
    public String orderNoRows() {
        return "'" + SqliteOrderKey.END + "'";
    }

    @Override
    public String orderMapKey(String keyRef) {
        return SqliteFunctions.ORDER_KEY + "(CAST(" + keyRef + " AS TEXT))";
    }

    @Override
    public String valuesTable(List<List<String>> rows, List<String> columns, String alias) {
        StringJoiner union = new StringJoiner(" UNION ALL ", "(", ") AS " + alias);
        for (List<String> r : rows) {
            StringJoiner select = new StringJoiner(", ", "SELECT ", "");
            for (int i = 0; i < r.size(); i++) {
                select.add(r.get(i) + " AS " + columns.get(i));
            }
            union.add(select.toString());
        }
        return union.toString();
    }

    @Override
    public boolean lateral() {
        return false;
    }

    // ------------------------------------------------------------------ JSON values

    @Override
    public SqlFragment jsonText(String column, List<String> path) {
        return SqlFragment.of("(" + column + " ->> ?)", jsonPath(path));
    }

    @Override
    public SqlFragment jsonValue(String column, List<String> path) {
        return SqlFragment.of("(" + column + " -> ?)", jsonPath(path));
    }

    /** {@code $."a"."b"}: each key quoted, so that a key holding a dot or a bracket stays one key. */
    static String jsonPath(List<String> path) {
        StringBuilder out = new StringBuilder("$");
        for (String key : path) {
            out.append(".\"").append(key).append('"');
        }
        return out.toString();
    }

    @Override
    public SqlFragment jsonRead(SqlFragment json, SqlOperand.JsonType type) {
        String types = switch (type) {
            case STRING -> "('text')";
            case NUMBER -> "('integer', 'real')";
            case BOOLEAN -> "('true', 'false')";
        };
        return json.wrap(CASE_WHEN_JSON_TYPE, ") IN " + types + THEN).then(json.wrap("(", " ->> '$')")).then(END);
    }

    @Override
    public String jsonElementColumn() {
        return "v";
    }

    // ------------------------------------------------------------------ connections, values, storage

    @Override
    public void prepareConnection(Connection connection) throws SQLException {
        SqliteFunctions.ready(connection);
    }

    @Override
    public void beginTransaction(Connection connection, boolean write) throws SQLException {
        connection.unwrap(SQLiteConnection.class).getConnectionConfig().setTransactionMode(
                write ? SQLiteConfig.TransactionMode.IMMEDIATE : SQLiteConfig.TransactionMode.DEFERRED);
    }

    @Override
    public Object parameter(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime o -> micros(o.toInstant());
            case Instant i -> micros(i);
            case LocalDateTime l -> micros(l.toInstant(ZoneOffset.UTC));
            case LocalDate d -> d.toEpochDay();
            case LocalTime t -> t.toNanoOfDay() / NANOS_PER_MICRO;
            case UUID u -> u.toString();
            case BigDecimal d -> d.toString();
            case Boolean b -> b ? 1 : 0;
            case Double d -> d.isNaN() ? NAN : d;
            case Float f -> f.isNaN() ? NAN : (Object) f.doubleValue();
            case Short s -> s.intValue();
            case Byte b -> b.intValue();
            case Character c -> c.toString();
            default -> value;
        };
    }

    private static long micros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND), instant.getNano() / NANOS_PER_MICRO);
    }

    @Override
    public SqlCells cells(ResultSet rs) {
        return new SqliteCells(rs);
    }

    @Override
    public String scalarValue(String ref, SqlColumn column) {
        return "NUMERIC".equals(column.sqlType()) ? "CAST(" + ref + " AS NUMERIC)" : ref;
    }

    @Override
    public String unboundedLimit() {
        return " LIMIT -1";
    }

    @Override
    public String ddlType(String logicalType) {
        return switch (logicalType.toUpperCase(Locale.ROOT)) {
            case "INTEGER", "BIGINT", "SMALLINT", "BOOLEAN", "TIMESTAMPTZ", "TIMESTAMP", "DATE", "TIME" -> INTEGER;
            // No affinity: a REAL column stores an integral value as an integer, and -0.0 would come back 0.0.
            case "DOUBLE PRECISION", "REAL" -> "";
            case "BYTEA" -> "BLOB";
            default -> TEXT_TYPE;
        };
    }

    @Override
    public String childIdentity() {
        return "INTEGER PRIMARY KEY";
    }

    @Override
    public String childKey(String columns, boolean identity) {
        return (identity ? "UNIQUE (" : "PRIMARY KEY (") + columns + ")";
    }

    @Override
    public String geometryAsGeoJson(String ref) {
        return ref;
    }

    // ------------------------------------------------------------------ predicates

    @Override
    public SqlFragment jsonArrayAny(SqlFragment json, String alias, SqlFragment inElement) {
        return SqlFragment.of("EXISTS (SELECT 1 FROM (SELECT json_quote(value) AS v FROM json_each(").then(json)
                .then(") WHERE json_type(").then(json).then(") = 'array') AS " + alias + " WHERE ").then(inElement)
                .then(")");
    }

    @Override
    public SqlFragment regex(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return SqliteRegex.on(operand, condition, domain);
    }

    @Override
    public SqlFragment geoWithin(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return geo(SqliteFunctions.GEO_WITHIN, operand, condition, domain);
    }

    @Override
    public SqlFragment geoIntersects(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return geo(SqliteFunctions.GEO_INTERSECTS, operand, condition, domain);
    }

    private SqlFragment geo(String function, SqlOperand o, SqlCondition c, String domain) throws ApiException {
        SqlGeoFilters.requireGeometry(o, c, domain);
        return SqlFragment.of(function + "(").then(o.expr(false)).then(", ").then(shape(o, c.value(), c))
                .then(") = 1");
    }

    private SqlFragment shape(SqlOperand o, Object geometry, SqlCondition c) throws ApiException {
        SqlGeoFilters.requireShape(geometry, c);
        return SqlFragment.of("?", SqlValues.toJdbc(this, o.bindColumn(false), geometry));
    }

    @Override
    public SqlFragment geoNear(SqlOperand o, SqlCondition c, String domain) throws ApiException {
        GeoNear near = SqlGeoFilters.nearValue(c);
        SqlGeoFilters.requireGeometry(o, c, domain);
        Point point = (Point) near.geometry();
        double lng = point.getCoordinates().getLongitude();
        double lat = point.getCoordinates().getLatitude();
        List<SqlFragment> parts = new ArrayList<>();
        parts.add(o.expr(false).then(" IS NOT NULL"));
        if (near.maxDistance() != null) {
            parts.add(distance(o, lng, lat).then(SqlFragment.of(" <= ?", near.maxDistance())));
        }
        if (near.minDistance() != null && near.minDistance() > 0) {
            parts.add(distance(o, lng, lat).then(SqlFragment.of(" >= ?", near.minDistance())));
        }
        return SqlFragment.join(" AND ", parts).wrap("(", ")");
    }

    private static SqlFragment distance(SqlOperand o, double lng, double lat) {
        return SqlFragment.of(SqliteFunctions.GEO_DISTANCE + "(").then(o.expr(false))
                .then(SqlFragment.of(", ?, ?)", lng, lat));
    }

    @Override
    public Optional<String> geoNearOrder(SqlTable table, IFilter filter) throws ApiException {
        Optional<IFilter> clause = SqlGeoFilters.nearClause(filter);
        if (clause.isEmpty()) {
            return Optional.empty();
        }
        SqlColumn column = SqlGeoFilters.nearColumn(table, clause.get());
        Point point = SqlGeoFilters.nearPoint(clause.get());
        return Optional.of(SqliteFunctions.GEO_DISTANCE + "(" + SqlQuery.ALIAS + "." + SqlNaming.quote(column.name())
                + ", " + Double.toString(point.getCoordinates().getLongitude()) + ", "
                + Double.toString(point.getCoordinates().getLatitude()) + ") ASC");
    }

    @Override
    public SqlFragment text(SqlTable table, Object search) {
        return SqliteTextSearch.predicate(table, search);
    }

    @Override
    public SqlFragment textOnIndexed(SqlColumn column, Object search) {
        return SqliteTextSearch.predicateOnIndexed(column, search);
    }

    // ------------------------------------------------------------------ child aggregation

    @Override
    public int maxAggregateCells() {
        return MAX_CELLS;
    }

    @Override
    public String aggregateCell(String ref, String logicalType, SqlColumnKind kind) {
        if (kind == SqlColumnKind.GEOMETRY) {
            return ref;
        }
        return switch (logicalType.toUpperCase(Locale.ROOT)) {
            case "BYTEA" -> "CASE WHEN " + ref + " IS NULL THEN NULL ELSE hex(" + ref + ") END";
            // Java's own text of the double: exact, with the sign of -0.0 and the infinities Java parses.
            case "DOUBLE PRECISION", "REAL" -> SqliteFunctions.REAL_TEXT + "(" + ref + ")";
            default -> "CAST(" + ref + " AS TEXT)";
        };
    }

    @Override
    public String aggregate(String cells, String orderBy) {
        return "json_group_array(json_array(" + cells + ") ORDER BY " + orderBy + ")";
    }

    @Override
    public String nestedAggregate(String ref) {
        // A JSON value loses its JSON subtype through a CTE: json() gives it back, or json_array would quote it.
        return "json(" + ref + ")";
    }

    // ------------------------------------------------------------------ lists of values

    @Override
    public String anyOf(String column) {
        return column + " IN (SELECT value FROM json_each(?))";
    }

    @Override
    public Object anyOfValues(Connection connection, Collection<String> values) throws SQLException {
        try {
            return SqlJson.MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot list the keys to load: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ writes

    @Override
    public Optional<UniqueViolation> uniqueViolation(SQLException failure) {
        if (!(failure instanceof SQLiteException sqlite)
                || sqlite.getResultCode() != SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
            return Optional.empty();
        }
        return Optional.of(new UniqueViolation(Optional.empty(), failure.getMessage()));
    }

    @Override
    public Optional<String> boundJsonText(Object bound) {
        return bound instanceof String json ? Optional.of(json) : Optional.empty();
    }

    @Override
    public boolean storesNul() {
        return true;
    }

    @Override
    public boolean batchesGeneratedKeys() {
        return false;
    }

    @Override
    public Optional<ISqlListDigests> listDigests() {
        return Optional.empty();
    }

    @Override
    public Optional<String> snapshotStatement() {
        return Optional.empty();
    }

    /** {@return the SQL type the DDL declares for the column} — for the schema checks. */
    static String declared(SqlColumn column) {
        return INSTANCE.ddlType(column.sqlType());
    }
}
