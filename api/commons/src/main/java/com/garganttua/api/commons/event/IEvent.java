package com.garganttua.api.commons.event;

import java.util.Date;
import java.util.Map;

import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;

// java.util.Date is part of this public event contract; consumers depend on these signatures.
@SuppressWarnings("PMD.ReplaceJavaUtilDate")
public interface IEvent {

	OperationDefinition getOperation();
    void setOperation(OperationDefinition operation);

    Date getInDate();
    void setInDate(Date inDate);

    Date getOutDate();
    void setOutDate(Date outDate);

    int getExceptionCode();
    void setExceptionCode(int exceptionCode);

    Map<String, String> getInParams();
    void setInParams(Map<String, String> inParams);

    Object getIn();
    void setIn(Object in);

    Object getOut();
    void setOut(Object out);

    ICaller getCaller();
    void setCaller(ICaller caller);

    String getTenantId();
    void setTenantId(String tenantId);

    String getOwnerId();
    void setOwnerId(String ownerId);

    String getUserId();
    void setUserId(String userId);

    String getExceptionMessage();
    void setExceptionMessage(String exceptionMessage);

    OperationResponseCode getCode();
    void setCode(OperationResponseCode code);

    /**
     * The uuid of the entity the operation was addressed to — the {@code {uuid}} of the path
     * ({@code IOperationRequest.ENTITY_UUID}) for a {@code readOne}, an {@code update} or a
     * {@code deleteOne}; {@code null} when the operation targets no single entity (a create, a
     * {@code readAll}, a {@code deleteAll}).
     *
     * <p>It is the ONLY place a consumer can learn which entity a {@code deleteOne} removed (its
     * {@link #getIn() in} is {@code null}: a DELETE has no body) or which entity a partial update
     * touched (a PATCH body rarely repeats its uuid).
     *
     * <p>Default methods, so that an existing implementation keeps compiling and simply reports
     * {@code null}.
     */
    default String getEntityUuid() {
        return null;
    }

    /** @see #getEntityUuid() — ignored by implementations that do not carry it. */
    default void setEntityUuid(String entityUuid) {
        // not carried by default
    }
    
    IOperationResponse toServiceResponse();
    
	/* IDomain getDomain();
	void setDomain(IDomain domain);
	 */
}
