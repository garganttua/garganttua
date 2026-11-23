package com.garganttua.api.core.integ.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.cache.CacheSpec;
import com.garganttua.core.cache.ICacheManager;
import com.garganttua.core.cache.LocalCache;
import com.garganttua.core.cache.dsl.CacheManagerBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/** A domain that caches its reads by uuid: one read of the store, then the cache, until a write invalidates it. */
@DisplayName("Domain cache — reads by uuid, invalidated by every write of the repository")
class DomainCacheIntegrationTest extends AbstractCrudScriptTest {

    /** A DAO that counts the reads reaching it. */
    static final class CountingDao extends CapturingDao {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger counts = new AtomicInteger();

        @Override
        public long count(IFilter filter) throws ApiException {
            this.counts.incrementAndGet();
            return super.count(filter);
        }

        @Override
        public List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
                throws ApiException {
            this.reads.incrementAndGet();
            return super.find(pageable, filter, sort);
        }
    }

    private final CountingDao dao = new CountingDao();
    private IDomain<?> users;

    private IDomain<?> start(boolean cached) throws ApiException {
        ICacheManager caches = CacheManagerBuilder.builder().build();
        IApiBuilder builder = newBuilder();
        var domain = builder.domain(IClass.getClass(User.class))
                .tenant(true).superTenant("superTenant")
                .entity().id("id").uuid("uuid").tenantId("tenantId").update("name").up()
                .dto(IClass.getClass(UserDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(this.dao).up();
        if (cached) {
            domain.cache(caches, IClass.getClass(LocalCache.class), CacheSpec.of());
        }
        domain.security().disable(true).up().up();
        return buildAndStart(builder).getDomain("users").orElseThrow();
    }

    @BeforeEach
    void setUp() throws ApiException {
        this.users = start(true);
        UserDto ana = new UserDto();
        ana.setId("ana");
        ana.setUuid("uuid-ana");
        ana.setTenantId("SUPER_TENANT");
        ana.setName("Ana");
        this.dao.save(ana);
        this.dao.reads.set(0);
    }

    private User read() throws ApiException {
        return (User) this.users.getRepository().getByUuid("uuid-ana").orElse(null);
    }

    @Test
    @DisplayName("a second read by uuid is served by the cache")
    void cachedRead() throws ApiException {
        assertEquals("Ana", read().getName());
        assertEquals("Ana", read().getName());

        assertEquals(1, this.dao.reads.get(), "one read of the store for two reads by uuid");
    }

    @Test
    @DisplayName("a write through the repository invalidates the uuid")
    void writeInvalidates() throws ApiException {
        User ana = read();
        ana.setName("Ana B.");
        this.users.getRepository().save(ana);

        assertEquals("Ana B.", read().getName());
        assertEquals(2, this.dao.reads.get());
    }

    @Test
    @DisplayName("an update through the pipeline invalidates the uuid")
    void pipelineUpdateInvalidates() throws ApiException {
        read();
        User body = new User();
        body.setName("Ana C.");
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-ana");
        request.arg("entity", body);
        WorkflowResult result = executeScript(this.users, request);

        assertEquals(0, result.code(), "update failed");
        assertEquals("Ana C.", read().getName());
    }

    @Test
    @DisplayName("a deletion invalidates the uuid")
    void deleteInvalidates() throws ApiException {
        IRepository repository = this.users.getRepository();
        repository.delete(read());

        assertTrue(repository.getByUuid("uuid-ana").isEmpty());
    }

    @Test
    @DisplayName("each read is a fresh entity: changing one does not change what the cache serves")
    void readsAreIsolated() throws ApiException {
        read().setName("changed in a request, never saved");

        assertEquals("Ana", read().getName());
        assertEquals(1, this.dao.reads.get());
    }

    @Test
    @DisplayName("without a declared cache, every read goes to the store")
    void uncachedByDefault() throws ApiException {
        CountingDao plain = new CountingDao();
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true).superTenant("superTenant")
                .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                .dto(IClass.getClass(UserDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(plain).up()
                .security().disable(true).up().up();
        IRepository repository = buildAndStart(builder).getDomain("users").orElseThrow().getRepository();
        plain.reads.set(0);

        repository.getByUuid("x");
        repository.getByUuid("x");

        assertEquals(2, plain.reads.get());
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("the cache of searches")
    class Searches {

        private final CountingDao searched = new CountingDao();
        private IDomain<?> domain;

        @BeforeEach
        void start() throws ApiException {
            ICacheManager caches = CacheManagerBuilder.builder().build();
            IApiBuilder builder = newBuilder();
            builder.domain(IClass.getClass(User.class))
                    .tenant(true).superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(UserDto.class)).id("id").uuid("uuid").tenantId("tenantId").db(this.searched).up()
                    .queryCache(caches, IClass.getClass(LocalCache.class), CacheSpec.of())
                    .security().disable(true).up().up();
            this.domain = buildAndStart(builder).getDomain("users").orElseThrow();
            this.searched.save(user("ana", "uuid-ana", "T1"));
            this.searched.save(user("bob", "uuid-bob", "T2"));
            this.searched.reads.set(0);
        }

        private UserDto user(String id, String uuid, String tenant) {
            UserDto dto = new UserDto();
            dto.setId(id);
            dto.setUuid(uuid);
            dto.setTenantId(tenant);
            dto.setName(id);
            return dto;
        }

        private List<Object> search(String name) throws ApiException {
            return this.domain.getRepository().getEntities(Optional.empty(),
                    Optional.of(com.garganttua.api.core.filter.Filter.eq("name", name)), Optional.empty());
        }

        @Test
        @DisplayName("the same search is served by the cache; another one is not")
        void sameSearchCached() throws ApiException {
            assertEquals(1, search("ana").size());
            assertEquals(1, search("ana").size());
            assertEquals(1, this.searched.reads.get());

            assertEquals(1, search("bob").size());
            assertEquals(2, this.searched.reads.get(), "another filter, another entry");
        }

        @Test
        @DisplayName("any write of the domain empties it")
        void writeEmptiesIt() throws ApiException {
            search("ana");
            this.domain.getRepository().save(this.domain.getRepository().getByUuid("uuid-bob").orElseThrow());

            search("ana");
            assertEquals(3, this.searched.reads.get(), "search, read by uuid, search again");
        }

        @Test
        @DisplayName("a list read through the pipeline by one tenant is never served to another")
        void tenantsNeverShare() throws ApiException {
            OperationDefinition readAll = OperationDefinition.readAllWithStandardSecurity("users",
                    IClass.getClass(User.class));

            List<?> t1 = (List<?>) executeScript(this.domain, tenantScriptRequest(readAll, "T1")).output();
            List<?> t2 = (List<?>) executeScript(this.domain, tenantScriptRequest(readAll, "T2")).output();

            assertEquals(List.of("ana"), t1.stream().map(u -> ((User) u).getId()).toList());
            assertEquals(List.of("bob"), t2.stream().map(u -> ((User) u).getId()).toList(),
                    "the access filter is part of the key");
            int reads = this.searched.reads.get();
            List<?> again = (List<?>) executeScript(this.domain, tenantScriptRequest(readAll, "T1")).output();
            assertEquals(List.of("ana"), again.stream().map(u -> ((User) u).getId()).toList());
            assertEquals(reads, this.searched.reads.get(), "T1's list came from the cache");
        }

        @Test
        @DisplayName("counts are cached and emptied like lists")
        void counts() throws ApiException {
            com.garganttua.api.commons.filter.IFilter tenantT1 = com.garganttua.api.core.filter.Filter.eq("tenantId", "T1");
            CountingDao counted = this.searched;
            long first = this.domain.getRepository().getCount(tenantT1);
            long second = this.domain.getRepository().getCount(tenantT1);

            assertEquals(first, second);
            assertEquals(1, counted.counts.get(), "the store counted once");
        }
    }
}

