package com.garganttua.api.core.perfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.Sort;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;

/**
 * What a read costs as the QUERY grows — more rows, a page, a filter and a sort, a field selection —
 * with the database replaced by {@link MockDao}, which answers in constant time.
 *
 * <p>
 * {@link CrudPipelinePerfTest} measures the fixed cost of one operation; this suite measures how
 * that cost moves with what the request asks for. Each test holds two scenarios against each other,
 * interleaved and compared on their floors (see {@link PerfHarness#compare}), and asserts a relation
 * that holds on any machine rather than a duration that holds on one:
 * </p>
 * <ul>
 *   <li><b>rows</b> — ten times the rows may cost about ten times the per-row work, never a hundred:
 *       a quadratic step in the mapping shows up here;</li>
 *   <li><b>page</b> — a page of twenty out of a thousand costs a read of twenty, not of a thousand:
 *       the pipeline must not walk the collection the database already sliced;</li>
 *   <li><b>filter and sort</b> — translating them is per-request work, not per-row work;</li>
 *   <li><b>selection</b> — {@code fields=} must not cost more than returning the whole entity. A
 *       consumer measured the opposite: the selection resolved each field anew on every row, about
 *       1.4 µs per field and per row, so that narrowing the response made it slower
 *       ({@code demandes-externes/le-dto-ne-restreint-pas-la-lecture-mongodb.md}).</li>
 * </ul>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-api-core -Dtest='ReadQueryPerfTest' -Dgarganttua.perf=true -Djacoco.skip=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true -Djacoco.skip=true")
@DisplayName("Read cost as the query grows (mocked repository)")
class ReadQueryPerfTest extends AbstractCrudIntegrationTest {

    private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 500);
    private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 1_500);

    /** The fields a selection asks for: every mapped field but the account flags. */
    private static final List<String> SELECTION = List.of("id", "uuid", "tenantId", "name", "email",
            "enabled", "superTenant", "superOwner");

    private final List<PerfHarness.Stats> collected = new ArrayList<>();

    private static IDomain<?> usersDomain(MockDao dao) throws Exception {
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security().disable(true).up()
            .up();
        IApi api = buildAndStart(builder);
        return api.getDomain("users").orElseThrow();
    }

    private static OperationRequest readAllRequest() {
        return superTenantRequest(
                OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class)));
    }

    /** Runs the read and fails the measurement if it did not succeed — a fast error is not a fast read. */
    private static IOperationResponse ok(IDomain<?> domain, OperationRequest request) {
        IOperationResponse response = domain.invoke(request);
        if (response.getResponseCode() != OperationResponseCode.OK) {
            throw new AssertionError("read answered " + response.getResponseCode() + ": " + response.getResponse());
        }
        return response;
    }

    private static int rowsOf(IOperationResponse response) {
        Object body = response.getResponse();
        if (body instanceof List<?> list) {
            return list.size();
        }
        if (body instanceof Page page) {
            return page.entities().size();
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ten times the rows costs about ten times the per-row work, not a hundred")
    void rowsScaleLinearly() throws Exception {
        IDomain<?> hundred = usersDomain(new MockDao().seed(100));
        IDomain<?> thousand = usersDomain(new MockDao().seed(1_000));
        assertEquals(1_000, rowsOf(ok(thousand, readAllRequest())));

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — 100 rows", i -> ok(hundred, readAllRequest()),
                "readAll — 1000 rows", i -> ok(thousand, readAllRequest()),
                WARMUP / 5, RUNS / 5);
        PerfHarness.Stats small = record(both[0]);
        PerfHarness.Stats large = record(both[1]);

        // Linear per-row work plus a fixed cost gives a ratio BELOW 10; a step that walks the
        // collection once per row gives a ratio near 100. 20 leaves room for the machine.
        assertTrue(large.minUs() < small.minUs() * 20,
                () -> "1000 rows cost " + ratio(large, small) + "x 100 rows — per-row work is not linear."
                        + report());
    }

    @Test
    @DisplayName("a page of 20 out of 1000 costs a read of 20, not of 1000")
    void pagePaysForThePageOnly() throws Exception {
        IDomain<?> twenty = usersDomain(new MockDao().seed(20));
        IDomain<?> thousand = usersDomain(new MockDao().seed(1_000));

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — 20 rows, no page", i -> ok(twenty, readAllRequest()),
                "readAll — page 20 of 1000", i -> {
                    OperationRequest request = readAllRequest();
                    request.arg(IOperationRequest.PAGE, new Pageable(i % 50, 20));
                    ok(thousand, request);
                },
                WARMUP, RUNS);
        PerfHarness.Stats whole = record(both[0]);
        PerfHarness.Stats paged = record(both[1]);

        // The paged read also asks for the total count and wraps the rows in a Page, so it may cost
        // somewhat more — but 1000 rows' worth of mapping would make it fifty times more.
        assertTrue(paged.minUs() < whole.minUs() * 3,
                () -> "a page of 20 out of 1000 costs " + ratio(paged, whole)
                        + "x a read of 20 rows — the pipeline walks more than the page." + report());
    }

    @Test
    @DisplayName("a filter and a sort are translated once per request, not per row")
    void filterAndSortArePerRequest() throws Exception {
        IDomain<?> twenty = usersDomain(new MockDao().seed(20));

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — 20 rows, bare", i -> ok(twenty, readAllRequest()),
                "readAll — 20 rows, filter+sort", i -> {
                    OperationRequest request = readAllRequest();
                    request.arg(IOperationRequest.FILTER, Filter.and(
                            Filter.eq("tenantId", "SUPER_TENANT"),
                            Filter.regex("email", ".*@example\\.com"),
                            Filter.in("name", "user-1", "user-2", "user-3")));
                    request.arg(IOperationRequest.SORT, new Sort("name", SortDirection.asc));
                    ok(twenty, request);
                },
                WARMUP, RUNS);
        PerfHarness.Stats bare = record(both[0]);
        PerfHarness.Stats filtered = record(both[1]);

        assertTrue(filtered.minUs() < bare.minUs() * 3,
                () -> "a filter and a sort make a 20-row read cost " + ratio(filtered, bare)
                        + "x the bare read." + report());
        System.out.printf(Locale.ROOT, "%nA three-clause filter and a sort add %.0f us to a read.%n",
                filtered.minUs() - bare.minUs());
    }

    @Test
    @DisplayName("fields= on 500 rows does not cost more than returning the whole entity")
    void selectionIsNotSlowerThanTheEntity() throws Exception {
        IDomain<?> domain = usersDomain(new MockDao().seed(500));
        OperationRequest probe = readAllRequest();
        probe.arg(IOperationRequest.PROJECTION, SELECTION);
        assertEquals(500, rowsOf(ok(domain, probe)));

        PerfHarness.Stats[] both = PerfHarness.compare(
                "readAll — 500 rows, entity", i -> ok(domain, readAllRequest()),
                "readAll — 500 rows, fields=8", i -> {
                    OperationRequest request = readAllRequest();
                    request.arg(IOperationRequest.PROJECTION, SELECTION);
                    ok(domain, request);
                },
                WARMUP / 5, RUNS / 5);
        PerfHarness.Stats entity = record(both[0]);
        PerfHarness.Stats selected = record(both[1]);

        // The selection is built on top of the entity, so it costs a little more here (the
        // serialisation it saves happens after the pipeline). Resolving each field per row put it
        // at 500 x 8 x 1.4 us = 5.6 ms above — several times the read itself.
        assertTrue(selected.minUs() < entity.minUs() * 1.5,
                () -> "fields= costs " + ratio(selected, entity)
                        + "x the whole entity — the selection is resolved per row again." + report());
    }

    @Test
    @DisplayName("deleting by uuid")
    void deleteCost() throws Exception {
        MockDao dao = new MockDao().seed(WARMUP + RUNS);
        IDomain<?> domain = usersDomain(dao);

        record(PerfHarness.measure("deleteOne — by uuid", WARMUP, RUNS, i -> {
            OperationRequest request = superTenantRequest(
                    OperationDefinition.deleteOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.ENTITY_UUID, "uuid-" + i);
            IOperationResponse response = domain.invoke(request);
            if (response.getResponseCode() != OperationResponseCode.DELETED
                    && response.getResponseCode() != OperationResponseCode.OK) {
                throw new AssertionError("delete answered " + response.getResponseCode());
            }
        }));
        assertEquals(WARMUP + RUNS, dao.deletes(), "every run deleted one row");
        assertEquals(0, dao.size());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private PerfHarness.Stats record(PerfHarness.Stats stats) {
        this.collected.add(stats);
        return stats;
    }

    private static String ratio(PerfHarness.Stats a, PerfHarness.Stats b) {
        return String.format(Locale.ROOT, "%.1f", a.minUs() / Math.max(b.minUs(), 0.001d));
    }

    private String report() {
        return PerfHarness.report("Read cost as the query grows, mocked repository:", this.collected);
    }

    @AfterEach
    void publish() {
        if (!this.collected.isEmpty()) {
            System.out.println(report());
        }
    }
}
