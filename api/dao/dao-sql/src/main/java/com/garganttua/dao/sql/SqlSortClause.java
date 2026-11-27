package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.sql.schema.SqlChildKind;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * Builds the {@code ORDER BY} of a read so that PostgreSQL returns rows in the order MongoDB would.
 *
 * <p>
 * A plain {@code ORDER BY column} differs from MongoDB in more places than one would guess, and each
 * difference is handled here rather than left to the caller:
 * </p>
 * <ul>
 * <li><b>missing values</b> come FIRST ascending and last descending ({@code NULLS FIRST/LAST});</li>
 * <li><b>text</b> is ordered by binary code points ({@code COLLATE "C"}), whatever the database's
 * linguistic default collation;</li>
 * <li><b>NaN</b> is below every number in BSON, above in PostgreSQL — a leading {@code <> 'NaN'}
 * key moves it;</li>
 * <li>an <b>array</b> sorts ascending by its smallest element and descending by its largest, and an
 * EMPTY array sorts before a missing one; its elements live in a child table, hence a correlated
 * subquery;</li>
 * <li>a whole <b>embedded POJO</b> or <b>map</b> is compared as a BSON sub-document
 * ({@link SqlBsonOrder});</li>
 * <li>{@code _id} is MongoDB's name of the uuid;</li>
 * <li>an <b>unknown</b> field is missing on every document for MongoDB — all rows tie — so it adds
 * no key at all (a schema-less store cannot refuse it, so neither does this one);</li>
 * <li>when a <b>page</b> is requested, the uuid is appended ascending as a last key: {@code OFFSET}
 * over an order with ties is not deterministic, while the uuid is unique, so consecutive pages stay
 * disjoint and complementary. The MongoDB DAO appends {@code _id} for the same reason.</li>
 * </ul>
 */
public final class SqlSortClause {

    private static final Logger LOG = Logger.getLogger(SqlSortClause.class);

    /** MongoDB's key field, which the MongoDB DAO fills with the uuid. */
    static final String MONGO_ID = "_id";


    private final SqlTable table;
    private final SqlPathLocator locator;

    public SqlSortClause(SqlTable table) {
        this.table = table;
        // Keys inlined: an ORDER BY carries no bind values. "h" keeps its aliases apart from SqlBsonOrder's.
        this.locator = new SqlPathLocator(table, "h", true);
    }

    /**
     * The {@code ORDER BY} clause.
     *
     * @param sort  the sort, or null
     * @param paged whether a page is requested (the uuid then breaks ties)
     * @return the clause including the keyword, or an empty string
     * @throws ApiException when the sort has no field name, or names a value whose MongoDB order is not
     *                      reproduced (a JSONB or geometry value, a collection nested in a collection)
     */
    public String orderBy(ISort sort, boolean paged) throws ApiException {
        return orderBy(sort, paged, java.util.Optional.empty());
    }

    /**
     * The {@code ORDER BY} of a read, with a key that comes before the requested sort — the distance
     * a {@code $near} orders by.
     *
     * @param sort    the requested sort, possibly null
     * @param paged   whether a page is requested (the uuid then ends the keys, for stable pages)
     * @param leading a key to sort by first, possibly empty
     * @return the clause, or an empty string
     * @throws ApiException when the sort names an unsortable field
     */
    public String orderBy(ISort sort, boolean paged, java.util.Optional<String> leading) throws ApiException {
        List<String> keys = new ArrayList<>();
        leading.ifPresent(keys::add);
        boolean byId = false;
        if (sort != null) {
            String field = fieldName(sort);
            // Same test as MongoDao: anything but asc sorts descending.
            boolean ascending = sort.getDirection() == SortDirection.asc;
            byId = MONGO_ID.equals(field) || table.id().dottedPath().equals(field);
            keys.addAll(byId ? List.of(SqlBsonOrder.ref(SqlQuery.ALIAS, table.id()) + table.dialect().binaryCollation()
                    + direction(ascending)) : keys(field, ascending));
        }
        if (paged && !byId) {
            keys.add(SqlBsonOrder.ref(SqlQuery.ALIAS, table.id()) + table.dialect().binaryCollation() + " ASC");
        }
        return keys.isEmpty() ? "" : "ORDER BY " + String.join(", ", keys);
    }

