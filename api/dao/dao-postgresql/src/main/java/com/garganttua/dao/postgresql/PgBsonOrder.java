package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * SQL expressions whose PostgreSQL order IS MongoDB's BSON order — what a sort on a whole embedded
 * POJO, a map, or an array needs.
 *
 * <p>
 * MongoDB sorts a sub-document by comparing its fields one after the other, in STORED order: first
 * the BSON type of the field (numbers before strings before objects before arrays before binary
 * before booleans before dates), then the field name, then the value; a document that runs out of
 * fields first is the smaller one ({@code {}} before everything). An embedded array compares the
 * same way, element by element. A relational row has none of this: a POJO is a row of flattened
 * columns, an array is a set of child rows. So the document is rebuilt, inside the {@code ORDER BY},
 * as a PostgreSQL array of records — {@code (type rank, name rank, value)} per PRESENT field, in
 * declaration order (the order the MongoDB writer stores them in) — and PostgreSQL compares arrays
 * lexicographically, shorter prefix first, and records field by field: exactly the BSON rules.
 * </p>
 *
 * <p>
 * Values are re-encoded where PostgreSQL's native order differs from BSON's: text as its UTF-8 bytes
 * (binary code-point order whatever the collation — and a record carries no collation), a double as
 * {@code (not NaN, value)} (BSON puts NaN BELOW every number, PostgreSQL above), binary as
 * {@code (length, bytes)} (BSON compares lengths first). Every literal in the generated SQL is a
 * constant of the model (ranks, positions); identifiers all come from the {@link PgTable}.
 * </p>
 *
 * <p>
 * One instance serves one {@code ORDER BY}: it numbers the aliases of the subqueries it nests.
 * </p>
 */
final class PgBsonOrder {

    /** BSON canonical type ranks, in MongoDB's cross-type sort order. */
    static final int NULL = 1;
    static final int NUMBER = 2;
    static final int STRING = 3;
    static final int OBJECT = 4;
    static final int ARRAY = 5;
    static final int BINARY = 6;
    static final int BOOLEAN = 8;
    static final int DATE = 9;

    /**
     * A value encoded for ordering.
     *
     * @param value   the SQL expression — only meaningful when {@code present} holds
     * @param present a SQL boolean: whether the value exists (is not null, not absent)
     * @param rank    its BSON type rank
     */
    record Encoded(String value, String present, int rank) {

        /** {@return the value, SQL NULL when absent} */
        String orNull() {
            return "CASE WHEN " + present + " THEN " + value + " END";
        }
    }

    private final PgTable table;
    private int aliases;

    PgBsonOrder(PgTable table) {
        this.table = table;
    }

    /** {@return a fresh subquery alias} */
    String alias(String prefix) {
        aliases++;
        return prefix + aliases;
    }

    /** {@return the condition tying the rows of a child table (under {@code alias}) to the main row} */
    String owner(String alias) {
        return alias + "." + PgNaming.quote(PgChildTable.OWNER) + " = " + PgQuery.ALIAS + "."
                + PgNaming.quote(table.id().name());
    }

    /** {@return a reference to a column under an alias} */
    static String ref(String alias, PgColumn column) {
        return alias + "." + PgNaming.quote(column.name());
    }

    /**
     * The BSON type rank of a scalar column.
     *
     * @param column the column
     * @return its rank, or empty when the column has no BSON-comparable scalar form (JSONB, geometry)
     */
    static Optional<Integer> rank(PgColumn column) {
        if (column.kind() == PgColumnKind.COMPOSITION) {
            return Optional.of(OBJECT);
        }
        if (column.kind() != PgColumnKind.SCALAR && column.kind() != PgColumnKind.ID) {
            return Optional.empty();
        }
        return switch (column.sqlType()) {
        case "INTEGER", "BIGINT", "SMALLINT", "DOUBLE PRECISION", "REAL", "NUMERIC" -> Optional.of(NUMBER);
        case "TEXT" -> Optional.of(STRING);
        case "BYTEA", "UUID" -> Optional.of(BINARY);
        case "BOOLEAN" -> Optional.of(BOOLEAN);
        case "TIMESTAMPTZ", "TIMESTAMP", "DATE", "TIME" -> Optional.of(DATE);
        default -> Optional.empty();
        };
    }

    /** {@return whether a column holds IEEE floating point, where NaN must be moved below every number} */
    static boolean floating(PgColumn column) {
        return "DOUBLE PRECISION".equals(column.sqlType()) || "REAL".equals(column.sqlType());
    }

    /**
     * A scalar column as a single orderable value.
     *
     * @param alias  the alias of its table
     * @param column the column
     * @return the encoded value
     * @throws ApiException when the column has no BSON-comparable form
     */
    Encoded scalar(String alias, PgColumn column) throws ApiException {
        int rank = rank(column).orElseThrow(() -> unsortable(column));
        String ref = ref(alias, column);
        String value;
        if (floating(column)) {
            value = "ROW(" + ref + " <> 'NaN', " + ref + ")";
        } else if ("TEXT".equals(column.sqlType())) {
            value = "convert_to(" + ref + ", 'UTF8')";
        } else if ("BYTEA".equals(column.sqlType())) {
            value = "ROW(octet_length(" + ref + "), " + ref + ")";
        } else {
            value = ref;
        }
        return new Encoded(value, ref + " IS NOT NULL", rank);
    }

