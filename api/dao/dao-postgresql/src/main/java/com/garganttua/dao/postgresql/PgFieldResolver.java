package com.garganttua.dao.postgresql;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Resolves a filter's field name (a dotted DTO path, used as is, like MongoDB does) to where it lives
 * in the relational model.
 *
 * <p>
 * This is the only door from filter text to SQL identifiers: every identifier that reaches a
 * statement comes from the {@link PgTable}, never from the filter, so a field name cannot inject SQL.
 * A name the model does not know is not an error: MongoDB has no schema, and a field no document
 * holds is simply ABSENT from all of them — {@code $eq null} matches everything, {@code $eq x}
 * nothing. Such a name resolves to {@link PgField.Opaque#MISSING}, which needs no identifier at all.
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

    private final PgTable table;
    private final PgPathLocator locator;

    PgFieldResolver(PgTable table) {
        this.table = table;
        this.locator = new PgPathLocator(table, CHILD_ALIAS, false);
    }

    /**
     * Resolves a field name.
     *
     * @param field the dotted field path from the filter
     * @return where it lives — {@link PgField.Opaque#MISSING} when the model has no such field
     */
    PgField resolve(String field) {
        Optional<PgField> reference = reference(field);
        if (reference.isPresent()) {
            return reference.get();
        }
        Optional<PgOperand> main = inColumns(table.columns(), PgQuery.ALIAS, field);
        if (main.isPresent()) {
            return new PgField.Scalar(main.get());
        }
        Optional<PgPathLocator.Located> located = locator.locate(field);
        if (located.isPresent()) {
            return located.get().rest() == null ? whole(located.get()) : inElement(located.get());
        }
        return table.presence(field).<PgField>map(p -> PgField.Opaque.of(isNull(PgQuery.ALIAS, p)))
                .orElse(PgField.Opaque.MISSING);
    }

    /**
     * A {@code @Composed} reference. MongoDB stores it as a DBRef sub-document {@code {$ref, $id}}: the
     * field itself is an object (it equals no string), and {@code field.$id} is the referenced uuid.
     */
    private Optional<PgField> reference(String field) {
        for (PgColumn column : table.columns()) {
            if (column.kind() != PgColumnKind.COMPOSITION) {
                continue;
            }
            if (column.dottedPath().equals(field)) {
                return Optional.of(PgField.Opaque.of(isNull(PgQuery.ALIAS, column)));
            }
            if (field.equals(column.dottedPath() + "." + DBREF_ID)) {
                return Optional.of(new PgField.Scalar(PgOperand.of(PgQuery.ALIAS, column)));
            }
        }
        return Optional.empty();
    }

    /**
     * The collection itself, or the value of a map entry. Scalars (a scalar collection, a scalar entry)
     * are compared element by element; anything else is an object, of which only presence can be
     * asked — absent on EVERY branch for {@code $exists: false}, on SOME branch for {@code $eq null}.
     */
    private PgField whole(PgPathLocator.Located at) {
        List<PgHop> hops = at.hops();
        PgHop last = at.last();
        PgChildTable child = last.child();
        if (last.keyed()) {
            if (scalarValues(child)) {
                return new PgField.Element(hops, value(last), false, null);
            }
            PgSql noValue = last.rows(PgSql.of(last.ref(PgChildTable.PRESENT) + " IS NOT NULL"))
                    .wrap("NOT EXISTS (", ")");
            return new PgField.Opaque(PgHop.noneHolds(hops), PgHop.someMisses(hops, noValue));
        }
        return switch (child.kind()) {
            case SCALAR_COLLECTION -> new PgField.Element(hops, value(last), true, null);
            case NESTED_COLLECTION -> nestedArrays(hops);
            case POJO_COLLECTION -> objects(hops);
            case MAP, COMPOSITION_COLLECTION -> new PgField.Opaque(PgHop.noneHolds(hops),
                    PgHop.someMisses(hops, last.absent()));
        };
    }

    /** A collection whose elements are collections: compared as arrays when they hold scalars. */
    private PgField nestedArrays(List<PgHop> hops) {
        Optional<PgHop> items = locator.items(hops)
                .filter(i -> i.child().kind() == PgChildKind.SCALAR_COLLECTION);
        return items.<PgField>map(i -> new PgField.Element(hops, value(i), true, i))
                .orElseGet(() -> objects(hops));
    }

    /** A collection of objects: absent, or holding a null element, is what {@code $eq null} matches. */
    private static PgField objects(List<PgHop> hops) {
        PgHop last = hops.get(hops.size() - 1);
        PgSql nullElement = last.rows(PgSql.of(last.ref(PgChildTable.PRESENT) + " IS NULL"))
                .wrap("EXISTS (", ")");
        return new PgField.Opaque(PgHop.noneHolds(hops),
                PgHop.someMisses(hops, PgSql.any(List.of(last.absent(), nullElement))));
    }

    /**
     * A path inside the elements of a collection ({@code lines.sku}, {@code orders.lines.sku}) or inside
     * the value of a map entry ({@code stock.apple.qty}).
     */
    private static PgField inElement(PgPathLocator.Located at) {
        PgHop last = at.last();
        PgChildTable child = last.child();
        Optional<PgOperand> operand = switch (child.kind()) {
            case COMPOSITION_COLLECTION -> DBREF_ID.equals(at.rest()) ? Optional.of(value(last)) : Optional.empty();
            case POJO_COLLECTION, MAP -> inColumns(child.valueColumns(), last.alias(), at.rest());
            default -> Optional.empty();
        };
        return operand.<PgField>map(o -> new PgField.Element(at.hops(), o, false, null))
                .orElse(PgField.Opaque.MISSING);
    }

    /** {@return the single value column of a scalar collection, under the hop's alias} */
    private static PgOperand value(PgHop hop) {
        return PgOperand.of(hop.alias(), hop.child().valueColumns().get(0));
    }

    /** {@return whether a map's values are scalars: one value column, the value itself} */
    static boolean scalarValues(PgChildTable child) {
        return child.elementTable().isEmpty() && child.valueColumns().size() == 1
                && child.valueColumns().get(0).fieldPath().isEmpty();
    }

    private static PgSql isNull(String alias, PgColumn column) {
        return PgSql.of(alias + "." + PgNaming.quote(column.name()) + " IS NULL");
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
    static Optional<PgOperand> inColumns(List<PgColumn> columns, String alias, String field) {
        List<PgColumn> values = columns.stream()
                .filter(c -> c.kind() != PgColumnKind.PRESENCE && c.kind() != PgColumnKind.COMPOSITION).toList();
        for (PgColumn column : values) {
            if (column.dottedPath().equals(field)) {
                return Optional.of(PgOperand.of(alias, column));
            }
        }
        for (PgColumn column : values) {
            String prefix = column.dottedPath() + ".";
            if (column.kind() == PgColumnKind.JSONB && !column.dottedPath().isEmpty()
                    && field.startsWith(prefix) && field.length() > prefix.length()) {
                List<String> path = Arrays.asList(field.substring(prefix.length()).split("\\.", -1));
                return Optional.of(new PgOperand(alias, column, path));
            }
        }
        return Optional.empty();
    }
}
