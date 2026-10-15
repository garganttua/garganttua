package com.garganttua.api.core.perfs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.reflection.IClass;

/**
 * What a request pays because something listens to the api's operations — an events connector,
 * typically, which subscribes to the global firehose to forward each operation onto a bus.
 *
 * <p>
 * With no listener, {@code Domain.invoke} takes a fast path. With one, every operation gets an
 * execution id, a start and an end event, and a business {@link IEvent} built from the request and
 * the response. That price is paid by EVERY request of EVERY domain, on the request thread, as soon
 * as one connector is attached — whatever the connector does with the event afterwards. This test
 * isolates the framework's share of it with a listener that does nothing but count; what a real
 * connector adds on top (encoding, queueing) is measured on the events side, by the api
 * connector's own performance test.
 * </p>
 *
 * <pre>{@code
 * mvn -o test -pl :garganttua-api-core -Dtest='EventEmissionPerfTest' -Dgarganttua.perf=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "garganttua.perf", matches = "true",
        disabledReason = "performance measurement — opt in with -Dgarganttua.perf=true")
@DisplayName("Cost of a firehose listener on every request (mocked repository)")
class EventEmissionPerfTest extends AbstractCrudIntegrationTest {

    private static final int WARMUP = Integer.getInteger("garganttua.perf.warmup", 2_000);
    private static final int RUNS = Integer.getInteger("garganttua.perf.runs", 5_000);

    private final List<PerfHarness.Stats> collected = new ArrayList<>();

    /** Counts the business events it receives, and nothing else — the cheapest possible listener. */
    private static final class CountingListener implements IObserver<ObservableEvent> {
        final AtomicLong businessEvents = new AtomicLong();

        @Override
        public void onEvent(ObservableEvent event) {
            if (event instanceof EndEvent && event.payload() instanceof IEvent) {
                this.businessEvents.incrementAndGet();
            }
        }
    }

    private static IDomain<?> usersDomain() throws Exception {
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(new MockDao().seed(100))
                .up()
                .security().disable(true).up()
            .up();
        IApi api = buildAndStart(builder);
        return api.getDomain("users").orElseThrow();
    }

    private static void readOne(IDomain<?> domain, int i) {
        OperationRequest request = superTenantRequest(
                OperationDefinition.readOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-" + (i % 100));
        domain.invoke(request);
    }

    @Test
    @DisplayName("a listener on the firehose adds a bounded cost to every request")
    void listenerCost() throws Exception {
        IDomain<?> domain = usersDomain();
        CountingListener listener = new CountingListener();
        assertTrue(!GlobalObservers.hasObservers(),
                "another test left a firehose listener attached — the baseline would not be the fast path");

        PerfHarness.Stats[] both;
        // Attached and detached around each run of the second scenario, so that both are measured
        // interleaved on the same domain: the only difference between a and b is the listener.
        both = PerfHarness.compare(
                "readOne — nobody listening", i -> readOne(domain, i),
                "readOne — one firehose listener", i -> {
                    GlobalObservers.addObserver(listener);
                    try {
                        readOne(domain, i);
                    } finally {
                        GlobalObservers.removeObserver(listener);
                    }
                },
                WARMUP, RUNS);
        PerfHarness.Stats silent = record(both[0]);
        PerfHarness.Stats listened = record(both[1]);

        assertTrue(listener.businessEvents.get() >= RUNS,
                "the listener received one business event per request (" + listener.businessEvents.get() + ")");
        System.out.printf(Locale.ROOT,
                "%nA firehose listener adds %.1f us to a readOne (floor %.1f -> %.1f us).%n",
                listened.minUs() - silent.minUs(), silent.minUs(), listened.minUs());
        // Building the events is a fraction of the pipeline it describes, not a second pipeline.
        assertTrue(listened.minUs() < silent.minUs() * 1.5,
                () -> String.format(Locale.ROOT, "a listener makes a readOne cost %.2fx — emitting the "
                        + "operation's events costs as much as running it.%s",
                        listened.minUs() / silent.minUs(), report()));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private PerfHarness.Stats record(PerfHarness.Stats stats) {
        this.collected.add(stats);
        return stats;
    }

    private String report() {
        return PerfHarness.report("Firehose listener cost, mocked repository:", this.collected);
    }

    @AfterEach
    void publish() {
        if (!this.collected.isEmpty()) {
            System.out.println(report());
        }
    }
}
