package com.garganttua.dao.postgresql;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.garganttua.dao.sql.schema.SqlChildKind;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.SqlField;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlHop;
import com.garganttua.dao.sql.SqlOperand;
import com.garganttua.dao.sql.SqlQuery;

/**
 * Resolves a filter's field name (a dotted DTO path, used as is, like MongoDB does) to where it lives
 * in the relational model.
 *
 * <p>
 * This is the only door from filter text to SQL identifiers: every identifier that reaches a
 * statement comes from the {@link SqlTable}, never from the filter, so a field name cannot inject SQL.
 * A name the model does not know is not an error: MongoDB has no schema, and a field no document
 * holds is simply ABSENT from all of them — {@code $eq null} matches everything, {@code $eq x}
 * nothing. Such a name resolves to {@link SqlField.Opaque#MISSING}, which needs no identifier at all.
 * The same holds for a path into a scalar ({@code name.first}) or into an unknown leaf of an embedded
 * object.
 * </p>
 *
 * <p>
 * Resolution order, most specific first: a reference ({@code customer}, or {@code customer.$id}, the
 * path of MongoDB's DBRef id), a main-table column or a path inside a JSONB one, a path through the
 * child tables — a collection, a path inside its elements, a map entry, at any depth
 * ({@link PgPathLocator}) — and last an embedded POJO.
 * </p>
 */
final class PgFieldResolver {

    /** The alias of the first-level child table inside an {@code EXISTS}; deeper levels append their depth. */
    static final String CHILD_ALIAS = "c";

    /** The DBRef field MongoDB stores a reference's id under. */
    static final String DBREF_ID = "$id";

    private final SqlTable table;
    private final PgPathLocator locator;

    PgFieldResolver(SqlTable table) {
        this.table = table;
        this.locator = new PgPathLocator(table, CHILD_ALIAS, false);
    }

    /**
     * Resolves a field name.
     *
     * @param field the dotted field path from the filter
     * @return where it lives — {@link SqlField.Opaque#MISSING} when the model has no such field
     */
    SqlField resolve(String field) {
        Optional<SqlField> reference = reference(field);
        if (reference.isPresent()) {
            return reference.get();
        }
        Optional<SqlOperand> main = inColumns(table.columns(), SqlQuery.ALIAS, field);
        if (main.isPresent()) {
            return new SqlField.Scalar(main.get());
        }
        Optional<PgPathLocator.Located> located = locator.locate(field);
        if (located.isPresent()) {
            return located.get().rest() == null ? whole(located.get()) : inElement(located.get());
        }
        return table.presence(field).<SqlField>map(p -> SqlField.Opaque.of(isNull(SqlQuery.ALIAS, p)))
                .orElse(SqlField.Opaque.MISSING);
    }

    /**
     * A {@code @Composed} reference. MongoDB stores it as a DBRef sub-document {@code {$ref, $id}}: the
     * field itself is an object (it equals no string), and {@code field.$id} is the referenced uuid.
     */
    private Optional<SqlField> reference(String field) {
        for (SqlColumn column : table.columns()) {
            if (column.kind() != SqlColumnKind.COMPOSITION) {
                continue;
            }
            if (column.dottedPath().equals(field)) {
                return Optional.of(SqlField.Opaque.of(isNull(SqlQuery.ALIAS, column)));
            }
            if (field.equals(column.dottedPath() + "." + DBREF_ID)) {
                return Optional.of(new SqlField.Scalar(SqlOperand.of(SqlQuery.ALIAS, column)));
            }
        }
        return Optional.empty();
    }

    /**
     * The collection itself, or the value of a map entry. Scalars (a scalar collection, a scalar entry)
     * are compared element by element; anything else is an object, of which only presence can be
     * asked — absent on EVERY branch for {@code $exists: false}, on SOME branch for {@code $eq null}.
     */
    private SqlField whole(PgPathLocator.Located at) {
        List<SqlHop> hops = at.hops();
        SqlHop last = at.last();
        SqlChildTable child = last.child();
        if (last.keyed()) {
            if (scalarValues(child)) {
                return new SqlField.Element(hops, value(last), false, null);
            }
            SqlFragment noValue = last.rows(SqlFragment.of(last.ref(SqlChildTable.PRESENT) + " IS NOT NULL"))
                    .wrap("NOT EXISTS (", ")");
            return new SqlField.Opaque(SqlHop.noneHolds(hops), SqlHop.someMisses(hops, noValue));
        }
        return switch (child.kind()) {
            case SCALAR_COLLECTION -> new SqlField.Element(hops, value(last), true, null);
            case NESTED_COLLECTION -> nestedArrays(hops);
            case POJO_COLLECTION -> objects(hops);
            case MAP, COMPOSITION_COLLECTION -> new SqlField.Opaque(SqlHop.noneHolds(hops),
                    SqlHop.someMisses(hops, last.absent()));
        };
    }

