package com.garganttua.api.core.repository;

import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.filter.FilterMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.javatuples.Pair;

import com.garganttua.api.core.repository.RepositoryException;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.cache.ICache;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.filter.IFilterMapper;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.core.mapper.IMapper;
import com.garganttua.core.mapper.MapperException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

import com.garganttua.core.observability.Logger;

public class Repository implements IRepository {
	private static final Logger log = Logger.getLogger(Repository.class);


    private final List<IDtoContext<?>> dtoContexts;
    private final IClass<?> entityClass;
    private static final IReflection REFLECTION = DefaultMapper.reflection();
    private final IMapper mapper = DefaultMapper.mapper();
    private final IFilterMapper filterMapper = new FilterMapper();

    private IDomain<?> domainContext;
    private final Object domainContextLock = new Object();

    /** The DTOs of an entity by its uuid, when the domain caches its reads; null when it does not. */
    private volatile ICache<String, List<Object>> cache;

    /**
     * Reads by uuid through {@code byUuid} from now on. It holds DTOs, not entities: each read maps a fresh entity,
     * so what a request does to its entity never reaches the cache.
     */
    public void useCache(ICache<String, List<Object>> byUuid) {
        this.cache = Objects.requireNonNull(byUuid, "Cache cannot be null");
    }

    /** The results of searches by query, when the domain caches them; null when it does not. */
    private volatile ICache<String, Object> queryCache;

    /**
     * Caches search results from now on, keyed by the whole query — access filter included — and emptied by any
     * write. It holds DTOs, mapped to fresh entities on each hit, like the cache by uuid.
     */
    public void useQueryCache(ICache<String, Object> byQuery) {
        this.queryCache = Objects.requireNonNull(byQuery, "Query cache cannot be null");
    }

    /** The DTOs and total of a cached page. */
    private record CachedPage(long total, Map<String, Object> dtos) {
    }

