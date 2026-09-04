package com.garganttua.api.commons.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.CoreException;

/**
 * Covers the classification of an {@link ApiException} into the code that reports it.
 *
 * <p>
 * The switch this exercises used to have no cases at all: every exception, including a business
 * rule deliberately declining a write, came back as a server error.
 * </p>
 */
@DisplayName("OperationResponseCode.fromExceptionCode")
class OperationResponseCodeTest {

    /** Stands in for any core-layer failure that reaches the API carrying its own diagnostic code. */
    private static final class ReflectionFailure extends CoreException {
        private static final long serialVersionUID = 1L;

        ReflectionFailure() {
            super(CoreException.REFLECTION_ERROR, "no such field");
        }
    }

    @Nested
    @DisplayName("a deliberately-chosen status")
    class ChosenStatus {

        @Test
        @DisplayName("badRequest is reported as a client error, not a server error")
        void badRequestIsClientError() {
            ApiException e = ApiException.badRequest("Le champ « nom » est obligatoire.");

            assertEquals(OperationResponseCode.CLIENT_ERROR, OperationResponseCode.fromExceptionCode(e));
            assertEquals("Le champ « nom » est obligatoire.", e.getMessage(),
                    "the message a consumer wrote must survive the classification");
        }

        @Test
        @DisplayName("each factory maps to its own response code")
        void everyFactoryMapsToItsCode() {
            assertEquals(OperationResponseCode.CLIENT_ERROR,
                    OperationResponseCode.fromExceptionCode(ApiException.badRequest("x")));
            assertEquals(OperationResponseCode.UNAUTHORIZED,
                    OperationResponseCode.fromExceptionCode(ApiException.unauthorized("x")));
            assertEquals(OperationResponseCode.FORBIDDEN,
                    OperationResponseCode.fromExceptionCode(ApiException.forbidden("x")));
            assertEquals(OperationResponseCode.NOT_FOUND,
                    OperationResponseCode.fromExceptionCode(ApiException.notFound("x")));
            assertEquals(OperationResponseCode.NOT_ACCEPTABLE,
                    OperationResponseCode.fromExceptionCode(ApiException.notAcceptable("x")));
            assertEquals(OperationResponseCode.CONFLICT,
                    OperationResponseCode.fromExceptionCode(ApiException.conflict("x")));
        }

        @Test
        @DisplayName("a chosen status is advertised by hasExplicitStatus")
        void chosenStatusIsAdvertised() {
            assertTrue(ApiException.badRequest("x").hasExplicitStatus());
            assertTrue(ApiException.conflict("x").hasExplicitStatus());
            assertTrue(ApiException.of(ApiException.FORBIDDEN, "x").hasExplicitStatus());
        }

        @Test
        @DisplayName("a cause is carried through, so the original failure stays reachable")
        void causeIsCarried() {
            IllegalStateException root = new IllegalStateException("root");
            ApiException e = ApiException.badRequest("refused", root);

            assertEquals(root, e.getCause());
            assertEquals(OperationResponseCode.CLIENT_ERROR, OperationResponseCode.fromExceptionCode(e));
        }
    }

    @Nested
    @DisplayName("everything else stays a server error")
    class ServerErrorDefault {

        @Test
        @DisplayName("a bare ApiException is a server error and claims no status of its own")
        void bareExceptionIsServerError() {
            ApiException e = new ApiException("something broke");

            assertEquals(OperationResponseCode.SERVER_ERROR, OperationResponseCode.fromExceptionCode(e));
            assertFalse(e.hasExplicitStatus(),
                    "a bare exception must not override the pipeline stage's own status");
        }

        @Test
        @DisplayName("a code inherited from a wrapped core failure is a diagnostic, not a chosen status")
        void inheritedCoreCodeIsNotAStatus() {
            ApiException wrapped = ApiException.wrap(new ReflectionFailure());

            assertEquals(CoreException.REFLECTION_ERROR, wrapped.getCode(),
                    "the diagnostic code is preserved for troubleshooting");
            assertFalse(wrapped.hasExplicitStatus(),
                    "but it is not a status: a reflection failure must not be reported as an HTTP 3");
            assertEquals(OperationResponseCode.SERVER_ERROR,
                    OperationResponseCode.fromExceptionCode(wrapped));
        }

        @Test
        @DisplayName("wrap keeps an already-chosen status intact")
        void wrapKeepsChosenStatus() {
            ApiException chosen = ApiException.conflict("duplicate");

            assertEquals(chosen, ApiException.wrap(chosen));
            assertEquals(OperationResponseCode.CONFLICT,
                    OperationResponseCode.fromExceptionCode(ApiException.wrap(chosen)));
        }
    }
}
