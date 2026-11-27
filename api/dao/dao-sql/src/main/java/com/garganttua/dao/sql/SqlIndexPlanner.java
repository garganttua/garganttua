package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * Turns the index rules a domain declares into the indexes its tables need — what to index, never how a database
 * spells it.
 *
 * <p>
 * A rule names a DTO field; it is located in the table model (a main-table column, a collection's element column in
 * its child table, or a field of an element) and planned as a {@link SqlIndex}: its table, its kind, the columns it
 * covers in order — the tenant column first for a uniqueness scoped to the tenant — and whether it is unique. A rule
 * that cannot be honoured (a field that maps no column, a uniqueness inside a child table, a geo index on a
 * non-geometry, a second text index…) is planned as a refusal that says why: the DAO reports it, and refuses to start
 * when told to be strict.
 * </p>
 */
public final class SqlIndexPlanner {

    private static final String FIELD = "field '";

    /** What an index covers, by kind. */
    public enum Kind {
        /** An ordinary ordered index on one or more columns. */
        STANDARD,
        /** A spatial index on a geometry column. */
        GEO,
        /** A full-text index on a text column. */
        TEXT
    }

    /**
     * One planned index.
     *
     * @param name            its name — unique in the database, derived from the table and the rule
     * @param table           the table it is built on
     * @param kind            what it covers
     * @param columns         the columns it covers, in order, unquoted
     * @param unique          whether it refuses two rows with the same values
     * @param field           the declared field, for messages
     * @param predicateColumn the column a unique index is restricted to the non-null rows of, or null
     */
    public record SqlIndex(String name, String table, Kind kind, List<String> columns, boolean unique, String field,
            String predicateColumn) {

        /** Copies the columns. */
        public SqlIndex {
            columns = List.copyOf(columns);
        }
    }

    /**
     * A rule and what became of it.
     *
     * @param rule    the declared rule
     * @param index   the planned index, or empty when refused
     * @param refusal why it was refused, or null
     */
    public record Planned(EntityIndexRule rule, Optional<SqlIndex> index, String refusal) {

        static Planned of(EntityIndexRule rule, SqlIndex index) {
            return new Planned(rule, Optional.of(index), null);
        }

        static Planned refused(EntityIndexRule rule, String refusal) {
            return new Planned(rule, Optional.empty(), refusal);
        }
    }

    private record Located(String table, SqlColumn column, boolean child) {
    }

    private SqlIndexPlanner() {
    }

    /**
     * The indexes a domain declares, plus the tenant index a multi-tenant domain needs for every query it runs.
     *
     * @param table            the domain's table model
     * @param domainDefinition the domain
     * @param dto              its DTO
     * @return the plans, in declaration order — empty when the domain declares none
     */
    @SuppressWarnings("rawtypes")
    public static List<Planned> plan(SqlTable table, IDomainDefinition domainDefinition, IDtoDefinition<?> dto) {
        IEntityDefinition<?> entity = domainDefinition.entityDefinition();
        if (entity == null) {
            return List.of();
        }
        List<EntityIndexRule> rules = new ArrayList<>(entity.indexes());
        ObjectAddress tenant = entity.tenantId();
        if (tenant != null && !tenant.equals(entity.uuid())
                && rules.stream().noneMatch(rule -> rule != null && rule.field().equals(tenant))) {
            rules.add(EntityIndexRule.of(tenant, false, UnicityScope.system));
        }
        if (rules.isEmpty()) {
            return List.of();
        }
        IClass<?> dtoClass = dto.dtoClass();
        return plan(table, rules, path -> {
            int dot = path.indexOf('.');
            return dot < 0 ? SqlQueryBuilder.translateToDtoField(dtoClass, path)
                    : SqlQueryBuilder.translateToDtoField(dtoClass, path.substring(0, dot)) + path.substring(dot);
        }, dto.tenantId() == null ? null : dto.tenantId().toString());
    }