    private String fieldName(ISort sort) throws ApiException {
        String field = sort.getFieldName();
        if (field == null || field.isBlank()) {
            throw new ApiException("Cannot sort domain '" + table.name() + "': the sort names no field.");
        }
        return field.trim();
    }

    /** The keys of a field, resolved most specific first; none for an unknown field. */
    private List<String> keys(String field, boolean ascending) throws ApiException {
        Optional<SqlColumn> column = table.column(field);
        if (column.isPresent()) {
            return columnKeys(column.get(), ascending);
        }
        SqlBsonOrder bson = new SqlBsonOrder(table);
        Optional<SqlChildTable> child = table.children().stream().filter(c -> c.dottedPath().equals(field))
                .findFirst();
        if (child.isPresent()) {
            return childKeys(bson, child.get(), ascending);
        }
        Optional<SqlColumn> presence = table.presence(field);
        if (presence.isPresent()) {
            refuseNested(field);
            SqlColumn p = presence.get();
            return List.of(bson.document(SqlQuery.ALIAS, table.columns(), p.fieldPath(),
                    SqlBsonOrder.ref(SqlQuery.ALIAS, p)).orNull() + direction(ascending));
        }
        Optional<SqlPathLocator.Located> located = locator.locate(field);
        if (located.isPresent()) {
            return pathKeys(bson, located.get(), field, ascending);
        }
        refuseInsideJson(field, table.columns());
        LOG.debug("Sort on unknown field '{}' of domain '{}': no key, every row ties as in MongoDB", field,
                table.name());
        return List.of();
    }

    /** A single main-table column, keyed so its order is BSON's. */
    private List<String> columnKeys(SqlColumn column, boolean ascending) throws ApiException {
        if (SqlBsonOrder.rank(column).isEmpty()) {
            throw new SqlBsonOrder(table).unsortable(column);
        }
        String ref = SqlBsonOrder.ref(SqlQuery.ALIAS, column);
        String dir = direction(ascending);
        if (SqlBsonOrder.floating(column)) {
            return List.of(table.dialect().notNaN(ref) + dir, ref + dir);
        }
        if (SqlTypes.BYTEA.equals(column.sqlType())) {
            return List.of(table.dialect().byteLength(ref) + dir, ref + dir);
        }
        return List.of(ref + ("TEXT".equals(column.sqlType()) ? table.dialect().binaryCollation() : "") + dir);
    }

    /** A whole collection (by its smallest / largest element) or a whole map (as a sub-document). */
    private List<String> childKeys(SqlBsonOrder bson, SqlChildTable child, boolean ascending) throws ApiException {
        refuseNested(child.dottedPath());
        Optional<SqlColumn> presence = table.presence(child.dottedPath());
        String presenceRef = presence.map(p -> SqlBsonOrder.ref(SqlQuery.ALIAS, p)).orElse("TRUE");
        if (child.kind() == SqlChildKind.MAP) {
            return List.of(bson.map(child, presenceRef).orNull() + direction(ascending));
        }
        String c = bson.alias("c");
        List<String> keys = new ArrayList<>();
        // MongoDB: an EMPTY array sorts below a missing field (and so, descending, after it).
        String e = bson.alias("c");
        keys.add("CASE WHEN " + presenceRef + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM "
                + SqlNaming.quote(child.name()) + " " + e + " WHERE " + bson.owner(e) + ") THEN 0 ELSE 1 END"
                + (ascending ? " ASC" : " DESC"));
        keys.add(extreme(bson, child, c, bson.element(child, c), ascending));
        return keys;
    }

