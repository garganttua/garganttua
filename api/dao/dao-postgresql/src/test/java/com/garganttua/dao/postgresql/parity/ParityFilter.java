package com.garganttua.dao.postgresql.parity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.sort.SortDirection;

/**
 * Filters, sorts and pages for the parity tests — the SAME objects are handed to both DAOs.
 *
 * <p>
 * The shape mirrors what the api builds from a JSON filter ({@code ProtocolFilterJson}): a
 * {@code $field} node whose value is the field name and whose single child is the comparison; list
 * operators carry one {@code $value} child per element; logical nodes carry their operands.
 * </p>
 */
public final class ParityFilter implements IFilter {

    private final String name;
    private Object value;
    private List<IFilter> filters;

    private ParityFilter(String name, Object value, List<IFilter> filters) {
        this.name = name;
        this.value = value;
        this.filters = new ArrayList<>(filters);
    }

    /** {@code {field: {op: value}}}. */
    public static IFilter field(String field, String op, Object value) {
        return new ParityFilter("$field", field, List.of(new ParityFilter(op, value, List.of())));
    }

    /** {@code {field: {op: [values…]}}} — for {@code $in} and {@code $nin}. */
    public static IFilter listed(String field, String op, Object... values) {
        List<IFilter> items = Arrays.stream(values).map(v -> (IFilter) new ParityFilter("$value", v, List.of())).toList();
        return new ParityFilter("$field", field, List.of(new ParityFilter(op, null, items)));
    }

    /** {@code {$and|$or|$nor: [subs…]}}. */
    public static IFilter logical(String op, IFilter... subs) {
        return new ParityFilter(op, null, List.of(subs));
    }

    /** A page. */
    public static IPageable page(int index, int size) {
        return new IPageable() {
            @Override
            public int getPageIndex() {
                return index;
            }

            @Override
            public int getPageSize() {
                return size;
            }
        };
    }

    /** A sort on one field. */
    public static ISort sort(String field, SortDirection direction) {
        return new ISort() {
            @Override
            public String getFieldName() {
                return field;
            }

            @Override
            public SortDirection getDirection() {
                return direction;
            }
        };
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public IFilter clone() {
        return new ParityFilter(name, value, filters);
    }

    @Override
    public List<IFilter> getFilters() {
        return filters;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setFilters(List<IFilter> valuesFilters) {
        this.filters = new ArrayList<>(valuesFilters);
    }

    @Override
    public void removeSubFilter(IFilter filter) {
        this.filters.remove(filter);
    }

    @Override
    public void replaceSubFilter(IFilter literal, IFilter mappedFilter) {
        int at = this.filters.indexOf(literal);
        if (at >= 0) {
            this.filters.set(at, mappedFilter);
        }
    }

    @Override
    public String toString() {
        return "$field".equals(name) ? value + ":" + filters : name + (value == null ? "" : "=" + value) + filters;
    }
}
