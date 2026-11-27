package com.garganttua.dao.postgresql;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.dao.sql.ISqlDialect;
import com.garganttua.dao.sql.ISqlListDigests;
import com.garganttua.dao.sql.SqlCondition;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlOperand;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * PostgreSQL, for the relational core shared by the SQL DAOs: JSON documents are {@code jsonb}, geometries are
 * PostGIS {@code geometry} in WGS 84 built from GeoJSON.
 */
public final class PgDialect implements ISqlDialect {

    /** The dialect has no state: one instance serves every PostgreSQL DAO. */
    public static final PgDialect INSTANCE = new PgDialect();

    private static final String JSONB = "jsonb";

    private PgDialect() {
    }

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public String placeholder(SqlColumn column) {
        return column.kind() == SqlColumnKind.GEOMETRY ? "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)" : "?";
    }

    @Override
    public String exactNumberPlaceholder() {
        return "?::numeric";
    }

    @Override
    public String binaryCollation() {
        return " COLLATE \"C\"";
    }

    @Override
    public String textLiteral(String text, String logicalType) {
        String hex = HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
        String decoded = "convert_from(decode('" + hex + "', 'hex'), 'UTF8')";
        return SqlTypes.TEXT.equals(logicalType) ? decoded : "CAST(" + decoded + " AS " + logicalType + ")";
    }

    @Override
    public String notNaN(String ref) {
        return "(" + ref + " <> 'NaN')";
    }

    @Override
    public String byteLength(String ref) {
        return "octet_length(" + ref + ")";
    }

    @Override
    public SqlFragment jsonArrayAny(SqlFragment json, String alias, SqlFragment inElement) {
        return json.wrap("EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(",
                ") = 'array' THEN ").then(json).then(" END) AS " + alias + "(v) WHERE ").then(inElement).then(")");
    }

    @Override
    public SqlFragment regex(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return PgRegex.on(operand, condition, domain);
    }

    @Override
    public SqlFragment geoWithin(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return new PgGeoPredicates(domain).within(operand, condition);
    }

    @Override
    public SqlFragment geoIntersects(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return new PgGeoPredicates(domain).intersects(operand, condition);
    }

    @Override
    public SqlFragment geoNear(SqlOperand operand, SqlCondition condition, String domain) throws ApiException {
        return new PgGeoPredicates(domain).near(operand, condition);
    }

    @Override
    public Optional<String> geoNearOrder(SqlTable table, IFilter filter) throws ApiException {
        return PgGeoPredicates.nearOrder(table, filter);
    }

    @Override
    public SqlFragment text(SqlTable table, Object search) {
        return PgTextSearch.predicate(table, search);
    }

    @Override
    public SqlFragment textOnIndexed(SqlColumn column, Object search) {
        return PgTextSearch.predicateOnIndexed(column, search);
    }

    /** {@code json_build_array} is a function: PostgreSQL caps its arguments at 100. */
    @Override
    public int maxAggregateCells() {
        return 100;
    }

    @Override
    public String aggregateCell(String ref, String logicalType, SqlColumnKind kind) {
        if (kind == SqlColumnKind.GEOMETRY) {
            return "ST_AsGeoJSON(" + ref + ")";
        }
        return switch (logicalType.toUpperCase(Locale.ROOT)) {
            // Exact in every version: the whole seconds (an integer, exact in a double), plus the micros.
            case "TIMESTAMPTZ" -> micros("(" + ref + " AT TIME ZONE 'UTC')");
            case "TIMESTAMP" -> micros(ref);
            case "DATE" -> "(" + ref + " - DATE '1970-01-01')::text";
            case "TIME" -> "round(extract(epoch FROM " + ref + ") * 1000000)::bigint::text";
            case "BYTEA" -> "encode(" + ref + ", 'hex')";
            default -> ref + "::text";
        };
    }

    private static String micros(String timestamp) {
        return "(extract(epoch FROM date_trunc('second', " + timestamp + "))::bigint * 1000000 + extract(microseconds FROM "
                + timestamp + ")::bigint % 1000000)::text";
    }

    @Override
    public String aggregate(String cells, String orderBy) {
        return "json_agg(json_build_array(" + cells + ") ORDER BY " + orderBy + ")";
    }

    @Override
    public String anyOf(String column) {
        return column + " = ANY(?)";
    }

    @Override
    public Object anyOfValues(Connection connection, Collection<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    @Override
    public Optional<UniqueViolation> uniqueViolation(SQLException failure) {
        if (!PgIndexManager.UNIQUE_VIOLATION.equals(failure.getSQLState())) {
            return Optional.empty();
        }
        ServerErrorMessage server = failure instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
        String constraint = server == null ? null : server.getConstraint();
        String detail = server == null ? failure.getMessage() : server.getDetail();
        return Optional.of(new UniqueViolation(Optional.ofNullable(constraint), detail));
    }

    @Override
    public Optional<String> boundJsonText(Object bound) {
        return bound instanceof PGobject json ? Optional.ofNullable(json.getValue()) : Optional.empty();
    }

    @Override
    public boolean storesNul() {
        return false;
    }

    @Override
    public boolean batchesGeneratedKeys() {
        return true;
    }

    /** The digest capability: triggers in PL/pgSQL, the DAO's flag a transaction-local setting. */
    private static final ISqlListDigests DIGESTS = new ISqlListDigests() {
        @Override
        public String flagExpression() {
            return PgListDigests.FLAG_EXPRESSION;
        }

        @Override
        public String lockForUpdate() {
            return " FOR UPDATE";
        }

        @Override
        public Map<SqlChildTable, String> columns(SqlTable table) {
            return PgListDigests.columns(table);
        }
    };

    @Override
    public Optional<ISqlListDigests> listDigests() {
        return Optional.of(DIGESTS);
    }

    @Override
    public Optional<String> snapshotStatement() {
        return Optional.of("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
    }

    @Override
    public Object json(String json) throws SQLException {
        PGobject object = new PGobject();
        object.setType(JSONB);
        object.setValue(json);
        return object;
    }
}
