package com.garganttua.api.core.unit.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.expression.SecurityRefreshExpressions;
import com.garganttua.api.core.service.OperationRequest;

/** Where a refresh finds the authorization it is handed. */
@DisplayName("presentedForRefresh")
class SecurityRefreshExpressionsTest {

    @Test
    @DisplayName("over HTTP: the token of the Authorization header, without its scheme")
    void fromTheHeader() {
        OperationRequest request = new OperationRequest(new HashMap<>());
        request.arg(IOperationRequest.RAW_AUTHORIZATION.name(), "Bearer eyJhbGciOiJFUzI1NiJ9.x.y");

        assertEquals("eyJhbGciOiJFUzI1NiJ9.x.y", SecurityRefreshExpressions.presentedForRefresh(request));
    }

    @Test
    @DisplayName("in-process: the request's entity, which wins over a header")
    void fromTheEntity() {
        Object authorization = new Object();
        OperationRequest request = new OperationRequest(new HashMap<>());
        request.arg("entity", authorization);
        request.arg(IOperationRequest.RAW_AUTHORIZATION.name(), "Bearer other");

        assertSame(authorization, SecurityRefreshExpressions.presentedForRefresh(request));
    }

    @Test
    @DisplayName("neither: refused, naming where to put it")
    void nothingPresented() {
        ApiException refused = assertThrows(ApiException.class,
                () -> SecurityRefreshExpressions.presentedForRefresh(new OperationRequest(new HashMap<>())));

        assertEquals("No authorization to refresh: present it in the Authorization header", refused.getMessage());
    }
}
