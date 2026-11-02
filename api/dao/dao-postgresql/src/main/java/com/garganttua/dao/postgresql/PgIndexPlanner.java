package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * Turns the indexes an entity declares into the PostgreSQL indexes that hold them.
 *
 * <p>
 * Each declaration is resolved against the domain's {@link PgTable}, the single source of truth for
 * where a field lives:
 * </p>
 * <ul>
 * <li>a main-table column ({@code code}, or {@code address.city} flattened to {@code address__city})
 * gets a {@code btree}; a {@code tenant}-scoped one is composed {@code (tenantId, field)}, as on
 * MongoDB, and a unique one is partial — {@code WHERE field IS NOT NULL};</li>
 * <li>a collection ({@code tags}) or a field of its elements ({@code lines.code}) lives in a child
 * table: the index is on the child table's value column, alone — a child row carries no tenant;</li>
 * <li>{@link IndexKind#geo} on a geometry gets a {@code gist} index on the column cast to
 * {@code geography}: every geographic predicate is evaluated on the sphere, on that very expression,
 * so the index serves them all;</li>
 * <li>{@link IndexKind#text} on a text column gets a {@code gin} index on the text-search vector
 * {@code $text} then queries (see {@link PgTextSearch#indexedVector(String)}).</li>
 * </ul>
 *
 * <p>
 * What PostgreSQL cannot hold is a refusal with its reason, never a silent skip: a unique index on a
 * collection (it would have to span the rows of a child table), a field that maps no column, a kind
 * that does not fit the column. The manager decides, per mode, whether a refusal is a WARN or stops
 * the application.
 * </p>
 */
final class PgIndexPlanner {

    private static final String BTREE = "btree";
    private static final String FIELD = "field '";

    /**
     * What one declaration becomes: an index to create, or the reason it cannot be.
     *
     * @param rule    the declaration
     * @param spec    the index, when it can be built
     * @param refusal why it cannot be, otherwise
     */
    record Planned(EntityIndexRule rule, Optional<PgIndexSpec> spec, String refusal) {

        static Planned of(EntityIndexRule rule, PgIndexSpec spec) {
            return new Planned(rule, Optional.of(spec), null);
        }

        static Planned refused(EntityIndexRule rule, String refusal) {
            return new Planned(rule, Optional.empty(), refusal);
        }
    }

    /** Where a declared field lives: a column of the main table or of one child table. */
    private record Located(String table, PgColumn column, boolean child) {
    }

    private PgIndexPlanner() {
    }

    /**
     * Plans every declaration of a domain.
     *
     * @param table      the domain's relational shape
     * @param rules      the declared indexes, possibly empty or {@code null}
     * @param toDtoPath  translates an entity field path to the DTO path the model is keyed by
     * @param tenantPath the DTO path of the tenant identifier, or {@code null} when the domain has none
     * @return one plan per declaration, in declaration order
     */
    static List<Planned> plan(PgTable table, List<EntityIndexRule> rules, UnaryOperator<String> toDtoPath,
            String tenantPath) {
        List<Planned> plans = new ArrayList<>();
        boolean textPlanned = false;
        for (EntityIndexRule rule : rules == null ? List.<EntityIndexRule>of() : rules) {
            if (rule == null) {
                continue;
            }
            if (rule.kind() == IndexKind.text && textPlanned) {
                plans.add(Planned.refused(rule, "only one text index is honoured per domain, as on MongoDB, which "
                        + "holds one per collection: $text searches the first declared text field"));
                continue;
            }
            Planned planned = planOne(table, rule, toDtoPath.apply(rule.field().toString()), tenantPath);
            textPlanned |= rule.kind() == IndexKind.text && planned.spec().isPresent();
            plans.add(planned);
        }
        return plans;
    }

    /**
     * {@return the column $text must search, when the domain declares a text index on one} Empty
     * means none: {@code $text} then searches every string of the entity.
     *
     * @param plans the domain's plans
     */
    static Optional<PgColumn> textColumn(PgTable table, List<Planned> plans) {
        return plans.stream().filter(p -> p.rule().kind() == IndexKind.text && p.spec().isPresent())
                .findFirst().flatMap(p -> table.column(p.spec().get().field()));
    }

    private static Planned planOne(PgTable table, EntityIndexRule rule, String path, String tenantPath) {
        Optional<Located> located = locate(table, path);
        if (located.isEmpty()) {
            return Planned.refused(rule, FIELD + path + "' maps no column of table '" + table.name()
                    + "' (a presence-only structure, or a field the DTO does not carry)");
        }
        Located where = located.get();
        return switch (rule.kind()) {
            case geo -> geo(rule, path, where);
            case text -> text(rule, path, where);
            case standard -> standard(table, rule, path, where, tenantPath);
        };
    }

    private static Planned standard(PgTable table, EntityIndexRule rule, String path, Located where,
            String tenantPath) {
        PgColumnKind kind = where.column().kind();
        if (kind == PgColumnKind.GEOMETRY || kind == PgColumnKind.IKEY) {
            return Planned.refused(rule, FIELD + path + "' is a " + kind.name().toLowerCase(java.util.Locale.ROOT)
                    + " column, which a standard index does not serve" + (kind == PgColumnKind.GEOMETRY
                            ? ": declare it with IndexKind.geo" : ""));
        }
        if (where.child()) {
            if (rule.unique()) {
                return Planned.refused(rule, FIELD + path + "' is held in child table '" + where.table()
                        + "', one row per element: a unique index there would refuse the same value in two "
                        + "elements of ONE entity, not in two entities — PostgreSQL cannot hold this uniqueness");
            }
            return Planned.of(rule, spec(rule, where, BTREE, List.of(where.column().name()), path));
        }
        List<String> columns = new ArrayList<>();
        Optional<PgColumn> tenant = tenantPath == null ? Optional.empty() : table.column(tenantPath);
        if (rule.scope() == UnicityScope.tenant && tenant.isPresent()
                && !tenant.get().name().equals(where.column().name())) {
            columns.add(tenant.get().name());
        }
        columns.add(where.column().name());
        return Planned.of(rule, spec(rule, where, BTREE, columns, path));
    }

    private static Planned geo(EntityIndexRule rule, String path, Located where) {
        if (where.column().kind() != PgColumnKind.GEOMETRY) {
            return Planned.refused(rule, FIELD + path + "' is not a geometry (it is " + where.column().sqlType()
                    + "): only a GeoJSON field, stored as a PostGIS geometry, takes a geo index");
        }
        if (rule.unique()) {
            return Planned.refused(rule, FIELD + path + "': a geo index cannot be unique");
        }
        String key = "(" + PgNaming.quote(where.column().name()) + "::geography)";
        return Planned.of(rule, new PgIndexSpec(name(rule, where), where.table(), "gist", List.of(key), false,
                path, null, List.of()));
    }

    private static Planned text(EntityIndexRule rule, String path, Located where) {
        PgColumn column = where.column();
        if (where.child() || !PgTypes.TEXT.equals(column.sqlType())
                || (column.kind() != PgColumnKind.SCALAR && column.kind() != PgColumnKind.ID)) {
            return Planned.refused(rule, FIELD + path + "' is not a text column of the main table: a text "
                    + "index is built on one string field of the entity");
        }
        if (rule.unique()) {
            return Planned.refused(rule, FIELD + path + "': a text index cannot be unique");
        }
        String key = PgTextSearch.indexedVector(PgNaming.quote(column.name()));
        return Planned.of(rule, new PgIndexSpec(name(rule, where), where.table(), "gin", List.of(key), false,
                path, null, List.of()));
    }

    private static PgIndexSpec spec(EntityIndexRule rule, Located where, String method, List<String> columns,
            String path) {
        return new PgIndexSpec(name(rule, where), where.table(), method,
                columns.stream().map(PgNaming::quote).toList(), rule.unique(), path,
                rule.unique() ? where.column().name() : null, columns);
    }

    private static String name(EntityIndexRule rule, Located where) {
        return PgNaming.index(where.table(), rule.name());
    }

    /**
     * Finds the column a DTO path names: on the main table, as a scalar collection's value, or as a
     * field of the elements of a POJO collection.
     */
    private static Optional<Located> locate(PgTable table, String path) {
        Optional<PgColumn> main = table.column(path);
        if (main.isPresent()) {
            return Optional.of(new Located(table.name(), main.get(), false));
        }
        Optional<PgChildTable> collection = table.child(path);
        if (collection.isPresent()) {
            return collection.get().valueColumns().stream()
                    .filter(c -> PgChildTable.VALUE.equals(c.name()))
                    .findFirst().map(c -> new Located(collection.get().name(), c, true));
        }
        for (PgChildTable child : table.allChildren()) {
            String prefix = child.dottedPath() + ".";
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                Optional<PgColumn> element = child.valueColumns().stream()
                        .filter(c -> c.kind() != PgColumnKind.PRESENCE && c.dottedPath().equals(rest)).findFirst();
                if (element.isPresent()) {
                    return Optional.of(new Located(child.name(), element.get(), true));
                }
            }
        }
        return Optional.empty();
    }
}
