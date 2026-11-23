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
}