    /**
     * An embedded POJO as a BSON sub-document: an array of {@code (type, name, value)} records, one per
     * present field, in declaration order.
     *
     * @param alias       the alias of the table holding its flattened columns
     * @param columns     those columns (main table, or a child table's element columns)
     * @param prefix      the POJO's path among them
     * @param presenceRef the SQL reference to its presence column
     * @return the encoded document
     * @throws ApiException when a field has no BSON-comparable form
     */
    Encoded document(String alias, List<PgColumn> columns, List<String> prefix, String presenceRef)
            throws ApiException {
        List<Encoded> members = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (PgColumn column : columns) {
            List<String> path = column.fieldPath();
            if (path.size() == prefix.size() + 1 && path.subList(0, prefix.size()).equals(prefix)) {
                members.add(member(alias, columns, column));
                names.add(path.get(path.size() - 1));
            }
        }
        return new Encoded(fields(members, names), presenceRef + " IS NOT NULL", OBJECT);
    }

    private Encoded member(String alias, List<PgColumn> columns, PgColumn column) throws ApiException {
        if (column.kind() != PgColumnKind.PRESENCE) {
            return scalar(alias, column);
        }
        String presence = ref(alias, column);
        Optional<PgChildTable> child = PgQuery.ALIAS.equals(alias) ? table.child(column.dottedPath())
                : Optional.empty();
        if (child.isEmpty()) {
            return document(alias, columns, column.fieldPath(), presence);
        }
        return child.get().kind() == PgChildKind.MAP ? map(child.get(), presence) : array(child.get(), presence);
    }

    /** The {@code ARRAY(SELECT ROW(rank, name, slot…) FROM (VALUES …))} of a document's present fields. */
    private String fields(List<Encoded> members, List<String> names) {
        if (members.isEmpty()) {
            return "ARRAY(SELECT ROW(0, 0) WHERE FALSE)";
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(PgBsonOrder::compareCodePoints);
        String v = alias("v");
        StringJoiner row = new StringJoiner(", ", "ROW(" + v + ".r, " + v + ".n, ", ")");
        StringJoiner values = new StringJoiner(", ");
        for (int i = 0; i < members.size(); i++) {
            Encoded m = members.get(i);
            int position = i + 1;
            row.add("CASE WHEN " + v + ".p = " + position + " THEN " + m.value() + " END");
            values.add("(" + position + ", " + m.rank() + ", " + sorted.indexOf(names.get(i)) + ", " + m.present()
                    + ")");
        }
        return "ARRAY(SELECT " + row + " FROM (VALUES " + values + ") AS " + v + "(p, r, n, present) WHERE " + v
                + ".present ORDER BY " + v + ".p)";
    }

    /**
     * A collection embedded in a document: BSON compares it element by element, in stored order.
     *
     * @param child       its child table
     * @param presenceRef the SQL reference to its presence column
     * @return the encoded array
     * @throws ApiException when an element has no BSON-comparable form
     */
    Encoded array(PgChildTable child, String presenceRef) throws ApiException {
        String c = alias("c");
        Encoded element = element(child, c);
        String value = "ARRAY(SELECT ROW(CASE WHEN " + element.present() + " THEN " + element.rank() + " ELSE "
                + NULL + " END, " + element.orNull() + ") FROM " + PgNaming.quote(child.name()) + " " + c
                + " WHERE " + owner(c) + " ORDER BY " + c + "." + PgNaming.quote(PgChildTable.ORD) + ")";
        return new Encoded(value, presenceRef + " IS NOT NULL", ARRAY);
    }

    /**
     * A map as a BSON sub-document: one {@code (type, key, value)} record per entry. MongoDB keeps the
     * entries in the order the Java map iterated them at write time, which no table remembers; they are
     * taken in key order here — the same answer whenever that iteration order was the key order (a
     * sorted map, or a single entry).
     *
     * @param child       the map's child table
     * @param presenceRef the SQL reference to its presence column
     * @return the encoded document
     * @throws ApiException when a value has no BSON-comparable form
     */
    Encoded map(PgChildTable child, String presenceRef) throws ApiException {
        String c = alias("c");
        Encoded element = element(child, c);
        String key = "convert_to(CAST(" + c + "." + PgNaming.quote(PgChildTable.KEY) + " AS TEXT), 'UTF8')";
        String value = "ARRAY(SELECT ROW(CASE WHEN " + element.present() + " THEN " + element.rank() + " ELSE "
                + NULL + " END, " + key + ", " + element.orNull() + ") FROM " + PgNaming.quote(child.name()) + " "
                + c + " WHERE " + owner(c) + " ORDER BY " + key + ")";
        return new Encoded(value, presenceRef + " IS NOT NULL", OBJECT);
    }

    /**
     * One element of a child table, under the alias its rows are read with.
     *
     * @param child the child table
     * @param alias the alias of its rows
     * @return the encoded element
     * @throws ApiException when the element has no BSON-comparable form
     */
    Encoded element(PgChildTable child, String alias) throws ApiException {
        List<PgColumn> values = child.valueColumns();
        if (values.size() == 1 && values.get(0).fieldPath().isEmpty()) {
            return scalar(alias, values.get(0));
        }
        String present = alias + "." + PgNaming.quote(PgChildTable.PRESENT);
        return document(alias, values, List.of(), present);
    }

    /** Binary code-point order — BSON's order of field names. */
    static int compareCodePoints(String a, String b) {
        return Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
    }

    ApiException unsortable(PgColumn column) {
        return new ApiException("Cannot sort domain '" + table.name() + "' on a value containing '"
                + column.dottedPath() + "': it is stored as " + column.sqlType() + ", whose MongoDB (BSON) order"
                + " the PostgreSQL DAO does not reproduce. Sort on a scalar field, an embedded POJO or a"
                + " collection of scalars or POJOs.");
    }
}
