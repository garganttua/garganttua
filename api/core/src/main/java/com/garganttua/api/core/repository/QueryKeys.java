package com.garganttua.api.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;

/**
 * The cache key of a search: its kind, its whole filter (the access predicates the pipeline added included), its
 * page, its sort and its projection, written in one canonical form. Two searches with the same key return the same
 * rows; values carry their type ({@code Integer:1} is not {@code String:1}).
 */
final class QueryKeys {

    private QueryKeys() {
    }

    static String of(String kind, Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) {
        StringBuilder key = new StringBuilder(kind).append('|');
        appendFilter(key, filter == null ? null : filter.orElse(null));
        key.append('|');
        if (pageable != null && pageable.isPresent()) {
            key.append(pageable.get().getPageIndex()).append('/').append(pageable.get().getPageSize());
        }
        key.append('|');
        if (sort != null && sort.isPresent()) {
            key.append(sort.get().getFieldName()).append(':').append(sort.get().getDirection());
        }
        key.append('|');
        if (projection != null && projection.isPresent()) {
            key.append(String.join(",", projection.get()));
        }
        return key.toString();
    }

    private static void appendFilter(StringBuilder key, IFilter filter) {
        if (filter == null) {
            key.append('-');
            return;
        }
        key.append(filter.getName()).append('(').append(value(filter.getValue())).append(')');
        List<IFilter> children = filter.getFilters();
        if (children != null && !children.isEmpty()) {
            key.append('[');
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    key.append(',');
                }
                appendFilter(key, children.get(i));
            }
            key.append(']');
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(QueryKeys::value).collect(Collectors.joining(";", "[", "]"));
        }
        return value.getClass().getSimpleName() + ':' + value;
    }
}
