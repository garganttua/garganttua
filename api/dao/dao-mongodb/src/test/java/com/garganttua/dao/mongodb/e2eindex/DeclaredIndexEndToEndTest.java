package com.garganttua.dao.mongodb.e2eindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.dao.mongodb.MongoIndexMode;
import com.garganttua.dao.mongodb.MongoTestServer;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * The whole joint, end to end: an annotation on a field, and a database that refuses a duplicate.
 *
 * <p>
 * Every part of this path already had its own test, and the path itself had none. The scan test
 * stops once the annotation has reached the entity definition; the index-manager test starts from a
 * spec built by hand and writes with the raw driver; the DAO test stands the definitions up with
 * Mockito. Each of them can pass while the joint between two of them is broken — which is exactly
 * how a uniqueness nobody held came to be believed for as long as it was. So this one starts from
 * an annotated class, builds the api with the real {@code ApiBuilder}, stores through the real
 * {@code MongoDao} into a real {@code mongod}, and writes through the same CRUD call a consumer
 * makes.
 * </p>
 *
 * <p>
 * The measurement it exists to hold is {@link TheConsumersMeasurement}: the consumer reported that
 * thirty rounds of two concurrent writes produced thirty duplicates, because the framework check
 * reads and then writes and two requests walk through the gap between the two. That number was only
 * ever reproduced on a throwaway bench. It is reproduced here, and it is the assertion that fails if
 * the index is ever taken away again.
 * </p>
 *
 * <p>
 * Like every integration suite in this module it runs against a real {@code mongod} obtained by
 * {@link MongoTestServer}, and is skipped — never faked — when the binary cannot be obtained.
 * </p>
 */
@DisplayName("@EntityIndexed, from the annotation to what the database does")
class DeclaredIndexEndToEndTest {

    /** The consumer's own figure: thirty rounds, which produced thirty duplicates unindexed. */
    private static final int ROUNDS = 30;

    private static final String EMAIL_INDEX = "gg_email_tenant_standard_unique";
    private static final String NATIONAL_ID_INDEX = "gg_nationalId_system_standard_unique";

    private MongoDatabase database;
    private MongoCollection<Document> collection;
    private List<String> warnings;
    private IObserver<ObservableEvent> observer;
    private IDomain<?> members;

    @BeforeEach
    void setUp() {
        this.database = MongoTestServer.freshDatabase();
        this.collection = this.database.getCollection(IndexedApiFixture.MEMBERS);
        this.warnings = Collections.synchronizedList(new ArrayList<>());
        this.observer = event -> {
            if (event instanceof LogEvent log && log.level() == LogEvent.Level.WARN) {
                this.warnings.add(log.message());
            }
        };
        Logger.global().addObserver(this.observer);
    }

