package com.garganttua.api.core.integ.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.operation.BusinessOperation;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.usecase.injection.UseCaseInput;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Covers a use case that REFUSES: it throws, and the caller must learn that it did.
 *
 * <p>
 * Reported by a consumer as {@code HTTP 200} with the body {@code 0} — a success, for a gesture the
 * server declined, with the refusal message reaching no one. A user clicked "Transmit" for half a
 * day against a server that refused every time, and the screen said nothing.
 * </p>
 *
 * <p>
 * The mechanism: {@code MethodInvoker.invokeMethodSafely} CAPTURES what the bound method threw
 * rather than throwing it, so {@code single()} answered {@code null}, {@code USE_CASE.gs}'s
 * {@code ! => … -> 500} never fired, and the workflow exited 0 — the {@code 0} the consumer saw
 * being that exit code served as the payload.
 * </p>
 */
@DisplayName("A use case that refuses")
class UseCaseRefusalIntegrationTest extends AbstractCrudScriptTest {

    public static class Order {
        private String uuid;
        public Order() {}
        public Order(String uuid) { this.uuid = uuid; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
    }

    /** The shape a consumer writes: a business rule declining, with a message meant for a human. */
    public static class TransmissionService {

        static final String REFUSAL = "Cette facture est un brouillon : émettez-la d'abord.";

        /** A bare ApiException — a failure with no chosen status. */
        public Order transmit(@UseCaseInput Order input) {
            throw new ApiException(REFUSAL);
        }

        /** The same refusal, declared as a client error through the factory. */
        public Order transmitAsClientError(@UseCaseInput Order input) {
            throw ApiException.badRequest(REFUSAL);
        }

        /** Anything else the method may throw — not an ApiException at all. */
        public Order explode(@UseCaseInput Order input) {
            throw new IllegalStateException("boom");
        }

        public Order succeed(@UseCaseInput Order input) {
            return new Order("ok");
        }
    }

    private IDomain<?> orders;

    @BeforeEach
    void setUp() throws ApiException {
        IApiBuilder builder = newBuilder();
        var domain = builder.domain(IClass.getClass(User.class))
                .tenant(true)
                .superTenant("superTenant")
                .entity()
                    .id("id").uuid("uuid").tenantId("tenantId")
                .up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(new CapturingDao())
                .up();
        for (String name : new String[] { "transmit", "transmitAsClientError", "explode", "succeed" }) {
            domain.useCase(name, IClass.getClass(Order.class), IClass.getClass(Order.class))
                    .bind(new TransmissionService())
                        .method(name, IClass.getClass(Order.class), IClass.getClass(Order.class))
                    .up()
                .up();
        }
        domain.security().disable(true).up().up();

        IApi context = buildAndStart(builder);
        orders = context.getDomain("users").orElseThrow();
    }

    private OperationDefinition useCaseOperation(String name) {
        return orders.getDomainDefinition().operations().stream()
                .filter(op -> op.getBusinessOperation() == BusinessOperation.useCase)
                .filter(op -> name.equals(op.useCaseName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not exposed"));
    }

    private WorkflowResult run(String useCase) {
        OperationRequest request = superTenantScriptRequest(useCaseOperation(useCase));
        request.arg("entity", new Order("order-1"));
        return executeScript(orders, request);
    }

    private IOperationResponse invoke(String useCase) {
        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION, useCaseOperation(useCase));
        request.arg(IOperationRequest.BODY, new Order("order-1"));
        return orders.invoke(request);
    }

    @Nested
    @DisplayName("the refusal reaches the caller")
    class RefusalSurfaces {

        @Test
        @DisplayName("a thrown ApiException fails the operation instead of answering a success")
        void refusalIsNotASuccess() {
            WorkflowResult result = run("transmit");

            assertFalse(result.isSuccess(),
                    "a refused gesture must not answer a success — this is the reported defect");
            assertEquals(500, result.code(), "a bare ApiException keeps the stage's own status");
        }

        @Test
        @DisplayName("the message the use case wrote is carried to the response")
        void refusalMessageIsCarried() {
            IOperationResponse response = invoke("transmit");

            assertEquals(OperationResponseCode.SERVER_ERROR, response.getResponseCode());
            Throwable carried = assertInstanceOf(Throwable.class, response.getResponse(),
                    "the failure must carry its exception, not a bare value");
            assertEquals(TransmissionService.REFUSAL, carried.getMessage(),
                    "the message the consumer wrote must reach the caller — losing it is what left a "
                            + "user clicking a button that silently did nothing");
        }

        @Test
        @DisplayName("a chosen status is honoured: ApiException.badRequest gives a client error")
        void chosenStatusIsHonoured() {
            IOperationResponse response = invoke("transmitAsClientError");

            assertEquals(OperationResponseCode.CLIENT_ERROR, response.getResponseCode(),
                    "the factory is how a consumer says 'this is a refusal, not an outage'");
            assertEquals(TransmissionService.REFUSAL,
                    assertInstanceOf(Throwable.class, response.getResponse()).getMessage());
        }

        @Test
        @DisplayName("an exception that is not an ApiException surfaces too, wrapped")
        void foreignExceptionSurfaces() {
            IOperationResponse response = invoke("explode");

            assertEquals(OperationResponseCode.SERVER_ERROR, response.getResponseCode());
            Throwable carried = assertInstanceOf(Throwable.class, response.getResponse());
            assertNotNull(carried.getMessage());
            assertTrue(carried.getMessage().contains("boom"),
                    () -> "the original cause must remain readable; got: " + carried.getMessage());
        }
    }

    @Test
    @DisplayName("a use case that succeeds still returns its result")
    void successIsUnaffected() {
        WorkflowResult result = run("succeed");

        assertTrue(result.isSuccess(), () -> "code=" + result.code());
        assertEquals("ok", assertInstanceOf(Order.class, result.output()).getUuid());
    }
}
