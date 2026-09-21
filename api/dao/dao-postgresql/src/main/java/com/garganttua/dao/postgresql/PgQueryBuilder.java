package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Turns a DAO read request — page, filter, sort, projection — into a {@link PgQuery} for one domain.
 *
 * <p>
 * It is the relational twin of what {@code MongoDao.find} hands to the driver, and it keeps MongoDB's
 * meaning wherever SQL would quietly change it:
 * </p>
 * <ul>
 * <li>the filter is translated with MongoDB's null and array semantics ({@link PgFilterTranslator});</li>
 * <li>MongoDB orders missing values FIRST ascending (and last descending); PostgreSQL does the
 * opposite by default, hence the explicit {@code NULLS FIRST} / {@code NULLS LAST};</li>
 * <li>when a page is requested, the id is appended as a last sort key. {@code OFFSET} over an order
 * with ties is not deterministic in PostgreSQL — two requests for consecutive pages could return the
 * same row twice and skip another — while the id is unique, so pages stay disjoint.</li>
 * </ul>
 *
 * <p>
 * Sorting is limited to single-valued main-table columns: a collection has no single value to order
 * by, and a JSONB or geometry column has no order a caller could mean. Text is ordered by the
 * database collation, where MongoDB compares binary code points.
 * </p>
 */
public final class PgQueryBuilder {

    private static final Logger LOG = Logger.getLogger(PgQueryBuilder.class);

    private final PgTable table;
    private final IClass<?> dtoClass;
    private final PgFilterTranslator translator;

    /**
     * @param table    the domain's relational shape
     * @param dtoClass its DTO class — where {@code @FieldMappingRule} translates projected entity fields
     */
    public PgQueryBuilder(PgTable table, IClass<?> dtoClass) {
        this.table = table;
        this.dtoClass = dtoClass;
        this.translator = new PgFilterTranslator(table);
    }

    /**
     * Builds a read.
     *
     * @param pageable   the page: {@code limit = pageSize}, {@code offset = pageIndex * pageSize}; a page
     *                   size of 0 means no limit, as in MongoDB
     * @param filter     the filter; empty matches every row
     * @param sort       the sort; empty leaves rows unordered (ordered by id when paged)
     * @param projection the ENTITY field names to load; empty loads everything
     * @return the query
     * @throws ApiException when the filter or sort names an unknown or unsortable field, or the page is invalid
     */
    public PgQuery build(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        PgSql where = translator.translate(present(filter));
        IPageable page = present(pageable);
        Integer limit = limit(page);
        Integer offset = offset(page);
        String orderBy = orderBy(present(sort), limit != null || offset != null);
        LOG.debug("Query on {}: WHERE {} {} LIMIT {} OFFSET {}", table.name(), where.text(), orderBy, limit, offset);
        return new PgQuery(where.text(), where.params(), orderBy, limit, offset, projection(present(projection)));
    }

    /**
     * Builds the {@code WHERE} of a count.
     *
     * @param filter the filter; null counts every row
     * @return a query carrying only {@code where} and its values
     * @throws ApiException when the filter is malformed or names an unknown field
     */
    public PgQuery count(IFilter filter) throws ApiException {
        PgSql where = translator.translate(filter);
        return new PgQuery(where.text(), where.params(), "", null, null, null);
    }

    private static <T> T present(Optional<T> optional) {
        return optional == null ? null : optional.orElse(null);
    }

    private static Integer limit(IPageable page) throws ApiException {
        if (page == null) {
            return null;
        }
        if (page.getPageSize() < 0 || page.getPageIndex() < 0) {
            throw new ApiException("Invalid page: index " + page.getPageIndex() + ", size " + page.getPageSize()
                    + " — both must be zero or positive");
        }
        return page.getPageSize() == 0 ? null : page.getPageSize();
    }

    private static Integer offset(IPageable page) throws ApiException {
        if (page == null || page.getPageSize() == 0 || page.getPageIndex() == 0) {
            return null;
        }
        try {
            return Math.multiplyExact(page.getPageIndex(), page.getPageSize());
        } catch (ArithmeticException e) {
            throw new ApiException("Invalid page: index " + page.getPageIndex() + " of size " + page.getPageSize()
                    + " is beyond any addressable row", e);
        }
    }

    private String orderBy(ISort sort, boolean paged) throws ApiException {
        List<String> keys = new ArrayList<>();
        PgColumn sorted = null;
        if (sort != null) {
            sorted = sortColumn(sort.getFieldName());
            // Same test as MongoDao: anything but asc sorts descending.
            boolean ascending = sort.getDirection() == SortDirection.asc;
            keys.add(qualified(sorted) + (ascending ? " ASC NULLS FIRST" : " DESC NULLS LAST"));
        }
        if (paged && (sorted == null || sorted.kind() != PgColumnKind.ID)) {
            keys.add(qualified(table.id()) + " ASC");
        }
        return keys.isEmpty() ? "" : "ORDER BY " + String.join(", ", keys);
    }

    private PgColumn sortColumn(String fieldName) throws ApiException {
        Optional<PgColumn> column = fieldName == null ? Optional.empty() : table.column(fieldName);
        if (column.isEmpty()) {
            throw new ApiException("Cannot sort domain '" + table.name() + "' on '" + fieldName
                    + "': it is not a single-valued field of the domain (unknown, a collection, or a path"
                    + " inside a JSON field). Sort on a scalar DTO field path, e.g. 'createdAt' or 'address.city'.");
        }
        PgColumnKind kind = column.get().kind();
        if (kind == PgColumnKind.JSONB || kind == PgColumnKind.IKEY || kind == PgColumnKind.GEOMETRY) {
            throw new ApiException("Cannot sort domain '" + table.name() + "' on '" + fieldName + "': it is stored as "
                    + column.get().sqlType() + ", which has no meaningful order. Sort on a scalar field.");
        }
        return column.get();
    }

    private static String qualified(PgColumn column) {
        return PgQuery.ALIAS + "." + PgNaming.quote(column.name());
    }

    /**
     * Entity field names to DTO field paths, like {@code MongoDao}: the first segment is translated
     * through {@code @FieldMappingRule}, the rest of a dotted path is kept.
     */
    private List<String> projection(List<String> entityFields) {
        if (entityFields == null || entityFields.isEmpty()) {
            return null;
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String entityField : entityFields) {
            if (entityField == null || entityField.isBlank()) {
                continue;
            }
            String trimmed = entityField.trim();
            int dot = trimmed.indexOf('.');
            String head = dot < 0 ? trimmed : trimmed.substring(0, dot);
            fields.add(translateToDtoField(head.trim()) + (dot < 0 ? "" : trimmed.substring(dot)));
        }
        return fields.isEmpty() ? null : new ArrayList<>(fields);
    }

    /** The DTO field whose {@code @FieldMappingRule} reads the entity field; the same name otherwise. */
    private String translateToDtoField(String entityField) {
        IClass<?> current = dtoClass;
        while (current != null) {
            for (IField field : current.getDeclaredFields()) {
                for (FieldMappingRule rule : field.getAnnotationsByType(IClass.getClass(FieldMappingRule.class))) {
                    if (entityField.equals(rule.sourceFieldAddress())) {
                        return field.getName();
                    }
                }
            }
            current = current.getSuperclass();
        }
        return entityField;
    }
}
