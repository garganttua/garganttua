package com.garganttua.dao.postgresql;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.dao.sql.SqlIndexPlanner;
import com.garganttua.dao.sql.SqlIndexPlanner.SqlIndex;
import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlNaming;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * The PostgreSQL spelling of the indexes {@link SqlIndexPlanner} plans: a B-tree for a standard index, a GiST on the
 * geography for a geo index, a GIN on the text vector {@link PgTextSearch} searches for a text index.
 */
final class PgIndexPlanner {

    /**
     * A rule and what became of it, in PostgreSQL terms.
     *
     * @param rule    the declared rule
     * @param spec    the index to create, or empty when refused
     * @param refusal why it was refused, or null
     */
    record Planned(EntityIndexRule rule, Optional<PgIndexSpec> spec, String refusal) {
    }

    private PgIndexPlanner() {
    }

    /** The indexes a domain declares, with its tenant index. */
    @SuppressWarnings("rawtypes")
    static List<Planned> plan(SqlTable table, IDomainDefinition domainDefinition, IDtoDefinition<?> dto) {
        return spelled(SqlIndexPlanner.plan(table, domainDefinition, dto));
    }

    /** The given rules, planned. */
    static List<Planned> plan(SqlTable table, List<EntityIndexRule> rules, UnaryOperator<String> toDtoPath,
            String tenantPath) {
        return spelled(SqlIndexPlanner.plan(table, rules, toDtoPath, tenantPath));
    }

    static Optional<SqlColumn> textColumn(SqlTable table, List<Planned> plans) {
        return plans.stream().filter(p -> p.spec().isPresent() && "gin".equals(p.spec().get().method()))
                .findFirst().flatMap(p -> table.column(p.spec().get().field()));
    }

    private static List<Planned> spelled(List<SqlIndexPlanner.Planned> plans) {
        return plans.stream().map(p -> new Planned(p.rule(), p.index().map(PgIndexPlanner::spec), p.refusal()))
                .toList();
    }

    private static PgIndexSpec spec(SqlIndex index) {
        String column = SqlNaming.quote(index.columns().get(0));
        return switch (index.kind()) {
            case STANDARD -> new PgIndexSpec(index.name(), index.table(), "btree",
                    index.columns().stream().map(SqlNaming::quote).toList(), index.unique(), index.field(),
                    index.predicateColumn(), index.columns());
            case GEO -> new PgIndexSpec(index.name(), index.table(), "gist", List.of("(" + column + "::geography)"),
                    false, index.field(), null, List.of());
            case TEXT -> new PgIndexSpec(index.name(), index.table(), "gin",
                    List.of(PgTextSearch.indexedVector(column)), false, index.field(), null, List.of());
        };
    }
}
