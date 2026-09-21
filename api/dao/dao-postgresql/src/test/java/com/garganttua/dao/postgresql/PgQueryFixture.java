package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.geojson.Point;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgDdl;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The domain, the rows and the helpers the query builder tests share.
 *
 * <p>
 * Rows are inserted with plain SQL — the reader and writer are other parts of the DAO — and a query is
 * checked by running its {@code WHERE} / {@code ORDER BY} / {@code LIMIT} against the real engine
 * and comparing the ids that come back.
 * </p>
 */
final class PgQueryFixture {

    private PgQueryFixture() {
    }

    public enum Status { ACTIVE, CLOSED }

    public static class Address {
        private String city;
        private Integer zip;
    }

    public static class Line {
        private String sku;
        private Integer qty;
    }

    /** Recursive: stored as JSONB. */
    public static class Node {
        private String label;
        private Object weight;
        private Node self;
    }

    public static class Item {
        @FieldMappingRule(sourceFieldAddress = "identifier")
        private String uuid;
        private String name;
        private Integer total;
        private Status status;
        private Instant createdAt;
        private Address address;
        private List<String> tags;
        private List<Line> lines;
        private Map<String, Integer> stock;
        private Node node;
    }

    /** Only for SQL-text tests: the embedded PostgreSQL has no PostGIS. */
    public static class Place {
        private String uuid;
        private String name;
        private Point location;
    }

    static final String INJECTED_NAME = "Robert'); DROP TABLE items; --";

    static PgTable items() {
        return PgSchemaModel.of("items", IClass.getClass(Item.class), "uuid", Map.of());
    }

    static PgTable places() {
        return PgSchemaModel.of("places", IClass.getClass(Place.class), "uuid", Map.of());
    }

    static PgQueryBuilder builder() {
        return new PgQueryBuilder(items(), IClass.getClass(Item.class));
    }

    /**
     * Five rows chosen so every operator has a row that must match and one that must not, and the
     * null cases MongoDB treats specially are present: c has no total, no status, no address, no
     * collection rows; d has no name and a line without qty; e has a hostile name and a single null tag.
     */
    static DataSource seed() throws SQLException {
        DataSource db = PgTestDatabase.freshDatabase();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String ddl : PgDdl.create(items())) {
                s.execute(ddl);
            }
            row(c, "a", "Alice", 10, "ACTIVE", day(1), "Paris", 75000, "{\"label\":\"root\",\"weight\":10}");
            row(c, "b", "Bob", 42, "CLOSED", day(2), "Lyon", null, "{\"label\":\"leaf\",\"weight\":9}");
            row(c, "c", "Carol", null, null, null, null, null, "{\"label\":\"x\",\"weight\":\"heavy\"}");
            row(c, "d", null, 5, "ACTIVE", day(4), "Paris", null, null);
            row(c, "e", INJECTED_NAME, 7, "ACTIVE", day(5), "Nice", 6000, null);
            child(c, "INSERT INTO \"items__tags\" VALUES (?, ?, ?)", "a", 0, "red", "a", 1, "blue", "b", 0, "blue",
                    "d", 0, "green", "e", 0, null);
            child(c, "INSERT INTO \"items__lines\" (\"_owner\", \"_ord\", \"sku\", \"qty\", \"_present\")"
                    + " VALUES (?, ?, ?, ?, TRUE)", "a", 0, "A1", 2, "a", 1, "B2", 5,
                    "b", 0, "A1", 1, "d", 0, "C3", null);
            child(c, "INSERT INTO \"items__stock\" VALUES (?, ?, ?)", "a", "apple", 3, "b", "apple", 7,
                    "b", "pear", 1);
        }
        return db;
    }

    private static OffsetDateTime day(int day) {
        return OffsetDateTime.of(2026, 1, day, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static void row(Connection c, String id, String name, Integer total, String status,
            OffsetDateTime createdAt, String city, Integer zip, String node) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO \"items\" (\"uuid\", \"name\", \"total\", "
                + "\"status\", \"createdAt\", \"address__city\", \"address__zip\", \"node\") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))")) {
            Object[] values = { id, name, total, status, createdAt, city, zip, node };
            for (int i = 0; i < values.length; i++) {
                p.setObject(i + 1, values[i]);
            }
            p.executeUpdate();
        }
    }

    /** Inserts rows of a child table; values is a flat list of consecutive rows. */
    private static void child(Connection c, String sql, Object... values) throws SQLException {
        int width = (int) sql.chars().filter(ch -> ch == '?').count();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int row = 0; row < values.length / width; row++) {
                for (int i = 0; i < width; i++) {
                    p.setObject(i + 1, values[row * width + i]);
                }
                p.executeUpdate();
            }
        }
    }

    /** Runs a query the way the reader will, and returns the matching ids in result order. */
    static List<String> ids(DataSource db, PgQuery q) throws SQLException {
        String sql = "SELECT t.\"uuid\" FROM \"items\" t WHERE " + q.where() + " " + q.orderBy()
                + (q.limit() == null ? "" : " LIMIT " + q.limit())
                + (q.offset() == null ? "" : " OFFSET " + q.offset());
        List<String> ids = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < q.params().size(); i++) {
                p.setObject(i + 1, q.params().get(i));
            }
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
        }
        return ids;
    }

    // ---- filters, built the way the api builds them: $field nodes with one comparison child ----

    static IFilter field(String field, String op, Object value) {
        return new TestFilter("$field", field, List.of(new TestFilter(op, value, List.of())));
    }

    static IFilter listed(String field, String op, Object... values) {
        List<IFilter> items = Arrays.stream(values).map(v -> (IFilter) new TestFilter("$value", v, List.of())).toList();
        return new TestFilter("$field", field, List.of(new TestFilter(op, null, items)));
    }

    static IFilter logical(String op, IFilter... subs) {
        return new TestFilter(op, null, List.of(subs));
    }

    /** A plain IFilter: the api's own implementation lives in api-core, which this module does not see. */
    static final class TestFilter implements IFilter {
        private final String name;
        private Object value;
        private List<IFilter> filters;

        TestFilter(String name, Object value, List<IFilter> filters) {
            this.name = name;
            this.value = value;
            this.filters = new ArrayList<>(filters);
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public void setValue(Object value) {
            this.value = value;
        }

        @Override
        public IFilter clone() {
            return new TestFilter(name, value, filters);
        }

        @Override
        public List<IFilter> getFilters() {
            return filters;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setFilters(List<IFilter> valuesFilters) {
            this.filters = new ArrayList<>(valuesFilters);
        }

        @Override
        public void removeSubFilter(IFilter filter) {
            filters.remove(filter);
        }

        @Override
        public void replaceSubFilter(IFilter literal, IFilter mappedFilter) {
            filters.replaceAll(f -> f == literal ? mappedFilter : f);
        }
    }
}