    @AfterEach
    void tearDown() {
        Logger.global().removeObserver(this.observer);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Starts the api over the fresh database — which is what asks the store for the indexes. */
    private void startApi() throws ApiException {
        IApi api = IndexedApiFixture.start(this.database, MongoIndexMode.CREATE);
        this.members = api.getDomain(IndexedApiFixture.MEMBERS).orElseThrow();
    }

    private static IndexedApiFixture.Member member(String email, String nationalId) {
        IndexedApiFixture.Member member = new IndexedApiFixture.Member();
        member.setEmail(email);
        member.setNationalId(nationalId);
        return member;
    }

    /** One create through the real CRUD path, as a consumer makes it. */
    private IOperationResponse create(String tenant, String email, String nationalId) {
        ICaller caller = Caller.createTenantCaller(tenant);
        return this.members.createOne(member(email, nationalId), caller);
    }

    private Optional<Document> storedIndex(String name) {
        List<Document> stored = new ArrayList<>();
        this.collection.listIndexes().into(stored);
        return stored.stream().filter(index -> name.equals(index.getString("name"))).findFirst();
    }

    private long countWith(String field, String value) {
        return this.collection.countDocuments(new Document(field, value));
    }

    /** A document written straight to the collection, going around the api's own check. */
    private static Document raw(String uuid, String tenant, String email, String nationalId) {
        return new Document("_id", uuid).append("uuid", uuid).append("tenantId", tenant)
                .append("email", email).append("nationalId", nationalId);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Starting the api lays the declared indexes")
    class LaidOnStartup {

        @BeforeEach
        void start() throws ApiException {
            startApi();
        }

        @Test
        @DisplayName("the tenant-scoped one carries the tenant FIRST, is unique, and skips null values")
        void tenantScopedIndexIsLaid() {
            Document stored = storedIndex(EMAIL_INDEX).orElseThrow(
                    () -> new AssertionError("no index named " + EMAIL_INDEX + " was created"));

            assertEquals(List.of("tenantId", "email"),
                    List.copyOf(((Document) stored.get("key")).keySet()),
                    "the tenant must come first, or a tenant-filtered query cannot use the index");
            assertEquals(Boolean.TRUE, stored.getBoolean("unique"));
            assertTrue(stored.containsKey("partialFilterExpression"),
                    "a unique index without the partial filter would also refuse a second null");
        }

        @Test
        @DisplayName("the system-scoped one indexes the field alone, with no tenant prefix")
        void systemScopedIndexIsLaid() {
            Document stored = storedIndex(NATIONAL_ID_INDEX).orElseThrow(
                    () -> new AssertionError("no index named " + NATIONAL_ID_INDEX + " was created"));

            assertEquals(List.of("nationalId"), List.copyOf(((Document) stored.get("key")).keySet()));
            assertEquals(Boolean.TRUE, stored.getBoolean("unique"));
        }

        @Test
        @DisplayName("nothing else is created — a declaration is not a licence to index the entity")
        void onlyTheDeclaredIndexesAreCreated() {
            List<String> names = new ArrayList<>();
            collection.listIndexes().forEach(index -> names.add(index.getString("name")));

            assertEquals(List.of("_id_", EMAIL_INDEX, NATIONAL_ID_INDEX), names);
        }
    }

    @Nested
    @DisplayName("The scope the annotation declared is the scope the database holds")
    class Scope {

        @BeforeEach
        void start() throws ApiException {
            startApi();
            assertEquals(OperationResponseCode.CREATED,
                    create("T1", "same@example.org", "N1").getResponseCode());
        }

        @Test
        @DisplayName("two tenants may carry the same tenant-scoped value, through the real write path")
        void tenantScopedValueMayRepeatAcrossTenants() {
            IOperationResponse response = create("T2", "same@example.org", "N2");

            assertEquals(OperationResponseCode.CREATED, response.getResponseCode(),
                    () -> "a per-tenant uniqueness must not span tenants: " + response.getResponse());
            assertEquals(2, countWith("email", "same@example.org"));
        }

        @Test
        @DisplayName("a system-scoped value is refused in ANOTHER tenant, through the real write path")
        void systemScopedValueIsRefusedAcrossTenants() {
            IOperationResponse response = create("T2", "other@example.org", "N1");

            assertNotEquals(OperationResponseCode.CREATED, response.getResponseCode(),
                    "a system-scoped uniqueness spans every tenant");
            assertEquals(1, countWith("nationalId", "N1"), "and nothing was stored");
        }

        @Test
        @DisplayName("and the refusal is the DATABASE's: a write going around the api is refused too, "
                + "while the tenant-scoped one is not")
        void theStoreItselfHoldsTheConstraint() {
            // Straight to the collection, so the api's own validateUnicity never runs. What answers
            // here is the server, which is the only thing a second process or a second instance obeys.
            collection.insertOne(raw("direct-1", "T2", "same@example.org", "N9"));
            assertEquals(2, countWith("email", "same@example.org"), "tenant-scoped: T2 may repeat it");

            MongoWriteException refused = assertThrows(MongoWriteException.class,
                    () -> collection.insertOne(raw("direct-2", "T2", "free@example.org", "N1")));

            assertEquals(11000, refused.getError().getCode(), refused.getMessage());
            assertEquals(1, countWith("nationalId", "N1"));
        }
    }

    @Nested
    @DisplayName("The consumer's measurement: two concurrent writers, thirty times over")
    class TheConsumersMeasurement {

        @BeforeEach
        void start() throws ApiException {
            startApi();
        }

        /**
         * What one round did: how many of the two writers were told they had created the member.
         *
         * @param created  the number of {@code CREATED} responses
         * @param refused  the number of responses that were anything else
         * @param inStore  how many documents actually carry the raced value
         */
        private record Round(int created, int refused, long inStore) {
        }

        /**
         * Two writers released together on a barrier, both creating the same email in the same
         * tenant, both going through the whole pipeline — {@code validateUnicity} included. There is
         * no sleep anywhere: the barrier is the synchronisation, so the round is as tight as the
         * machine allows and as reproducible as it can be made.
         */
        private Round race(String email) throws InterruptedException {
            CyclicBarrier start = new CyclicBarrier(2);
            List<IOperationResponse> responses = Collections.synchronizedList(new ArrayList<>());
            List<Thread> writers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                writers.add(Thread.ofPlatform().unstarted(() -> {
                    awaitQuietly(start);
                    responses.add(create("T1", email, null));
                }));
            }
            writers.forEach(Thread::start);
            for (Thread writer : writers) {
                writer.join();
            }
            return tally(responses, email);
        }

        private Round tally(List<IOperationResponse> responses, String email) {
            assertEquals(2, responses.size(), "both writers must have answered");
            int created = 0;
            for (IOperationResponse response : responses) {
                if (response.getResponseCode() == OperationResponseCode.CREATED) {
                    created++;
                }
            }
            return new Round(created, responses.size() - created, countWith("email", email));
        }

        /** The barrier never times out here; a broken one is a test bug, not a result. */
        private static void awaitQuietly(CyclicBarrier barrier) {
            try {
                barrier.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("writer interrupted at the barrier", e);
            } catch (RuntimeException | java.util.concurrent.BrokenBarrierException e) {
                throw new IllegalStateException("the barrier broke", e);
            }
        }

        @Test
        @DisplayName("thirty rounds produce thirty members and ZERO duplicates — the figure the "
                + "consumer measured as thirty duplicates")
        void thirtyRoundsLeaveNoDuplicate() throws InterruptedException {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();

            for (int round = 0; round < ROUNDS; round++) {
                // The collection is emptied between rounds; the indexes stay, as a restart would
                // leave them. Each round therefore starts from the same state as the first.
                collection.deleteMany(new Document());
                Round result = race("race@example.org");

                int number = round;
                assertEquals(1, result.inStore(),
                        () -> "round " + number + " stored " + result.inStore() + " documents for one "
                                + "value — a duplicate is exactly what the index exists to prevent");
                assertEquals(1, result.created(),
                        () -> "round " + number + ": both writers were told they had created it");
                created.addAndGet(result.created());
                refused.addAndGet(result.refused());
            }

            assertEquals(ROUNDS, created.get(), "one winner per round");
            assertEquals(ROUNDS, refused.get(), "and one loser per round, told so rather than ignored");
        }
    }

    @Nested
    @DisplayName("A missing value is not a duplicate")
    class MissingValues {

        @BeforeEach
        void start() throws ApiException {
            startApi();
        }

        @Test
        @DisplayName("any number of members may carry no email and no national id at all — the "
                + "partial filter keeps what validateUnicity did, which returned early on a null")
        void severalNullValuesPass() {
            for (int i = 0; i < 3; i++) {
                int number = i;
                IOperationResponse response = create("T1", null, null);
                assertEquals(OperationResponseCode.CREATED, response.getResponseCode(),
                        () -> "member " + number + " with no email: " + response.getResponse());
            }

            assertEquals(3, collection.countDocuments());
        }

        @Test
        @DisplayName("and a document with the field ABSENT rather than null passes too")
        void absentFieldsPass() {
            collection.insertOne(new Document("_id", "absent-1").append("tenantId", "T1"));
            collection.insertOne(new Document("_id", "absent-2").append("tenantId", "T1"));

            assertEquals(2, collection.countDocuments());
        }
    }

    @Nested
    @DisplayName("A database that already holds duplicates")
    class ExistingDuplicates {

        @Test
        @DisplayName("the application still STARTS, the index it could not create is absent, and a "
                + "WARN names the domain, the field and the query listing the offenders")
        void startsWithAWarn() throws ApiException {
            collection.insertOne(raw("dup-1", "T1", "dup@example.org", null));
            collection.insertOne(raw("dup-2", "T1", "dup@example.org", null));

            startApi();

            assertTrue(storedIndex(EMAIL_INDEX).isEmpty(),
                    "the index could not be created, and nothing pretends otherwise");
            assertTrue(storedIndex(NATIONAL_ID_INDEX).isPresent(),
                    "the index that COULD be created still was — one failure is not a reason to skip the rest");
            assertTrue(warnings.stream().anyMatch(w -> w.contains(IndexedApiFixture.MEMBERS)
                    && w.contains("email") && w.contains("aggregate([")),
                    () -> "no WARN pointed at the duplicates; got " + warnings);
        }
    }
}
