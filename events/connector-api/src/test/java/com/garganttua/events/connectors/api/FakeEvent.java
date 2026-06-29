package com.garganttua.events.connectors.api;

import java.util.Date;
import java.util.Map;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;

/**
 * Minimal mutable {@link IEvent} stub for unit tests; carries only the fields the api connector
 * serialises.
 */
final class FakeEvent implements IEvent {

	private String tenantId;
	private String ownerId;
	private String userId;
	private Object in;
	private Object out;
	private OperationResponseCode code;
	private OperationDefinition operation;

	@Override
	public OperationDefinition getOperation() {
		return this.operation;
	}

	@Override
	public void setOperation(OperationDefinition operation) {
		this.operation = operation;
	}

	@Override
	public Date getInDate() {
		return null;
	}

	@Override
	public void setInDate(Date inDate) {
		// unused
	}

	@Override
	public Date getOutDate() {
		return null;
	}

	@Override
	public void setOutDate(Date outDate) {
		// unused
	}

	@Override
	public int getExceptionCode() {
		return 0;
	}

	@Override
	public void setExceptionCode(int exceptionCode) {
		// unused
	}

	@Override
	public Map<String, String> getInParams() {
		return null;
	}

	@Override
	public void setInParams(Map<String, String> inParams) {
		// unused
	}

	@Override
	public Object getIn() {
		return this.in;
	}

	@Override
	public void setIn(Object in) {
		this.in = in;
	}

	@Override
	public Object getOut() {
		return this.out;
	}

	@Override
	public void setOut(Object out) {
		this.out = out;
	}

	@Override
	public ICaller getCaller() {
		return null;
	}

	@Override
	public void setCaller(ICaller caller) {
		// unused
	}

	@Override
	public String getTenantId() {
		return this.tenantId;
	}

	@Override
	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	@Override
	public String getOwnerId() {
		return this.ownerId;
	}

	@Override
	public void setOwnerId(String ownerId) {
		this.ownerId = ownerId;
	}

	@Override
	public String getUserId() {
		return this.userId;
	}

	@Override
	public void setUserId(String userId) {
		this.userId = userId;
	}

	@Override
	public String getExceptionMessage() {
		return null;
	}

	@Override
	public void setExceptionMessage(String exceptionMessage) {
		// unused
	}

	@Override
	public OperationResponseCode getCode() {
		return this.code;
	}

	@Override
	public void setCode(OperationResponseCode code) {
		this.code = code;
	}

	@Override
	public IOperationResponse toServiceResponse() {
		return null;
	}
}
