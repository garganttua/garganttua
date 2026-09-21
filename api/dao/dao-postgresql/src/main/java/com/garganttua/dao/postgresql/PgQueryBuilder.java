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
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
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
 * <li>the sort follows BSON order — missing first, binary text, NaN below numbers, arrays by their
 * extreme element, sub-documents field by field, unknown fields as no key ({@link PgSortClause});</li>
 * <li>when a page is requested, the uuid is appended as a last sort key, so pages over a non-unique
 * key are stable and complementary; a negative index or size is refused, and the offset is computed
 * in {@code long} — a page far beyond the data is simply empty;</li>
 * <li>a projected dotted path ({@code address.city}) projects its HEAD field, as the MongoDB DAO
 * does: MongoDB then returns the whole sub-document.</li>
 * </ul>
 */
public final class PgQueryBuilder {

    private static final Logger LOG = Logger.getLogger(PgQueryBuilder.class);

    private final PgTable table;
    private final IClass<?> dtoClass;
    private final PgFilterTranslator translator;
    private final PgSortClause sorts;

    /**
     * @param table    the domain's relational shape
     * @param dtoClass its DTO class — where {@code @FieldMappingRule} translates projected entity fields
     */
    public PgQueryBuilder(PgTable table, IClass<?> dtoClass) {
        this.table = table;
        this.dtoClass = dtoClass;
        this.translator = new PgFilterTranslator(table);
        this.sorts = new PgSortClause(table);
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
        Long offset = offset(page);
        String orderBy = sorts.orderBy(present(sort), page != null);
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

    /** {@code index * size} in long arithmetic: an int product would wrap to a negative skip. */
    private static Long offset(IPageable page) {
        if (page == null || page.getPageSize() == 0 || page.getPageIndex() == 0) {
            return null;
        }
        return (long) page.getPageIndex() * page.getPageSize();
    }

    /**
     * Entity field names to the DTO fields to load, like {@code MongoDao.applyProjection}: a dotted path
     * keeps only its first segment (MongoDB then returns the whole sub-document), translated through
     * {@code @FieldMappingRule}. An unknown name selects nothing — only the identity comes back.
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
            fields.add(translateToDtoField((dot < 0 ? trimmed : trimmed.substring(0, dot)).trim()));
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
