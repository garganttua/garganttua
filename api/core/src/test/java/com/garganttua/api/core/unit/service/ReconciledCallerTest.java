package com.garganttua.api.core.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.service.OperationRequest;

/**
 * Covers {@code IOperationRequest.caller()}, reported by a consumer as rebuilding the caller from
 * the protocol headers instead of returning the one the verification stage reconciled.
 *
 * <p>
 * The consequence was that any membership check written against {@code ICaller} compared the
 * caller to a value the caller itself had chosen ({@code X-Tenant-Id}) — a control that looks like
 * it protects something and protects nothing. {@code VERIFY_AUTHORIZATION.gs} has always published
 * the reconciled caller; nothing read it.
 * </p>
 */
@DisplayName("IOperationRequest.caller() — the verified caller wins")
class ReconciledCallerTest {

    private static OperationRequest requestAnnouncing(String headerTenant) {
        OperationRequest request = new OperationRequest(new HashMap<>());
        request.arg(IOperationRequest.TENANT_ID, headerTenant);
        request.arg(IOperationRequest.REQUESTED_TENANT_ID, headerTenant);
        request.arg(IOperationRequest.CALLER_ID, "someone");
        return request;
    }

    @Test
    @DisplayName("the reconciled caller is returned, not one rebuilt from the headers")
    void reconciledCallerWins() {
        OperationRequest request = requestAnnouncing("0");
        ICaller verified = ICaller.of("team-7", "team-7", "someone", null, null, false, false,
                List.of("patient-read"));
        request.arg(IOperationRequest.CALLER, verified);

        ICaller caller = request.caller();

        assertSame(verified, caller);
        assertEquals("team-7", caller.tenantId(),
                "the tenant is the token's, not the X-Tenant-Id the client announced");
        assertEquals(List.of("patient-read"), caller.authorities());
    }

    @Test
    @DisplayName("without a verified caller it still falls back to the protocol args")
    void fallsBackForAnUnverifiedOperation() {
        OperationRequest request = requestAnnouncing("0");

        ICaller caller = request.caller();

        assertNotNull(caller, "an anonymous or framework-internal operation still gets a caller");
        assertEquals("0", caller.tenantId());
        assertEquals("someone", caller.callerId());
    }

    @Test
    @DisplayName("a later reconciliation supersedes an earlier seeding — the last word is the server's")
    void reconciliationSupersedesTheSeededCaller() {
        OperationRequest request = requestAnnouncing("0");
        request.arg(IOperationRequest.CALLER,
                ICaller.of("0", "0", "someone", null, null, false, false, List.of()));

        // What VERIFY_AUTHORIZATION does once it has folded the protocol caller into the token.
        request.arg(IOperationRequest.CALLER,
                ICaller.of("team-7", "team-7", "someone", null, null, false, false, List.of("admin")));

        assertEquals("team-7", request.caller().tenantId());
        assertEquals(List.of("admin"), request.caller().authorities());
    }

    @Test
    @DisplayName("the caller a use case receives is the same object the request answers with")
    void theSuppliersSeeTheSameCaller() {
        OperationRequest request = requestAnnouncing("0");
        ICaller verified = ICaller.of("team-7", null, "someone", "users:u-1", null, false, false, List.of());
        request.arg(IOperationRequest.CALLER, verified);

        // CallerSupplier / TenantSupplier / OwnerIdSupplier / AuthoritiesSupplier / LoginSupplier all
        // read request.caller(); fixing it once fixes the five of them.
        assertEquals("team-7", request.caller().tenantId());
        assertEquals("users:u-1", request.caller().ownerId());
        assertEquals("someone", request.caller().callerId());
    }
}