    /**
     * A path through the child tables — inside the elements of a collection ({@code lines.qty}),
     * across several collections ({@code orders.lines.qty}), onto a map entry ({@code stock.paris.qty}),
     * or a collection nested in an element ({@code lines.tags}): smallest / largest over every value
     * reachable ({@link SqlPathSortKey}). MongoDB gives an empty array no special place here — its key is
     * simply missing.
     */
    private List<String> pathKeys(SqlBsonOrder bson, SqlPathLocator.Located at, String field, boolean ascending)
            throws ApiException {
        SqlHop last = at.last();
        SqlChildTable child = last.child();
        SqlBsonOrder.Encoded element;
        if (at.rest() == null) {
            // A nested collection of scalars sorts by its extreme element; one of objects, a map or a
            // reference collection would need BSON array-of-document order across levels. A map ENTRY is
            // a plain sub-document.
            boolean scalars = child.kind() == SqlChildKind.SCALAR_COLLECTION;
            if (child.hasChildren() || (!scalars && !last.keyed())) {
                throw nestedUnsortable(field);
            }
            element = bson.element(child, last.alias());
        } else {
            Optional<SqlColumn> column = child.valueColumns().stream()
                    .filter(v -> v.dottedPath().equals(at.rest())).findFirst();
            if (column.isEmpty()) {
                refuseInsideJson(at.rest(), child.valueColumns());
                LOG.debug("Sort on unknown element path '{}' of '{}': no key", at.rest(), child.dottedPath());
                return List.of();
            }
            element = elementValue(bson, last, column.get(), field);
        }
        return List.of(SqlPathSortKey.of(bson, at.hops(), element, direction(ascending)));
    }

    /** One value column of an element — or, for a presence bit, the POJO it marks, as a sub-document. */
    private SqlBsonOrder.Encoded elementValue(SqlBsonOrder bson, SqlHop hop, SqlColumn target, String field)
            throws ApiException {
        if (target.kind() != SqlColumnKind.PRESENCE) {
            return bson.scalar(hop.alias(), target);
        }
        // A POJO of the element holding a collection: that collection is a nested table, not a column.
        List<String> pojo = target.fieldPath();
        if (hop.child().children().stream().anyMatch(c -> c.fieldPath().size() > pojo.size()
                && c.fieldPath().subList(0, pojo.size()).equals(pojo))) {
            throw nestedUnsortable(field);
        }
        return bson.document(hop.alias(), hop.child().valueColumns(), target.fieldPath(),
                SqlBsonOrder.ref(hop.alias(), target));
    }

    /**
     * Refuses a structure that is, or holds, a collection nested in a collection: its BSON order is not
     * reproduced — the sub-document encoding reads flattened columns and top-level arrays only.
     */
    private void refuseNested(String path) throws ApiException {
        boolean nested = table.allChildren().stream().anyMatch(c -> (c.nested() || c.hasChildren())
                && (c.dottedPath().equals(path) || c.dottedPath().startsWith(path + ".")));
        if (nested) {
            throw nestedUnsortable(path);
        }
    }

    private ApiException nestedUnsortable(String path) {
        return new ApiException("Cannot sort domain '" + table.name() + "' on '" + path + "': it is, or holds,"
                + " a collection nested in another collection, whose MongoDB (BSON) order the PostgreSQL DAO"
                + " does not reproduce. Sort on a scalar reachable through it instead.");
    }

    /** {@code (SELECT smallest-or-largest element)}: a null element is the smallest of all. */
    private String extreme(SqlBsonOrder bson, SqlChildTable child, String c, SqlBsonOrder.Encoded element,
            boolean ascending) {
        String k = bson.alias("k");
        String dir = direction(ascending);
        return "(SELECT " + k + ".e FROM (SELECT " + element.orNull() + " AS e FROM " + SqlNaming.quote(child.name())
                + " " + c + " WHERE " + bson.owner(c) + ") AS " + k + " ORDER BY " + k + ".e" + dir + " LIMIT 1)"
                + dir;
    }

    /** A path into a JSONB (or geometry) value: MongoDB would sort on it, but its BSON order is not reproduced. */
    private void refuseInsideJson(String path, List<SqlColumn> columns) throws ApiException {
        for (SqlColumn column : columns) {
            if (column.kind() != SqlColumnKind.PRESENCE && SqlBsonOrder.rank(column).isEmpty()
                    && !column.fieldPath().isEmpty() && path.startsWith(column.dottedPath() + ".")) {
                throw new SqlBsonOrder(table).unsortable(column);
            }
        }
    }

    private static String direction(boolean ascending) {
        return ascending ? " ASC NULLS FIRST" : " DESC NULLS LAST";
    }
}
