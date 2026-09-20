package com.garganttua.api.core.integ.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.SynchronizationPolicy;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.mutex.InterruptibleLeaseMutex;
import com.garganttua.core.mutex.MutexStrategy;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Writes run under the domain's mutex, and only writes do.
 *
 * <p>
 * Two instances serving one database interleave a partial update without a word: each reads the
 * stored entity, merges its own non-null fields and writes the whole thing back, so the second
 * erases what the first decided — and both answer {@code 200}. Counting invariants ("count the rows,
 * then authorize the creation") break the same way. A declared {@link SynchronizationPolicy} makes
 * {@code UPDATE_ONE.gs} and its siblings run inside a lock keyed by tenant and entity.
 * </p>
 *
 * <p>
 * These tests do not test a lock implementation — core owns that. They pin what the api asks for:
 * the right key, at the right moments, nowhere else, and with the failure semantics chosen when the
 * feature was specified (a refused acquisition is a {@code 409}, never a silent unsynchronized
 * write).
 * </p>
 */
@DisplayName("Write synchronization")
class WriteSynchronizationIntegrationTest extends AbstractCrudScriptTest {

    private CapturingDao userDao;
    private IDomain<?> users;
    private RecordingMutexManager mutexes;

    /** Builds a domain, synchronized or not, and seeds one row. */
    private void given(boolean synchronized_) throws ApiException {
        userDao = new CapturingDao();
        mutexes = new RecordingMutexManager();

        IApiBuilder builder = newBuilder();
        var domain = builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .update("name")
                    .update("email")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(userDao)
                .up();
        if (synchronized_) {
            domain.synchronization(mutexes, IClass.getClass(InterruptibleLeaseMutex.class));
        }
        domain.security().disable(true).up().up();

        IApi context = buildAndStart(builder);
        users = context.getDomain("users").orElseThrow();
        seedUser("1", "uuid-alice", "Alice", "alice@example.com");
    }

    private void seedUser(String id, String uuid, String name, String email) {
        UserDto dto = new UserDto();
        dto.setId(id);
        dto.setUuid(uuid);
        dto.setTenantId("SUPER_TENANT");
        dto.setName(name);
        dto.setEmail(email);
        userDao.getStorage().add(dto);
    }

    private WorkflowResult update(String newName) {
        User changed = new User();
        changed.setName(newName);
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("entity", changed);
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-alice");
        return executeScript(users, request);
    }

    private WorkflowResult create(String name) {
        User created = new User();
        created.setName(name);
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.createOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("entity", created);
        return executeScript(users, request);
    }

    private WorkflowResult readOne() {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.readOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-alice");
        return executeScript(users, request);
    }

    private String storedName() {
        return userDao.getStorage().stream()
                .filter(d -> d instanceof UserDto dto && "uuid-alice".equals(dto.getUuid()))
                .map(d -> ((UserDto) d).getName())
                .findFirst().orElse(null);
    }

    @Nested
    @DisplayName("when no policy is declared")
    class Unsynchronized {

        @Test
        @DisplayName("nothing is locked and the write behaves exactly as before")
        void nothingIsLocked() throws ApiException {
            given(false);

            WorkflowResult result = update("Alice Updated");

            assertTrue(result.isSuccess(), () -> "update failed: " + result);
            assertEquals("Alice Updated", storedName());
            assertTrue(mutexes.acquired.isEmpty(),
                    "an undeclared policy must add no lock at all — a single-instance deployment "
                            + "pays nothing for a feature it did not ask for");
        }
    }

    @Nested
    @DisplayName("when a policy is declared")
    class Synchronized {

        @Test
        @DisplayName("an update takes the key of that tenant's entity, and writes inside it")
        void updateLocksTheEntity() throws ApiException {
            given(true);

            WorkflowResult result = update("Alice Updated");

            assertTrue(result.isSuccess(), () -> "update failed: " + result);
            assertEquals(List.of("users:SUPER_TENANT:uuid-alice"), mutexes.acquired,
                    "the key narrows to domain, tenant and entity: two updates of DIFFERENT entities "
                            + "must not serialize on each other");
            assertEquals(1, mutexes.maxDepth.get(),
                    "the stage must run INSIDE the lock — a wrapper that takes the key and then "
                            + "executes nothing is the trap this design walks past");
            assertEquals("Alice Updated", storedName(),
                    "and the write really happened, rather than being swallowed by the wrapper");
        }

        @Test
        @DisplayName("a creation takes the tenant key — there is no uuid yet, and quotas need that")
        void createLocksTheTenant() throws ApiException {
            given(true);

            WorkflowResult result = create("Bob");

            assertTrue(result.isSuccess(), () -> "create failed: " + result);
            assertEquals(List.of("users:SUPER_TENANT"), mutexes.acquired,
                    "a creation has no entity to key on; per-tenant is what a 'count then authorize' "
                            + "quota needs, and it leaves other tenants free");
        }

        @Test
        @DisplayName("a read takes nothing")
        void readsAreNotLocked() throws ApiException {
            given(true);

            WorkflowResult result = readOne();

            assertTrue(result.isSuccess(), () -> "read failed: " + result);
            assertTrue(mutexes.acquired.isEmpty(),
                    "reads lose nothing by interleaving; locking them would cost on the hot path "
                            + "for nothing, and the request that asked for this excluded them");
        }

        @Test
        @DisplayName("the acquisition carries a lease, so a dead node frees the key")
        void theLeaseTravels() throws ApiException {
            given(true);

            update("Alice Updated");

            MutexStrategy strategy = mutexes.lastStrategy;
            assertNotNull(strategy, "the api must hand its strategy to acquire(), not the defaults");
            assertTrue(strategy.leaseTime() > 0,
                    "without a lease, a node that dies holding the key blocks every other forever");
            assertEquals(TimeUnit.SECONDS, strategy.leaseTimeUnit());
        }
    }

    @Nested
    @DisplayName("when the lock cannot be taken")
    class AcquisitionFails {

        @Test
        @DisplayName("the write is refused with a conflict rather than run unprotected")
        void refusedAcquisitionIsAConflict() throws ApiException {
            given(true);
            mutexes.refuseAcquisition = true;

            WorkflowResult result = update("Alice Updated");

            assertFalse(result.isSuccess(),
                    "a lock that silently gives up is not a lock: the lost update it was meant to "
                            + "prevent would come back, with no trace");
            assertEquals("Alice", storedName(), "and nothing must have been written");
        }

        @Test
        @DisplayName("an unresolvable mutex degrades to an unsynchronized write, not to a dead domain")
        void unresolvableMutexDegrades() throws ApiException {
            given(true);
            mutexes.unresolvable = true;

            WorkflowResult result = update("Alice Updated");

            assertTrue(result.isSuccess(),
                    () -> "an unresolvable lock is a deployment problem; turning every write into a "
                            + "failure would take the whole domain down over it: " + result);
            assertEquals("Alice Updated", storedName());
        }
    }

    @Nested
    @DisplayName("a write that fails inside the lock")
    class FailureInsideTheLock {

        @Test
        @DisplayName("reports its own failure, not a lock conflict")
        void businessFailureIsNotDisguised() throws ApiException {
            given(true);

            // Nothing matches this uuid: UPDATE_ONE.gs exits 404. The wrapper must let that surface
            // unchanged — an implementation that lets acquire() wrap whatever the stage threw would
            // report "could not take the lock" for every business refusal.
            User changed = new User();
            changed.setName("Ghost");
            OperationRequest request = superTenantScriptRequest(
                    OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg("entity", changed);
            request.arg("type", "uuid");
            request.arg("identifier", "uuid-nobody");

            WorkflowResult result = executeScript(users, request);

            assertFalse(result.isSuccess());
            assertEquals(List.of("users:SUPER_TENANT:uuid-nobody"), mutexes.acquired,
                    "the lock was taken and released around the failure");
            assertEquals(404, result.code(),
                    "the stage's own exit code must reach the caller — the wrap must not overwrite it");
        }
    }

    @Nested
    @DisplayName("what the lock actually buys")
    class Serialization {

        /** Runs {@code count} updates at once and reports how many were ever inside the lock together. */
        private int overlapOf(java.util.List<String> uuids) throws Exception {
            java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
            mutexes.insideSection = () -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            };
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (String uuid : uuids) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    User changed = new User();
                    changed.setName("by-" + uuid);
                    OperationRequest request = superTenantScriptRequest(
                            OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
                    request.arg("entity", changed);
                    request.arg("type", "uuid");
                    request.arg("identifier", uuid);
                    executeScript(users, request);
                });
                threads.add(t);
                t.start();
            }
            go.countDown();
            for (Thread t : threads) {
                t.join(10_000);
            }
            return peak.get();
        }

        @Test
        @DisplayName("concurrent writes to the SAME entity are serialized — one at a time, never two")
        void sameEntitySerializes() throws Exception {
            given(true);

            int overlap = overlapOf(java.util.List.of("uuid-alice", "uuid-alice", "uuid-alice", "uuid-alice"));

            assertEquals(1, overlap,
                    "this is the whole point: without it, two nodes read the same stored entity, each "
                            + "merges its own fields, and the second write erases the first's decision "
                            + "— both answering 200");
        }

        @Test
        @DisplayName("concurrent writes to DIFFERENT entities still run in parallel")
        void differentEntitiesDoNotSerialize() throws Exception {
            given(true);
            seedUser("2", "uuid-bob", "Bob", "bob@example.com");
            seedUser("3", "uuid-carol", "Carol", "carol@example.com");
            seedUser("4", "uuid-dave", "Dave", "dave@example.com");

            int overlap = overlapOf(java.util.List.of("uuid-alice", "uuid-bob", "uuid-carol", "uuid-dave"));

            assertTrue(overlap > 1,
                    () -> "a key that did not narrow to the entity would serialize the whole domain; "
                            + "observed overlap was " + overlap);
        }
    }

    @Nested
    @DisplayName("where the policy comes from")
    class Declaration {

        @Test
        @DisplayName("declared on the api, it covers every domain")
        void apiWidePolicyReachesTheDomain() throws ApiException {
            userDao = new CapturingDao();
            mutexes = new RecordingMutexManager();

            IApiBuilder builder = newBuilder();
            builder.synchronization(mutexes, IClass.getClass(InterruptibleLeaseMutex.class));
            builder.domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").update("name").up()
                    .dto(IClass.getClass(UserDto.class))
                        .id("id").uuid("uuid").tenantId("tenantId").db(userDao).up()
                    .security().disable(true).up()
                .up();
            users = buildAndStart(builder).getDomain("users").orElseThrow();
            seedUser("1", "uuid-alice", "Alice", "alice@example.com");

            assertTrue(update("Alice Updated").isSuccess());
            assertEquals(List.of("users:SUPER_TENANT:uuid-alice"), mutexes.acquired,
                    "an api-wide declaration is the default for every domain — a consumer wiring this "
                            + "from an auto-configuration module writes it once");
        }

        @Test
        @DisplayName("a qualified name picks the mutex implementation, as it does in events")
        void qualifiedNameSelectsTheType() throws ApiException {
            SynchronizationPolicy policy = SynchronizationPolicy.of(
                    mutexesForNaming(), InterruptibleLeaseMutex.class.getCanonicalName() + "::orders");

            assertEquals(InterruptibleLeaseMutex.class.getCanonicalName(),
                    policy.mutexType().getCanonicalName());
            assertEquals("orders:TENANT_A:uuid-1",
                    policy.keyFor("users", "TENANT_A", "uuid-1").name(),
                    "the name half of Type::name becomes the key prefix, replacing the domain name");
        }

        @Test
        @DisplayName("a policy without a lease is refused")
        void aPolicyMustCarryALease() {
            MutexStrategy noLease = new MutexStrategy(1, TimeUnit.SECONDS, 0, 0,
                    TimeUnit.MILLISECONDS, 0, TimeUnit.SECONDS);

            assertThrows(IllegalArgumentException.class,
                    () -> SynchronizationPolicy.of(mutexesForNaming(),
                            IClass.getClass(InterruptibleLeaseMutex.class)).withStrategy(noLease),
                    "a node that dies holding a key must not block every other node forever");
        }

        private RecordingMutexManager mutexesForNaming() {
            return new RecordingMutexManager();
        }
    }

    /** A use case the framework cannot tell writes from one that only reads. */
    public static class QuotaService {

        public User consumeQuota(@com.garganttua.api.commons.usecase.injection.UseCaseInput User input) {
            return input;
        }

        public User readReport(@com.garganttua.api.commons.usecase.injection.UseCaseInput User input) {
            return input;
        }
    }

    @Nested
    @DisplayName("use cases")
    class UseCases {

        private void givenUseCases(java.util.Set<String> synchronizedOnes) throws ApiException {
            userDao = new CapturingDao();
            mutexes = new RecordingMutexManager();

            IApiBuilder builder = newBuilder();
            var domain = builder.domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(UserDto.class))
                        .id("id").uuid("uuid").tenantId("tenantId").db(userDao).up();
            for (String name : new String[] { "consumeQuota", "readReport" }) {
                domain.useCase(name, IClass.getClass(User.class), IClass.getClass(User.class))
                        .bind(new QuotaService())
                            .method(name, IClass.getClass(User.class), IClass.getClass(User.class))
                        .up()
                    .up();
            }
            domain.synchronization(SynchronizationPolicy
                    .of(mutexes, IClass.getClass(InterruptibleLeaseMutex.class))
                    .withUseCases(synchronizedOnes));
            domain.security().disable(true).up().up();

            users = buildAndStart(builder).getDomain("users").orElseThrow();
        }

        private WorkflowResult runUseCase(String name) {
            OperationDefinition op = users.getDomainDefinition().operations().stream()
                    .filter(o -> name.equals(o.useCaseName()))
                    .findFirst().orElseThrow(() -> new AssertionError(name + " not exposed"));
            OperationRequest request = superTenantScriptRequest(op);
            request.arg("entity", new User());
            return executeScript(users, request);
        }

        @Test
        @DisplayName("a named one runs under the lock")
        void namedUseCaseIsSynchronized() throws ApiException {
            givenUseCases(java.util.Set.of("consumeQuota"));

            assertTrue(runUseCase("consumeQuota").isSuccess());

            assertEquals(List.of("users:SUPER_TENANT"), mutexes.acquired,
                    "a use case that writes — a quota that counts then authorizes — must be able to "
                            + "join the CRUD writes under the same key");
        }

        @Test
        @DisplayName("an unnamed one does not — the framework cannot guess that it writes")
        void unnamedUseCaseIsNotSynchronized() throws ApiException {
            givenUseCases(java.util.Set.of("consumeQuota"));

            assertTrue(runUseCase("readReport").isSuccess());

            assertTrue(mutexes.acquired.isEmpty(),
                    "opt-in, deliberately: locking a use case that only reads would serialize it for "
                            + "nothing, and only its author knows which it is");
        }
    }

    @Nested
    @DisplayName("the startup upsert")
    class Startup {

        /** A dao whose save() replaces the row with the same uuid, like a real upsert by _id. */
        static class UpsertingDao extends CapturingDao {
            @Override
            public Object save(Object object) throws ApiException {
                String uuid = uuidOf(object);
                if (uuid != null) {
                    getStorage().removeIf(row -> uuid.equals(uuidOf(row)));
                }
                return super.save(object);
            }

            private static String uuidOf(Object o) {
                try {
                    java.lang.reflect.Field f = o.getClass().getDeclaredField("uuid");
                    f.setAccessible(true);
                    Object v = f.get(o);
                    return v != null ? v.toString() : null;
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }

        @Test
        @DisplayName("still runs when the domain is synchronized")
        void startupEntitiesSurviveTheWrapper() throws ApiException {
            userDao = new UpsertingDao();
            mutexes = new RecordingMutexManager();

            User seeded = new User();
            seeded.setUuid("uuid-bootstrap");
            seeded.setName("Bootstrap admin");
            seeded.setTenantId("SUPER_TENANT");

            // The row is ALREADY there, as on every restart after the first: the upsert then takes
            // the UPDATE path, not the create one. That is the shape an application actually runs.
            UserDto existing = new UserDto();
            existing.setId("1");
            existing.setUuid("uuid-bootstrap");
            existing.setTenantId("SUPER_TENANT");
            existing.setName("Stale name");
            userDao.getStorage().add(existing);

            IApiBuilder builder = newBuilder();
            builder.synchronization(mutexes, IClass.getClass(InterruptibleLeaseMutex.class));
            builder.domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").mandatory("name")
                        .update("name").up()
                    .dto(IClass.getClass(UserDto.class))
                        .id("id").uuid("uuid").tenantId("tenantId").db(userDao).up()
                    .creation(true).readAll(true)
                    .upsert(seeded)
                    .security().disable(true).up()
                .up();

            // An application seeds its bootstrap administrator and its reference data this way. The
            // startup path invokes the domain OUTSIDE any request, and the wrapper must cope: a
            // domain that declares startup entities must not become unbootable by being synchronized.
            IApi context = buildAndStart(builder);

            users = context.getDomain("users").orElseThrow();
            assertEquals("Bootstrap admin", storedName("uuid-bootstrap"),
                    "the declared startup entity must have been persisted");
        }
    }

    private String storedName(String uuid) {
        return userDao.getStorage().stream()
                .filter(d -> d instanceof UserDto dto && uuid.equals(dto.getUuid()))
                .map(d -> ((UserDto) d).getName())
                .findFirst().orElse(null);
    }

    @Nested
    @DisplayName("under the REAL core mutex, not a fake")
    class RealMutex {

        /**
         * The fakes above stay on one thread. {@code InterruptibleLeaseMutex} does not: to enforce
         * the lease — which this api makes mandatory — it runs the protected block on a dedicated
         * thread so it can interrupt it. Every ambient context a stage needs is a {@code ScopedValue},
         * deliberately not inheritable, so it must be re-bound on the other side. This test is the
         * guard: if core grows a third such context, it fails here rather than in a consumer's logs.
         */
        @Test
        @DisplayName("a write completes, with the lease enforced on another thread")
        void writeSurvivesTheRealLeaseEnforcingMutex() throws ApiException {
            userDao = new CapturingDao();

            IApiBuilder builder = newBuilder();
            builder.synchronization(new com.garganttua.core.mutex.MutexManager(),
                    IClass.getClass(InterruptibleLeaseMutex.class));
            builder.domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").update("name").up()
                    .dto(IClass.getClass(UserDto.class))
                        .id("id").uuid("uuid").tenantId("tenantId").db(userDao).up()
                    .security().disable(true).up()
                .up();
            users = buildAndStart(builder).getDomain("users").orElseThrow();
            seedUser("1", "uuid-alice", "Alice", "alice@example.com");

            WorkflowResult result = update("Alice Updated");

            assertTrue(result.isSuccess(), () -> "the write failed under the real mutex: " + result
                    + result.exception().map(e -> "\ncause: " + e.getCause()).orElse(""));
            assertEquals("Alice Updated", storedName("uuid-alice"),
                    "and it really wrote, rather than being swallowed on the other thread");
        }
    }
}
