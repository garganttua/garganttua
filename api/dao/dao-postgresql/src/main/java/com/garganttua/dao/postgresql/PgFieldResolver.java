package com.garganttua.dao.postgresql;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgChildKind;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

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
 * path of MongoDB's DBRef id), a main-table column or a path inside a JSONB one, a collection, a path
 * inside a collection's elements, an embedded POJO.
 * </p>
 */
final class PgFieldResolver {

    /** The alias of the child table inside an {@code EXISTS} subquery. */
    static final String CHILD_ALIAS = "c";

    /** The DBRef field MongoDB stores a reference's id under. */
    static final String DBREF_ID = "$id";

    private final PgTable table;

    PgFieldResolver(PgTable table) {
        this.table = table;
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
        Optional<PgChildTable> exact = table.child(field);
        if (exact.isPresent()) {
            return whole(exact.get());
        }
        Optional<PgChildTable> container = containingChild(field);
        if (container.isPresent()) {
            PgChildTable child = container.get();
            return inElement(child, field.substring(child.dottedPath().length() + 1));
        }
        return table.presence(field).<PgField>map(p -> PgField.Opaque.of(isNull(PgQuery.ALIAS, p)))
                .orElse(PgField.Opaque.MISSING);
    }

    /** {@return the {@code _owner} condition tying child rows to the current main row} */
    PgSql ownerScope() {
        return PgSql.of(CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.OWNER) + " = " + PgQuery.ALIAS + "."
                + PgNaming.quote(table.id().name()));
    }

    /** {@return {@code SELECT 1 FROM <child> c WHERE <scope>}} */
    static PgSql rows(PgChildTable child, PgSql scope) {
        return PgSql.of("SELECT 1 FROM " + PgNaming.quote(child.name()) + " " + CHILD_ALIAS + " WHERE ").then(scope);
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

    /** The collection itself: its value column for scalars, otherwise an object tested for presence. */
    private PgField whole(PgChildTable child) {
        PgSql absent = absent(child);
        return switch (child.kind()) {
            case SCALAR_COLLECTION -> new PgField.Element(child, ownerScope(),
                    PgOperand.of(CHILD_ALIAS, child.valueColumns().get(0)), true, absent);
            case POJO_COLLECTION -> new PgField.Opaque(absent, PgSql.any(List.of(absent, rows(child,
                    ownerScope().then(" AND " + present() + " IS NULL")).wrap("EXISTS (", ")"))));
            case MAP, COMPOSITION_COLLECTION -> PgField.Opaque.of(absent);
        };
    }

    /** A path inside the elements of a collection ({@code lines.sku}) or an entry of a map ({@code stock.apple}). */
    private PgField inElement(PgChildTable child, String rest) {
        if (child.kind() == PgChildKind.MAP) {
            return inMapEntry(child, rest);
        }
        Optional<PgOperand> operand = switch (child.kind()) {
            case COMPOSITION_COLLECTION -> DBREF_ID.equals(rest)
                    ? Optional.of(PgOperand.of(CHILD_ALIAS, child.valueColumns().get(0)))
                    : Optional.empty();
            case POJO_COLLECTION -> inColumns(child.valueColumns(), CHILD_ALIAS, rest);
            default -> Optional.empty();
        };
        return operand.<PgField>map(o -> new PgField.Element(child, ownerScope(), o, false, absent(child)))
                .orElse(PgField.Opaque.MISSING);
    }

    /** {@code stock.apple} (a scalar value), {@code stock.apple} (an object value) or {@code stock.apple.qty}. */
    private PgField inMapEntry(PgChildTable child, String rest) {
        boolean scalarValues = child.valueColumns().size() == 1
                && child.valueColumns().get(0).fieldPath().isEmpty();
        int dot = rest.indexOf('.');
        String key = scalarValues || dot < 0 ? rest : rest.substring(0, dot);
        Optional<PgSql> scope = keyScope(child, key);
        if (scope.isEmpty()) {
            return PgField.Opaque.MISSING;
        }
        PgSql keyAbsent = rows(child, scope.get()).wrap("NOT EXISTS (", ")");
        if (scalarValues) {
            return new PgField.Element(child, scope.get(), PgOperand.of(CHILD_ALIAS, child.valueColumns().get(0)),
                    false, keyAbsent);
        }
        if (dot < 0) {
            PgSql noValue = rows(child, scope.get().then(" AND " + present() + " IS NOT NULL"))
                    .wrap("NOT EXISTS (", ")");
            return new PgField.Opaque(keyAbsent, noValue);
        }
        return inColumns(child.valueColumns(), CHILD_ALIAS, rest.substring(dot + 1))
                .<PgField>map(o -> new PgField.Element(child, scope.get(), o, false, keyAbsent))
                .orElse(PgField.Opaque.MISSING);
    }

    /**
     * The owner condition narrowed to one map key — the key is filter text, so it is bound. A key
     * that cannot be a key of the map (text against an Integer-keyed map) is in no document.
     */
    private Optional<PgSql> keyScope(PgChildTable child, String key) {
        PgColumn keyColumn = new PgColumn(PgChildTable.KEY, PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT),
                PgColumnKind.SCALAR, List.of(), child.keyType());
        try {
            return Optional.of(ownerScope().then(PgSql.of(" AND " + CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.KEY)
                    + " = " + PgValues.placeholder(keyColumn), PgValues.toJdbc(keyColumn, key))));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /** "The collection is absent": its presence bit on the owner row is NULL. */
    private PgSql absent(PgChildTable child) {
        return table.presence(child.dottedPath()).map(p -> isNull(PgQuery.ALIAS, p))
                .orElseGet(() -> rows(child, ownerScope()).wrap("NOT EXISTS (", ")"));
    }

    private static String present() {
        return CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.PRESENT);
    }

    private static PgSql isNull(String alias, PgColumn column) {
        return PgSql.of(alias + "." + PgNaming.quote(column.name()) + " IS NULL");
    }

    /** The deepest collection whose path is a proper prefix of the field. */
    private Optional<PgChildTable> containingChild(String field) {
        return table.children().stream()
                .filter(c -> field.startsWith(c.dottedPath() + "."))
                .max(Comparator.comparingInt(c -> c.dottedPath().length()));
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