    /** A collection whose elements are collections: compared as arrays when they hold scalars. */
    private SqlField nestedArrays(List<SqlHop> hops) {
        Optional<SqlHop> items = locator.items(hops)
                .filter(i -> i.child().kind() == SqlChildKind.SCALAR_COLLECTION);
        return items.<SqlField>map(i -> new SqlField.Element(hops, value(i), true, i))
                .orElseGet(() -> objects(hops));
    }

    /** A collection of objects: absent, or holding a null element, is what {@code $eq null} matches. */
    private static SqlField objects(List<SqlHop> hops) {
        SqlHop last = hops.get(hops.size() - 1);
        SqlFragment nullElement = last.rows(SqlFragment.of(last.ref(SqlChildTable.PRESENT) + " IS NULL"))
                .wrap("EXISTS (", ")");
        return new SqlField.Opaque(SqlHop.noneHolds(hops),
                SqlHop.someMisses(hops, SqlFragment.any(List.of(last.absent(), nullElement))));
    }

    /**
     * A path inside the elements of a collection ({@code lines.sku}, {@code orders.lines.sku}) or inside
     * the value of a map entry ({@code stock.apple.qty}).
     */
    private static SqlField inElement(PgPathLocator.Located at) {
        SqlHop last = at.last();
        SqlChildTable child = last.child();
        Optional<SqlOperand> operand = switch (child.kind()) {
            case COMPOSITION_COLLECTION -> DBREF_ID.equals(at.rest()) ? Optional.of(value(last)) : Optional.empty();
            case POJO_COLLECTION, MAP -> inColumns(child.valueColumns(), last.alias(), at.rest());
            default -> Optional.empty();
        };
        return operand.<SqlField>map(o -> new SqlField.Element(at.hops(), o, false, null))
                .orElse(SqlField.Opaque.MISSING);
    }

    /** {@return the single value column of a scalar collection, under the hop's alias} */
    private static SqlOperand value(SqlHop hop) {
        return SqlOperand.of(hop.alias(), hop.child().valueColumns().get(0));
    }

    /** {@return whether a map's values are scalars: one value column, the value itself} */
    static boolean scalarValues(SqlChildTable child) {
        return child.elementTable().isEmpty() && child.valueColumns().size() == 1
                && child.valueColumns().get(0).fieldPath().isEmpty();
    }

    private static SqlFragment isNull(String alias, SqlColumn column) {
        return SqlFragment.of(alias + "." + SqlNaming.quote(column.name()) + " IS NULL");
    }

    /**
     * A value column whose path is the field, or a JSONB column whose path prefixes it. Presence bits
     * and references are not values, and are never returned.
     *
     * @param columns the columns to search (main table, or a child's value columns)
     * @param alias   their table alias
     * @param field   the path, relative to the columns' owner
     * @return the operand, or empty when no column matches
     */
    static Optional<SqlOperand> inColumns(List<SqlColumn> columns, String alias, String field) {
        List<SqlColumn> values = columns.stream()
                .filter(c -> c.kind() != SqlColumnKind.PRESENCE && c.kind() != SqlColumnKind.COMPOSITION).toList();
        for (SqlColumn column : values) {
            if (column.dottedPath().equals(field)) {
                return Optional.of(SqlOperand.of(alias, column));
            }
        }
        for (SqlColumn column : values) {
            String prefix = column.dottedPath() + ".";
            if (column.kind() == SqlColumnKind.JSONB && !column.dottedPath().isEmpty()
                    && field.startsWith(prefix) && field.length() > prefix.length()) {
                List<String> path = Arrays.asList(field.substring(prefix.length()).split("\\.", -1));
                return Optional.of(new SqlOperand(alias, column, path));
            }
        }
        return Optional.empty();
    }
}
