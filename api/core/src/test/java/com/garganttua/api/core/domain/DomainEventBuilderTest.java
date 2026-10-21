package com.garganttua.api.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.service.IOperationRequest;

/**
 * The business event carries the uuid the operation was ADDRESSED to (path {@code {uuid}}), so that
 * a consumer can tell which entity a {@code deleteOne} removed — its {@code in} is null, a DELETE
 * has no body — or which entity a PATCH without uuid in its body touched.
 */
@DisplayName("DomainEventBuilder — entityUuid")
class DomainEventBuilderTest {

	@Test
	@DisplayName("copies IOperationRequest.ENTITY_UUID onto the event")
	void copiesTheAddressedUuid() {
		IOperationRequest request = IOperationRequest.create();
		request.arg(IOperationRequest.ENTITY_UUID, "0199-abc");

		IEvent event = DomainEventBuilder.buildEvent(request, null, new Date(), null, null);

		assertEquals("0199-abc", event.getEntityUuid());
		assertNull(event.getIn(), "no body: the uuid is the only trace of the deleted entity");
	}

	@Test
	@DisplayName("leaves it null when the operation addresses no single entity (create, readAll, deleteAll)")
	void nullWithoutAddressedUuid() {
		IEvent event = DomainEventBuilder.buildEvent(IOperationRequest.create(), null, new Date(), null, null);

		assertNull(event.getEntityUuid());
	}
}