    /**
     * Plans the given rules.
     *
     * @param table      the table model
     * @param rules      the rules, as declared
     * @param toDtoPath  maps an entity field path onto the DTO field path the table model is built from
     * @param tenantPath the DTO path of the tenant field, or null when the domain has none
     * @return one plan per non-null rule, in order
     */
    public static List<Planned> plan(SqlTable table, List<EntityIndexRule> rules, UnaryOperator<String> toDtoPath,
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
            textPlanned |= rule.kind() == IndexKind.text && planned.index().isPresent();
            plans.add(planned);
        }
        return plans;
    }

    /**
     * The column {@code $text} searches: that of the first planned text index, if any.
     *
     * @param table the table model
     * @param plans the plans
     * @return the column, or empty when no text index is planned
     */
    public static Optional<SqlColumn> textColumn(SqlTable table, List<Planned> plans) {
        return plans.stream().filter(p -> p.rule().kind() == IndexKind.text && p.index().isPresent())
                .findFirst().flatMap(p -> table.column(p.index().get().field()));
    }

    private static Planned planOne(SqlTable table, EntityIndexRule rule, String path, String tenantPath) {
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

    private static Planned standard(SqlTable table, EntityIndexRule rule, String path, Located where,
            String tenantPath) {
        SqlColumnKind kind = where.column().kind();
        if (kind == SqlColumnKind.GEOMETRY || kind == SqlColumnKind.IKEY) {
            return Planned.refused(rule, FIELD + path + "' is a " + kind.name().toLowerCase(Locale.ROOT)
                    + " column, which a standard index does not serve" + (kind == SqlColumnKind.GEOMETRY
                            ? ": declare it with IndexKind.geo" : ""));
        }
        if (where.child()) {
            if (rule.unique()) {
                return Planned.refused(rule, FIELD + path + "' is held in child table '" + where.table()
                        + "', one row per element: a unique index there would refuse the same value in two "
                        + "elements of ONE entity, not in two entities — " + table.dialect().name()
                        + " cannot hold this uniqueness");
            }
            return Planned.of(rule, index(rule, where, Kind.STANDARD, List.of(where.column().name()), path));
        }
        List<String> columns = new ArrayList<>();
        Optional<SqlColumn> tenant = tenantPath == null ? Optional.empty() : table.column(tenantPath);
        if (rule.scope() == UnicityScope.tenant && tenant.isPresent()
                && !tenant.get().name().equals(where.column().name())) {
            columns.add(tenant.get().name());
        }
        columns.add(where.column().name());
        return Planned.of(rule, index(rule, where, Kind.STANDARD, columns, path));
    }

    private static Planned geo(EntityIndexRule rule, String path, Located where) {
        if (where.column().kind() != SqlColumnKind.GEOMETRY) {
            return Planned.refused(rule, FIELD + path + "' is not a geometry (it is " + where.column().sqlType()
                    + "): only a GeoJSON field takes a geo index");
        }
        if (rule.unique()) {
            return Planned.refused(rule, FIELD + path + "': a geo index cannot be unique");
        }
        return Planned.of(rule, new SqlIndex(name(rule, where), where.table(), Kind.GEO,
                List.of(where.column().name()), false, path, null));
    }

    private static Planned text(EntityIndexRule rule, String path, Located where) {
        SqlColumn column = where.column();
        if (where.child() || !SqlTypes.TEXT.equals(column.sqlType())
                || (column.kind() != SqlColumnKind.SCALAR && column.kind() != SqlColumnKind.ID)) {
            return Planned.refused(rule, FIELD + path + "' is not a text column of the main table: a text "
                    + "index is built on one string field of the entity");
        }
        if (rule.unique()) {
            return Planned.refused(rule, FIELD + path + "': a text index cannot be unique");
        }
        return Planned.of(rule, new SqlIndex(name(rule, where), where.table(), Kind.TEXT, List.of(column.name()),
                false, path, null));
    }

    private static SqlIndex index(EntityIndexRule rule, Located where, Kind kind, List<String> columns, String path) {
        return new SqlIndex(name(rule, where), where.table(), kind, columns, rule.unique(), path,
                rule.unique() ? where.column().name() : null);
    }

    private static String name(EntityIndexRule rule, Located where) {
        return SqlNaming.index(where.table(), rule.name());
    }

    private static Optional<Located> locate(SqlTable table, String path) {
        Optional<SqlColumn> main = table.column(path);
        if (main.isPresent()) {
            return Optional.of(new Located(table.name(), main.get(), false));
        }
        Optional<SqlChildTable> collection = table.child(path);
        if (collection.isPresent()) {
            return collection.get().valueColumns().stream()
                    .filter(c -> SqlChildTable.VALUE.equals(c.name()))
                    .findFirst().map(c -> new Located(collection.get().name(), c, true));
        }
        for (SqlChildTable child : table.allChildren()) {
            String prefix = child.dottedPath() + ".";
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                Optional<SqlColumn> element = child.valueColumns().stream()
                        .filter(c -> c.kind() != SqlColumnKind.PRESENCE && c.dottedPath().equals(rest)).findFirst();
                if (element.isPresent()) {
                    return Optional.of(new Located(child.name(), element.get(), true));
                }
            }
        }
        return Optional.empty();
    }
}
