package com.garganttua.api.core.integ;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.RequestBuilder;
import com.garganttua.core.reflection.IClass;

/**
 * End-to-end tests for per-domain write synchronization: the DSL {@code .synchronization(...)} is
 * carried through the build onto the {@link Domain} context, and a synchronized domain serializes its
 * concurrent writes through the core mutex (peak in-flight save == 1). Mirrors the events
 * route-synchronization E2E test.
 */
class DomainSyncIntegrationTest extends AbstractCrudIntegrationTest {

    /** A DAO whose {@link #save} records the peak number of concurrent in-flight writes. */
    static final class ProbeDao implements IDao {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        int peakInFlight() {
            return this.peak.get();
        }

        @Override
        public void registerDomain(IDomainDefinition domainDefinition) {
        }

        @Override
        public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort) {
            // Empty: the create unicity check then sees no duplicate and proceeds to save.
            return new ArrayList<>();
        }

        @Override
        public Object save(Object object) {
            int now = this.inFlight.incrementAndGet();
            this.peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                this.inFlight.decrementAndGet();
            }
            return object;
        }

        @Override
        public void delete(Object object) {
        }

        @Override
        public long count(IFilter filter) {
            return 0;
        }
    }

    private IApi buildProducts(IDao dao, boolean synchronize) throws ApiException {
        IApiBuilder builder = newBaseBuilder().multiTenant(false);
        var domain = builder.domain(IClass.getClass(Product.class))
                .entity().id("id").uuid("uuid").up()
                .dto(IClass.getClass(ProductDto.class))
                    .id("id").uuid("uuid")
                    .db(dao)
                .up()
                .security().disable(true).up()
                .creation(true).readAll(true).readOne(true);
        if (synchronize) {
            domain.synchronization("products-lock", null);
        }
        domain.up();
        IApi api = builder.build();
        api.onInit();
        api.onStart();
        return api;
    }

    private static Product product(String label) {
        Product p = new Product();
        p.setLabel(label);
        p.setPrice(1.0);
        return p;
    }

    @Nested
    @DisplayName("build wiring")
    class BuildWiring {

        @Test
        @DisplayName(".synchronization(...) marks the built domain as synchronized; absent it is not")
        void synchronizationFlagCarriesThroughBuild() throws ApiException {
            IDomain<?> synced = buildProducts(new ProbeDao(), true).getDomain("products").orElseThrow();
            IDomain<?> plain = buildProducts(new ProbeDao(), false).getDomain("products").orElseThrow();

            assertTrue(((Domain<?>) synced).hasSynchronization(),
                    "a domain declaring .synchronization(...) must be marked synchronized");
            assertFalse(((Domain<?>) plain).hasSynchronization(),
                    "a domain without .synchronization(...) must not be synchronized");
        }
    }

    @Nested
    @DisplayName("runtime serialization")
    class RuntimeSerialization {

        @Test
        @DisplayName("a synchronized domain serializes concurrent creates through the mutex (peak in-flight 1)")
        void synchronizedDomainSerializesWrites() throws Exception {
            ProbeDao dao = new ProbeDao();
            IApi api = buildProducts(dao, true);
            IDomain<?> domain = api.getDomain("products").orElseThrow();

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    final int n = i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                            RequestBuilder.builder(domain).createOne(product("p" + n)).build().execute();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not start in time");
                go.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent creates did not finish in time");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, dao.peakInFlight(),
                    "the domain mutex must serialize writes: never more than one save in flight at once");
        }
    }
}
