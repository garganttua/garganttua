package com.garganttua.api.core.security.authorization;

import java.lang.reflect.Type;
import java.util.Optional;

import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.runtime.IRuntimeContext;
import com.garganttua.core.supply.IContextualSupplier;
import com.garganttua.core.supply.SupplyException;

/**
 * Supplies a custom issuer with the authorization a refresh is replacing — decoded and checked — so that it can
 * carry over what it put in it at login (a name, the way the person logged in). Empty at login: the issuer tells
 * the two apart by it.
 */
public class RefreshedAuthorizationSupplier implements IContextualSupplier<Object, IRuntimeContext> {

    /** The request arg REFRESH_AUTHORIZATION puts the replaced authorization under. */
    public static final String ARG = "refreshedAuthorization";

    private static final IClass<Object> SUPPLIED_CLASS = IClass.getClass(Object.class);
    @SuppressWarnings("rawtypes")
    private static final IClass<IRuntimeContext> CONTEXT_CLASS = IClass.getClass(IRuntimeContext.class);

    @Override
    public Type getSuppliedType() {
        return SUPPLIED_CLASS.getType();
    }

    @Override
    public IClass<Object> getSuppliedClass() {
        return SUPPLIED_CLASS;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public IClass<IRuntimeContext> getOwnerContextType() {
        return CONTEXT_CLASS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Object> supply(IRuntimeContext context, Object... otherContexts) throws SupplyException {
        if (context == null) {
            throw new SupplyException("IRuntimeContext cannot be null");
        }
        Optional<?> request = context.getVariable("request", IClass.getClass(IOperationRequest.class));
        return request.map(r -> ((IOperationRequest) r).arg(ARG).orElse(null));
    }
}
