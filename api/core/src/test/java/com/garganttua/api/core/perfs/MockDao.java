package com.garganttua.api.core.perfs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest.UserDto;

/**
 * A repository that costs (almost) nothing, so that what a performance test measures belongs to the
 * framework.
 *
 * <p>
 * The {@code CapturingDao} of the integration tests evaluates filters by reflection over a list —
 * faithful, and far too slow to sit under a measurement: on a thousand rows its own scans would
 * outweigh the pipeline. This one answers the only lookup the pipeline makes on its own, by uuid,
 * from a hash map, and returns every other read as the stored rows in id order, sliced by
 * the page. Filters and sorts are received and ignored: translating them is the framework's work and
 * is measured; applying them is the database's, and is not.
 * </p>
 *
 * <p>
 * Thread-safe, for the concurrency measurements. It counts what it is asked, so a test can check
 * that a run did the work it claims to have timed.
 * </p>
 */
final class MockDao implements IDao {

    private final Map<String, UserDto> byUuid = new ConcurrentHashMap<>();
    private volatile List<Object> snapshot = List.of();
    private volatile boolean dirty;
    private final AtomicLong finds = new AtomicLong();
    private final AtomicLong saves = new AtomicLong();
    private final AtomicLong deletes = new AtomicLong();

    @Override
    public void registerDomain(IDomainDefinition<?> domainDefinition) {
        // nothing to prepare
    }

    /** Stores {@code count} users {@code uuid-0 … uuid-(count-1)} in the super tenant. */
    MockDao seed(int count) {
        for (int i = 0; i < count; i++) {
            UserDto dto = new UserDto();
            dto.setId(String.valueOf(i));
            dto.setUuid("uuid-" + i);
            dto.setTenantId("SUPER_TENANT");
            dto.setName("user-" + i);
            dto.setEmail("user-" + i + "@example.com");
            this.byUuid.put(dto.getUuid(), dto);
        }
        refreshSnapshot();
        return this;
    }

    /** Empties the store — a create loop calls it so the store does not grow under the measure. */
    void clear() {
        this.byUuid.clear();
        this.dirty = true;
    }

    int size() {
        return this.byUuid.size();
    }

    long finds() {
        return this.finds.get();
    }

    long saves() {
        return this.saves.get();
    }

    long deletes() {
        return this.deletes.get();
    }

    private synchronized void refreshSnapshot() {
        this.dirty = false;
        List<Object> rows = new ArrayList<>(this.byUuid.values());
        rows.sort((a, b) -> Integer.compare(idOf(a), idOf(b)));
        this.snapshot = List.copyOf(rows);
    }

    private static int idOf(Object row) {
        try {
            return Integer.parseInt(((UserDto) row).getId());
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
            throws ApiException {
        this.finds.incrementAndGet();
        Optional<String> uuid = filter.flatMap(MockDao::uuidOf);
        if (uuid.isPresent()) {
            UserDto row = this.byUuid.get(uuid.get());
            return row == null ? new ArrayList<>() : new ArrayList<>(List.of(row));
        }
        if (this.dirty) {
            refreshSnapshot();
        }
        List<Object> rows = this.snapshot;
        if (pageable.isPresent()) {
            IPageable p = pageable.get();
            int from = Math.min(Math.max(p.getPageIndex(), 0) * p.getPageSize(), rows.size());
            int to = Math.min(from + p.getPageSize(), rows.size());
            return new ArrayList<>(rows.subList(from, to));
        }
        return new ArrayList<>(rows);
    }

    /**
     * The uuid an equality on {@code uuid} asks for, at the top of the filter or under an
     * {@code $and}; empty for any other filter.
     */
    private static Optional<String> uuidOf(IFilter filter) {
        if ("$field".equals(filter.getName()) && "uuid".equals(String.valueOf(filter.getValue()))
                && filter.getFilters() != null && !filter.getFilters().isEmpty()
                && "$eq".equals(filter.getFilters().get(0).getName())) {
            return Optional.of(String.valueOf(filter.getFilters().get(0).getValue()));
        }
        if ("$and".equals(filter.getName()) && filter.getFilters() != null) {
            for (IFilter sub : filter.getFilters()) {
                Optional<String> found = uuidOf(sub);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Object save(Object object) throws ApiException {
        this.saves.incrementAndGet();
        UserDto dto = (UserDto) object;
        this.byUuid.put(dto.getUuid(), dto);
        this.dirty = true;
        return object;
    }

    @Override
    public void delete(Object object) throws ApiException {
        this.deletes.incrementAndGet();
        if (object instanceof UserDto dto) {
            this.byUuid.remove(dto.getUuid());
            this.dirty = true;
        }
    }

    @Override
    public long count(IFilter filter) throws ApiException {
        return this.byUuid.size();
    }
}
