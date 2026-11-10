package com.garganttua.api.core.integ;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.event.RequestOrigin;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.observability.EndEvent;
import com.garganttua.core.observability.GlobalObservers;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.observability.StartEvent;
import com.garganttua.core.reflection.IClass;

/**
 * What an operation leaves for an access log: its origin in the business event, and — when a BLOCKING
 * observer declines to record it — a refusal instead of the data.
 */
@DisplayName("Operation trace — origin in the event, and no service without a record")
class OperationTraceTest extends AbstractCrudIntegrationTest {

    /** Counts what reaches the database. */
    public static class CountingDao extends CapturingDao {
        int finds;

        @Override
        public List<Object> find(java.util.Optional<com.garganttua.api.commons.pageable.IPageable> pageable,
                java.util.Optional<com.garganttua.api.commons.filter.IFilter> filter,
                java.util.Optional<com.garganttua.api.commons.sort.ISort> sort) throws ApiException {
            this.finds++;
            return super.find(pageable, filter, sort);
        }
    }

    private final List<IObserver<ObservableEvent>> registered = new ArrayList<>();
    private CountingDao dao;

    @AfterEach
    void unregister() {
        this.registered.forEach(GlobalObservers::removeObserver);
        this.registered.clear();
    }

    private IDomain<?> users() throws ApiException {
        this.dao = new CountingDao();
        IApiBuilder builder = newBuilder();
        builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(this.dao)
                .up()
                .security().disable(true).up()
            .up();
        IApi api = buildAndStart(builder);
        UserDto john = new UserDto();
        john.setId("john");
        john.setUuid("user-1");
        john.setTenantId("SUPER_TENANT");
        this.dao.save(john);
        return api.getDomain("users").orElseThrow();
    }

    private static OperationRequest readAll() {
        return superTenantRequest(OperationDefinition.readAll("users", IClass.getClass(User.class), false, null,
                com.garganttua.api.commons.operation.Access.anonymous));
    }

    private List<IEvent> listen() {
        List<IEvent> events = Collections.synchronizedList(new ArrayList<>());
        IObserver<ObservableEvent> observer = event -> {
            if (event instanceof EndEvent && event.payload() instanceof IEvent business) {
                events.add(business);
            }
        };
        GlobalObservers.addObserver(observer);
        this.registered.add(observer);
        return events;
    }

    private void blocking(IObserver<ObservableEvent> observer) {
        GlobalObservers.addBlockingObserver(observer);
        this.registered.add(observer);
    }

    @Nested
    @DisplayName("origin")
    class Origin {

        @Test
        @DisplayName("the event carries the origin the transport saw")
        void transportOrigin() throws Exception {
            IDomain<?> users = users();
            List<IEvent> events = listen();
            OperationRequest request = readAll();
            request.arg(IOperationRequest.ORIGIN, new RequestOrigin("198.51.100.20", "Mozilla/5.0", null));

            users.invoke(request);

            RequestOrigin origin = events.get(0).getOrigin();
            assertEquals("198.51.100.20", origin.address());
            assertEquals("Mozilla/5.0", origin.userAgent());
            assertNull(origin.sessionId(), "no authorization verified, no session");
        }

        @Test
        @DisplayName("in the session verification recorded")
        void session() throws Exception {
            IDomain<?> users = users();
            List<IEvent> events = listen();
            OperationRequest request = readAll();
            request.arg(IOperationRequest.ORIGIN, new RequestOrigin("198.51.100.20", "Mozilla/5.0", null));
            request.arg(IOperationRequest.SESSION_ID, "authz-42");

            users.invoke(request);

            assertEquals(new RequestOrigin("198.51.100.20", "Mozilla/5.0", "authz-42"), events.get(0).getOrigin());
        }

        @Test
        @DisplayName("an in-process invocation has no origin")
        void inProcess() throws Exception {
            IDomain<?> users = users();
            List<IEvent> events = listen();

            users.invoke(readAll());

            assertNull(events.get(0).getOrigin());
        }
    }

    @Nested
    @DisplayName("a blocking observer that cannot record")
    class Blocking {

        @Test
        @DisplayName("the end of the operation: 503, and the data is not served")
        void atTheEnd() throws Exception {
            IDomain<?> users = users();
            List<IEvent> ordinary = listen();
            blocking(event -> {
                if (event instanceof EndEvent) {
                    throw new IllegalStateException("broker down");
                }
            });

            IOperationResponse response = users.invoke(readAll());

            assertEquals(OperationResponseCode.NOT_AVAILABLE, response.getResponseCode());
            assertTrue(response.getResponse() instanceof ApiException failure
                    && failure.getMessage().contains("broker down"), () -> String.valueOf(response.getResponse()));
            assertEquals(1, ordinary.size(), "an ordinary observer still saw the operation");
        }

        @Test
        @DisplayName("its start: 503, and the operation does not run")
        void atTheStart() throws Exception {
            IDomain<?> users = users();
            blocking(event -> {
                if (event instanceof StartEvent) {
                    throw new IllegalStateException("broker down");
                }
            });

            int before = OperationTraceTest.this.dao.finds;

            IOperationResponse response = users.invoke(readAll());

            assertEquals(OperationResponseCode.NOT_AVAILABLE, response.getResponseCode());
            assertEquals(before, OperationTraceTest.this.dao.finds, "nothing was read");
        }

        @Test
        @DisplayName("a blocking observer that records: the operation is served as before")
        void recording() throws Exception {
            IDomain<?> users = users();
            List<ObservableEvent> recorded = Collections.synchronizedList(new ArrayList<>());
            blocking(recorded::add);

            IOperationResponse response = users.invoke(readAll());

            assertEquals(OperationResponseCode.OK, response.getResponseCode());
            assertNotNull(response.getResponse());
            assertTrue(recorded.stream().anyMatch(EndEvent.class::isInstance));
        }
    }
}
