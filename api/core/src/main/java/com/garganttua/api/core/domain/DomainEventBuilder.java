package com.garganttua.api.core.domain;

import java.util.Date;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.ArgKey;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.event.Event;
import com.garganttua.api.core.service.OperationResponse;
import com.garganttua.core.reflection.IClass;

/**
 * Builds the business {@link IEvent} carried by a {@link Domain}'s observability End/Error events.
 * Only used on the observability slow path (a domain that registered {@code .events(...)} or an
 * {@code @Observer}). Extracted from {@code Domain} to keep that wide context under the file-size
 * gate; behaviour is identical.
 */
@SuppressWarnings("PMD.ReplaceJavaUtilDate") // IEvent's in/out dates are java.util.Date by contract
final class DomainEventBuilder {

    /** Arg under which the pipeline stashes the resolved {@link ICaller}. */
    private static final ArgKey<ICaller> CALLER_ARG =
            ArgKey.of("caller", IClass.getClass(ICaller.class));

    /** Arg under which the operation's input entity is carried (the business stages read "entity"). */
    private static final ArgKey<Object> ENTITY_ARG =
            ArgKey.of("entity", IClass.getClass(Object.class));

    private DomainEventBuilder() {
    }

    /**
     * Assembles the business {@link IEvent} for one invocation from the request args (body, caller,
     * tenant/owner) and the outcome (a returned {@link OperationResponse}, or a thrown
     * {@link Throwable}).
     */
    static IEvent buildEvent(IOperationRequest request, OperationDefinition operation,
            Date inDate, OperationResponse response, Throwable thrown) {
        Event event = new Event();
        event.setOperation(operation);
        event.setInDate(inDate);
        event.setOutDate(new Date());
        event.setIn(request.arg(ENTITY_ARG).orElse(request.arg(IOperationRequest.BODY).orElse(null)));
        event.setTenantId(request.arg(IOperationRequest.TENANT_ID).orElse(null));
        event.setOwnerId(request.arg(IOperationRequest.OWNER_ID).orElse(null));
        event.setUserId(request.arg(IOperationRequest.CALLER_ID).orElse(null));
        event.setCaller(request.arg(CALLER_ARG).orElse(null));

        if (response != null) {
            event.setCode(response.getResponseCode());
            Object out = response.getResponse();
            if (out instanceof Throwable t) {
                // Handled failure: the response carries the throwable, not a payload.
                event.setExceptionMessage(t.getMessage());
                event.setExceptionCode(response.getResponseCode() != null
                        ? response.getResponseCode().ordinal() : -1);
            } else {
                event.setOut(out);
            }
        }
        if (thrown != null) {
            event.setExceptionMessage(thrown.getMessage());
            event.setExceptionCode(-1);
        }
        return event;
    }
}