    public Repository(List<IDtoContext<?>> dtoContexts, IClass<?> entityClass) {
        this.dtoContexts = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(dtoContexts, "Dto contexts cannot be null")));
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class cannot be null");
    }

    public void setDomain(IDomain<?> domainContext) {
        synchronized (domainContextLock) {
            this.domainContext = Objects.requireNonNull(domainContext, "Domain context cannot be null");
        }
    }

    private IDomain<?> getDomain() {
        synchronized (domainContextLock) {
            return this.domainContext;
        }
    }

    // --- Read operations ---
    
    @Override
    public List<Object> getEntities(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
            throws ApiException {
        return getEntities(pageable, filter, sort, Optional.empty());
    }

    @Override
    public List<Object> getEntities(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        log.debug("Fetching entities with filter={} projection={}", filter.orElse(null), projection.orElse(null));
        ICache<String, Object> byQuery = this.queryCache;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dtoMaps = byQuery == null ? queryAllDtos(pageable, filter, sort, projection)
                : (List<Map<String, Object>>) byQuery.get(QueryKeys.of("list", pageable, filter, sort, projection),
                        () -> List.copyOf(queryAllDtos(pageable, filter, sort, projection)));
        return mergeAndMapToEntities(dtoMaps);
    }

    /**
     * A page and the total its filter matches, in ONE call to the DAO when the domain has one DTO — the
     * case of every domain the starters build. The filter is mapped to the DTO's fields as for the read,
     * so the total counts exactly what the page is cut from (the separate count used to receive the
     * ENTITY filter).
     */
    @Override
    public Page getEntitiesPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) throws ApiException {
        if (pageable == null || pageable.isEmpty() || this.dtoContexts.size() != 1) {
            return IRepository.super.getEntitiesPage(pageable, filter, sort, projection);
        }
        ICache<String, Object> byQuery = this.queryCache;
        CachedPage page = byQuery == null ? findPage(pageable, filter, sort, projection)
                : (CachedPage) byQuery.get(QueryKeys.of("page", pageable, filter, sort, projection),
                        () -> findPage(pageable, filter, sort, projection));
        return new Page(page.total(), mergeAndMapToEntities(List.of(page.dtos())));
    }

    private CachedPage findPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) {
        IDtoContext<?> dtoContext = this.dtoContexts.get(0);
        Optional<IFilter> mapped = mappedFilter(dtoContext, filter);
        Page page = dtoContext.findPage(pageable, mapped, sort, projection);
        Map<String, Object> dtos = new LinkedHashMap<>();
        for (Object dto : page.entities()) {
            dtos.put(dtoContext.getUuid(dto), dto);
        }
        return new CachedPage(page.totalCount(), dtos);
    }

    /** The filter as the read of one DTO context receives it: mapped to its fields when the domain is known. */
    private Optional<IFilter> mappedFilter(IDtoContext<?> dtoContext, Optional<IFilter> filter) throws ApiException {
        IDomain<?> dc = getDomain();
        if (dc == null) {
            return filter;
        }
        for (Pair<IClass<?>, IFilter> mapped : this.filterMapper.map(dc.getDomainDefinition(), filter.orElse(null))) {
            if (dtoContext.equals(findDtoContextByClass(mapped.getValue0()))) {
                return Optional.ofNullable(mapped.getValue1());
            }
        }
        return filter;
    }

    // --- Write operations ---

    @Override
    public void save(Object entity) throws ApiException {
        Objects.requireNonNull(entity, "Entity cannot be null");
        log.debug("Saving entity of type {}", entity.getClass().getSimpleName());

        try {
            for (IDtoContext<?> dtoContext : dtoContexts) {
                Object dto = mapEntityToDto(entity, dtoContext.getDtoDefinition());
                dtoContext.save(dto);
            }
        } finally {
            // Even a write that failed half-way may have changed the store: the cached copy is no longer vouched for.
            invalidateCached(entity);
        }
    }

    @Override
    public void delete(Object entity) throws ApiException {
        Objects.requireNonNull(entity, "Entity cannot be null");
        log.debug("Deleting entity of type {}", entity.getClass().getSimpleName());

        try {
            for (IDtoContext<?> dtoContext : dtoContexts) {
                Object dto = mapEntityToDto(entity, dtoContext.getDtoDefinition());
                dtoContext.delete(dto);
            }
        } finally {
            invalidateCached(entity);
        }
    }

    private void invalidateCached(Object entity) {
        ICache<String, Object> byQuery = this.queryCache;
        if (byQuery != null) {
            // Any write may move any row in or out of any search: they are all forgotten.
            byQuery.invalidateAll();
        }
        ICache<String, List<Object>> byUuid = this.cache;
        if (byUuid == null) {
            return;
        }
        String uuid = extractUuidFromEntity(entity);
        if (uuid != null) {
            byUuid.invalidate(uuid);
        }
    }

    // --- Existence checks ---

    @Override
    public boolean doesExist(Object entity) throws ApiException {
        Objects.requireNonNull(entity, "Entity cannot be null");
        String uuid = extractUuidFromEntity(entity);
        return doesExist(uuid);
    }

    @Override
    public Optional<Object> getByUuid(String uuid) throws ApiException {
        Objects.requireNonNull(uuid, "UUID cannot be null");
        ICache<String, List<Object>> byUuid = this.cache;
        List<Object> dtos = byUuid == null ? findDtosByUuid(uuid) : byUuid.get(uuid, () -> findDtosByUuid(uuid));
        return dtos == null ? Optional.empty() : Optional.ofNullable(mapDtosToEntity(dtos));
    }

    /** {@return the DTOs of the entity {@code uuid} — one per DTO context holding it} {@code null} when none. */
    private List<Object> findDtosByUuid(String uuid) {
        List<Map<String, Object>> dtoMaps = new ArrayList<>();
        for (IDtoContext<?> dtoContext : dtoContexts) {
            ObjectAddress uuidField = dtoContext.getDtoDefinition().uuid();
            if (uuidField != null) {
                dtoMaps.add(queryDtoContext(dtoContext, Optional.empty(),
                        Optional.of(Filter.eq(uuidField.toString(), uuid)), Optional.empty(), Optional.empty()));
            }
        }
        Map<String, List<Object>> merged = mergeMaps(dtoMaps, false);
        return merged.isEmpty() ? null : List.copyOf(merged.values().iterator().next());
    }

    @Override
    public boolean doesExist(String uuid) throws ApiException {
        Objects.requireNonNull(uuid, "UUID cannot be null");
        return findOneByField(ctx -> ctx.getDtoDefinition().uuid(), uuid).isPresent();
    }

    // --- Count ---

    @Override
    public long getCount(IFilter filter) throws ApiException {
        if (dtoContexts.isEmpty()) {
            return 0;
        }
        ICache<String, Object> byQuery = this.queryCache;
        if (byQuery == null) {
            return dtoContexts.get(0).count(filter);
        }
        return (Long) byQuery.get(QueryKeys.of("count", Optional.empty(), Optional.ofNullable(filter), Optional.empty(),
                Optional.empty()), () -> dtoContexts.get(0).count(filter));
    }

    // --- Internal: querying ---

    private List<Map<String, Object>> queryAllDtos(Optional<IPageable> pageable, Optional<IFilter> filter,
            Optional<ISort> sort, Optional<List<String>> projection) throws ApiException {
        IDomain<?> dc = getDomain();
        if (dc != null) {
            return queryWithFilterMapping(dc, pageable, filter, sort, projection);
        }
        return dtoContexts.stream()
                .map(ctx -> queryDtoContext(ctx, pageable, filter, sort, projection))
                .toList();
    }

    private List<Map<String, Object>> queryWithFilterMapping(IDomain<?> dc, Optional<IPageable> pageable,
            Optional<IFilter> filter, Optional<ISort> sort, Optional<List<String>> projection) throws ApiException {
        IDomainDefinition<?> definition = dc.getDomainDefinition();
        List<Pair<IClass<?>, IFilter>> mappedFilters = filterMapper.map(definition, filter.orElse(null));

        List<Map<String, Object>> dtoMaps = new ArrayList<>();
        for (Pair<IClass<?>, IFilter> mapped : mappedFilters) {
            IDtoContext<?> dtoContext = findDtoContextByClass(mapped.getValue0());
            if (dtoContext != null) {
                dtoMaps.add(queryDtoContext(dtoContext, pageable, Optional.ofNullable(mapped.getValue1()), sort, projection));
            }
        }
        return dtoMaps;
    }

    private Optional<Object> findOneByField(FieldAddressExtractor extractor, String value) throws ApiException {
        List<Map<String, Object>> dtoMaps = new ArrayList<>();
        for (IDtoContext<?> dtoContext : dtoContexts) {
            ObjectAddress fieldAddress = extractor.extract(dtoContext);
            if (fieldAddress == null) {
                continue;
            }
            IFilter filter = Filter.eq(fieldAddress.toString(), value);
            dtoMaps.add(queryDtoContext(dtoContext, Optional.empty(), Optional.of(filter), Optional.empty(), Optional.empty()));
        }

        Map<String, List<Object>> merged = mergeMaps(dtoMaps, false);
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        List<Object> dtos = merged.values().iterator().next();
        return Optional.ofNullable(mapDtosToEntity(dtos));
    }

    private Map<String, Object> queryDtoContext(IDtoContext<?> dtoContext, Optional<IPageable> pageable,
            Optional<IFilter> filter, Optional<ISort> sort, Optional<List<String>> projection) throws ApiException {
        // Insertion-ordered: the DAO answers in the read's order (sort, $near distance, page) and the
        // entities must come out in that order — a HashMap keyed by uuid used to shuffle them.
        Map<String, Object> map = new LinkedHashMap<>();
        // Use the 3-arg find for the common no-projection path so DAOs/contexts that only implement
        // it are unaffected; the projecting 4-arg variant is reached only when fields were requested.
        boolean hasProjection = projection != null && projection.isPresent() && !projection.get().isEmpty();
        List<Object> dtos = hasProjection
                ? dtoContext.find(pageable, filter, sort, projection)
                : dtoContext.find(pageable, filter, sort);
        for (Object dto : dtos) {
            String uuid = dtoContext.getUuid(dto);
            map.put(uuid, dto);
        }
        return map;
    }

    // --- Internal: mapping ---

    private List<Object> mergeAndMapToEntities(List<Map<String, Object>> dtoMaps) throws ApiException {
        Map<String, List<Object>> merged = mergeMaps(dtoMaps, false);
        return merged.values().stream()
                .map(this::mapDtosToEntity)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private Object mapDtosToEntity(List<Object> dtoList) {
        Object entity = null;
        for (Object dto : dtoList) {
            try {
                entity = (entity == null)
                        ? mapper.map(dto, entityClass)
                        : mapper.map(dto, entity);
            } catch (MapperException e) {
                log.error("Mapping failed for DTO {}", dto.getClass().getSimpleName(), e);
                return null;
            }
        }
        return entity;
    }

    private Object mapEntityToDto(Object entity, IDtoDefinition<?> dtoDefinition) throws ApiException {
        try {
            return mapper.map(entity, dtoDefinition.dtoClass());
        } catch (MapperException e) {
            throw new RepositoryException(
                    "Failed to map entity to DTO " + dtoDefinition.dtoClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private String extractUuidFromEntity(Object entity) throws ApiException {
        IDomain<?> dc = getDomain();
        if (dc == null) {
            throw new RepositoryException("Domain context not set, cannot extract UUID from entity");
        }
        ObjectAddress uuidAddress = dc.getEntityDefinition().uuid();
        try {
            Object value = REFLECTION.getFieldValue(entity, uuidAddress.toString());
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            throw new RepositoryException("Failed to extract UUID from entity: " + e.getMessage(), e);
        }
    }

    // --- Internal: lookup ---

    private IDtoContext<?> findDtoContextByClass(IClass<?> dtoClass) {
        for (IDtoContext<?> ctx : dtoContexts) {
            if (ctx.getDtoDefinition().dtoClass().equals(dtoClass)) {
                return ctx;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface FieldAddressExtractor {
        ObjectAddress extract(IDtoContext<?> dtoContext);
    }

    // --- Internal: merge ---

    public static Map<String, List<Object>> mergeMaps(List<Map<String, Object>> maps, boolean strict) throws ApiException {
        // Insertion-ordered, for the same reason: the first DTO context's order is the read's order.
        Map<String, List<Object>> result = new LinkedHashMap<>();
        maps.forEach(map -> map.forEach((key, value) ->
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(value)));

        if (strict && !result.isEmpty()) {
            int expectedSize = result.values().iterator().next().size();
            result.forEach((key, list) -> {
                if (list.size() != expectedSize) {
                    throw new RepositoryException(
                            String.format("Key '%s' has %d DTOs, expected %d", key, list.size(), expectedSize));
                }
            });
        }
        return result;
    }
}
