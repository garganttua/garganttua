package com.garganttua.api.core.integ.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.cache.dsl.CacheManagerBuilder;
import com.garganttua.core.reflection.IClass;

/** Map searches read by tiles: the same rows as the store, and the tiles shared between neighbouring viewports. */
@DisplayName("Tile cache — map searches by tiles")
class TileCacheIntegrationTest extends AbstractCrudScriptTest {

    public static class Place {
        private String id;
        private String uuid;
        private String tenantId;
        private String kind;
        private Double latitude;
        private Double longitude;
        private Boolean superTenant = false;
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
    }

    public static class PlaceDto extends Place {
    }

    /** A store that evaluates $and, $eq, $gt, $gte, $lt, $lte — as MongoDB and PostgreSQL do — and counts reads. */
    static final class PlaceDao extends CapturingDao {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort) {
            this.reads.incrementAndGet();
            return getStorage().stream().filter(row -> filter.map(f -> matches((Place) row, f)).orElse(true))
                    .collect(Collectors.toList());
        }

        private static boolean matches(Place row, IFilter filter) {
            if (Filter.OPERATOR_AND.equals(filter.getName())) {
                return filter.getFilters().stream().allMatch(sub -> matches(row, sub));
            }
            IFilter op = filter.getFilters().get(0);
            Object actual = switch (String.valueOf(filter.getValue())) {
                case "latitude" -> row.getLatitude();
                case "longitude" -> row.getLongitude();
                case "kind" -> row.getKind();
                case "tenantId" -> row.getTenantId();
                default -> null;
            };
            if (Filter.OPERATOR_EQUAL.equals(op.getName())) {
                return op.getValue().equals(actual);
            }
            double value = ((Number) actual).doubleValue();
            double bound = ((Number) op.getValue()).doubleValue();
            return switch (op.getName()) {
                case Filter.OPERATOR_GREATER_THAN -> value > bound;
                case Filter.OPERATOR_GREATER_THAN_EXCLUSIVE -> value >= bound;
                case Filter.OPERATOR_LOWER_THAN -> value < bound;
                case Filter.OPERATOR_LOWER_THAN_EXCLUSIVE -> value <= bound;
                default -> throw new IllegalArgumentException(op.getName());
            };
        }
    }

    private final PlaceDao dao = new PlaceDao();
    private IRepository tiled;

    @BeforeEach
    void setUp() throws ApiException {
        ICacheManager caches = CacheManagerBuilder.builder().build();
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(Place.class))
                .tenant(true).superTenant("superTenant")
                .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                .dto(IClass.getClass(PlaceDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(this.dao).up()
                .tileCache(caches, IClass.getClass(LocalCache.class), CacheSpec.of(), "latitude", "longitude")
                .security().disable(true).up().up();
        this.tiled = buildAndStart(builder).getDomain("places").orElseThrow().getRepository();
        Random random = new Random(42);
        for (int i = 0; i < 400; i++) {
            place("p" + i, i % 2 == 0 ? "T1" : "T2", i % 3 == 0 ? "center" : "home",
                    45 + random.nextDouble() * 4, 2 + random.nextDouble() * 4);
        }
        place("edge", "T1", "center", 46.0, 3.0);   // exactly on the bounds of some viewports below
        this.dao.reads.set(0);
    }

    private void place(String id, String tenant, String kind, double lat, double lon) throws ApiException {
        PlaceDto dto = new PlaceDto();
        dto.setId(id);
        dto.setUuid("uuid-" + id);
        dto.setTenantId(tenant);
        dto.setKind(kind);
        dto.setLatitude(lat);
        dto.setLongitude(lon);
        this.dao.save(dto);
    }

    private static Filter viewport(Filter latLow, Filter latHigh, Filter lonLow, Filter lonHigh, Filter... rest) {
        Filter[] all = new Filter[4 + rest.length];
        all[0] = latLow;
        all[1] = latHigh;
        all[2] = lonLow;
        all[3] = lonHigh;
        System.arraycopy(rest, 0, all, 4, rest.length);
        return Filter.and(all);
    }

    private Set<String> ids(List<Object> rows) {
        return rows.stream().map(row -> ((Place) row).getId()).collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> tiled(Filter filter) throws ApiException {
        return ids(this.tiled.getEntities(Optional.empty(), Optional.of(filter), Optional.empty()));
    }

    /** What the store answers for the same query, without tiles. */
    private Set<String> store(Filter filter) {
        return ids(this.dao.find(Optional.empty(), Optional.of(filter), Optional.empty()).stream()
                .map(Object.class::cast).toList());
    }

    @Test
    @DisplayName("on 200 viewports — inclusive and strict bounds, with and without other conditions — the rows of the store")
    void sameRowsAsTheStore() throws ApiException {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            double south = 45 + random.nextDouble() * 3;
            double west = 2 + random.nextDouble() * 3;
            double span = 0.05 + random.nextDouble() * 2;
            Filter filter = i % 2 == 0
                    ? viewport(Filter.gte("latitude", south), Filter.lte("latitude", south + span),
                            Filter.gte("longitude", west), Filter.lte("longitude", west + span))
                    : viewport(Filter.gt("latitude", south), Filter.lt("latitude", south + span),
                            Filter.gt("longitude", west), Filter.lt("longitude", west + span),
                            Filter.eq("kind", "center"), Filter.eq("tenantId", i % 4 == 1 ? "T1" : "T2"));
            assertEquals(store(filter), tiled(filter), "viewport " + i);
        }
    }

    @Test
    @DisplayName("a row exactly on a bound is in an inclusive search and out of a strict one")
    void boundsExactly() throws ApiException {
        Filter inclusive = viewport(Filter.gte("latitude", 46.0), Filter.lte("latitude", 46.5),
                Filter.gte("longitude", 3.0), Filter.lte("longitude", 3.5));
        Filter strict = viewport(Filter.gt("latitude", 46.0), Filter.lte("latitude", 46.5),
                Filter.gte("longitude", 3.0), Filter.lte("longitude", 3.5));

        assertTrue(tiled(inclusive).contains("edge"));
        assertTrue(!tiled(strict).contains("edge"));
    }

    @Test
    @DisplayName("panning reads only the new tiles; the same viewport again reads nothing")
    void panningReusesTiles() throws ApiException {
        Filter here = viewport(Filter.gte("latitude", 46.1), Filter.lte("latitude", 46.4),
                Filter.gte("longitude", 3.1), Filter.lte("longitude", 3.4));
        Filter slightlyEast = viewport(Filter.gte("latitude", 46.1), Filter.lte("latitude", 46.4),
                Filter.gte("longitude", 3.15), Filter.lte("longitude", 3.45));

        tiled(here);
        int first = this.dao.reads.get();
        tiled(here);
        assertEquals(first, this.dao.reads.get(), "the same viewport is served by the tiles");
        tiled(slightlyEast);
        assertTrue(this.dao.reads.get() - first < first, "a small pan reads fewer tiles than the first viewport");
    }

    @Test
    @DisplayName("a write empties the tiles: the next search sees it")
    void writeEmptiesTiles() throws ApiException {
        Filter filter = viewport(Filter.gte("latitude", 45.9), Filter.lte("latitude", 46.1),
                Filter.gte("longitude", 2.9), Filter.lte("longitude", 3.1));
        tiled(filter);
        Place added = new Place();
        added.setId("new");
        added.setUuid("uuid-new");
        added.setTenantId("T1");
        added.setKind("home");
        added.setLatitude(46.0);
        added.setLongitude(3.05);
        this.tiled.save(added);

        assertTrue(tiled(filter).contains("new"));
    }

    @Test
    @DisplayName("a search that does not bound both fields on both sides is read as usual, and still right")
    void otherSearchesUntouched() throws ApiException {
        Filter halfOpen = Filter.and(Filter.gte("latitude", 46.0), Filter.gte("longitude", 3.0));

        assertEquals(store(halfOpen), tiled(halfOpen));
    }
}
