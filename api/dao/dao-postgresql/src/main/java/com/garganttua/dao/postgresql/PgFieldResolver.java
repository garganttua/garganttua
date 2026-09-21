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
 * This is the only door from filter text to SQL identifiers: a name that does not resolve to
 * something the {@link PgTable} built is refused, so a field name can never inject SQL — the
 * identifiers that reach a statement all come from the model. Resolution order, most specific
 * first: a main-table column (or a path inside a JSONB one), a collection, a path inside a
 * collection's elements, an embedded POJO.
 * </p>
 */
final class PgFieldResolver {

    /** The alias of the child table inside an {@code EXISTS} subquery. */
    static final String CHILD_ALIAS = "c";

    private final PgTable table;

    PgFieldResolver(PgTable table) {
        this.table = table;
    }

    /**
     * Resolves a field name.
     *
     * @param field the dotted field path from the filter
     * @return where it lives
     * @throws ApiException when the model has no such field
     */
    PgField resolve(String field) throws ApiException {
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
            return inElement(child, field.substring(child.dottedPath().length() + 1), field);
        }
        List<PgColumn> pojo = table.columns().stream()
                .filter(c -> c.dottedPath().startsWith(field + ".")).toList();
        if (!pojo.isEmpty()) {
            return new PgField.Pojo(pojo);
        }
        throw unknown(field);
    }

    /** {@return the {@code _owner} condition tying child rows to the current main row} */
    PgSql ownerScope() {
        return PgSql.of(CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.OWNER) + " = " + PgQuery.ALIAS + "."
                + PgNaming.quote(table.id().name()));
    }

    ApiException unknown(String field) {
        return new ApiException("Unknown field '" + field + "' in a filter on domain '" + table.name()
                + "': it is neither a column, a collection, nor a path into one. Filter field names are"
                + " DTO field paths, dotted for nested fields (e.g. 'address.city').");
    }

    /** The collection itself: its single value column when it has one, otherwise presence only. */
    private PgField whole(PgChildTable child) {
        boolean singleValue = child.kind() == PgChildKind.SCALAR_COLLECTION
                || child.kind() == PgChildKind.COMPOSITION_COLLECTION;
        PgOperand operand = singleValue ? PgOperand.of(CHILD_ALIAS, child.valueColumns().get(0)) : null;
        return new PgField.Element(child, ownerScope(), operand, true);
    }

    /** A path inside the elements of a collection ({@code lines.sku}) or an entry of a map ({@code stock.apple}). */
    private PgField inElement(PgChildTable child, String rest, String field) throws ApiException {
        if (child.kind() != PgChildKind.MAP) {
            PgOperand operand = inColumns(child.valueColumns(), CHILD_ALIAS, rest)
                    .orElseThrow(() -> unknown(field));
            return new PgField.Element(child, ownerScope(), operand, false);
        }
        boolean scalarValues = child.valueColumns().size() == 1
                && child.valueColumns().get(0).fieldPath().isEmpty();
        int dot = rest.indexOf('.');
        String key = scalarValues || dot < 0 ? rest : rest.substring(0, dot);
        PgOperand operand = null;
        if (scalarValues) {
            operand = PgOperand.of(CHILD_ALIAS, child.valueColumns().get(0));
        } else if (dot >= 0) {
            operand = inColumns(child.valueColumns(), CHILD_ALIAS, rest.substring(dot + 1))
                    .orElseThrow(() -> unknown(field));
        }
        return new PgField.Element(child, keyScope(child, key), operand, false);
    }

    /** The owner condition narrowed to one map key — the key is filter text, so it is bound. */
    private PgSql keyScope(PgChildTable child, String key) throws ApiException {
        PgColumn keyColumn = new PgColumn(PgChildTable.KEY, PgTypes.sqlTypeOf(child.keyType()).orElse(PgTypes.TEXT),
                PgColumnKind.SCALAR, List.of(), child.keyType());
        return ownerScope().then(PgSql.of(" AND " + CHILD_ALIAS + "." + PgNaming.quote(PgChildTable.KEY)
                + " = " + PgValues.placeholder(keyColumn), PgValues.toJdbc(keyColumn, key)));
    }

    /** The deepest collection whose path is a proper prefix of the field. */
    private Optional<PgChildTable> containingChild(String field) {
        return table.children().stream()
                .filter(c -> field.startsWith(c.dottedPath() + "."))
                .max(Comparator.comparingInt(c -> c.dottedPath().length()));
    }

    /**
     * A column of a list whose path is the field, or a JSONB column whose path prefixes it.
     *
     * @param columns the columns to search (main table, or a child's value columns)
     * @param alias   their table alias
     * @param field   the path, relative to the columns' owner
     * @return the operand, or empty when no column matches
     */
    static Optional<PgOperand> inColumns(List<PgColumn> columns, String alias, String field) {
        for (PgColumn column : columns) {
            if (column.kind() != PgColumnKind.PRESENCE && column.dottedPath().equals(field)) {
                return Optional.of(PgOperand.of(alias, column));
            }
        }
        for (PgColumn column : columns) {
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
