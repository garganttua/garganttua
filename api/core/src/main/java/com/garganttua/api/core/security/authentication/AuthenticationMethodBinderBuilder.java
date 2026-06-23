package com.garganttua.api.core.security.authentication;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.core.reflection.annotations.Reflected;

import com.garganttua.api.commons.context.dsl.security.IAuthenticationBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticationMethodBinderBuilder;
import com.garganttua.api.commons.security.authentication.IAuthentication;

import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.reflection.binders.dsl.AbstractMethodBinderBuilder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

@Reflected
@SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "Validating builder constructor throws on invalid input by design; the class declares no finalizer, so the finalizer-attack premise does not apply.")
@SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
public class AuthenticationMethodBinderBuilder<ExecutionReturn> extends AbstractMethodBinderBuilder<ExecutionReturn, IAuthenticationMethodBinderBuilder<ExecutionReturn>, IAuthenticationBuilder<?>, IMethodBinder<ExecutionReturn>> implements IAuthenticationMethodBinderBuilder<ExecutionReturn>{

    private String authenticateMethodName;

    public AuthenticationMethodBinderBuilder(IAuthenticationBuilder<?> up, ISupplierBuilder<?, ? extends ISupplier<?>> supplier) {
        super(up, supplier, Set.of());
    }

    public AuthenticationMethodBinderBuilder(IAuthenticationBuilder<?> up, ISupplierBuilder<?, ? extends ISupplier<?>> supplier, String methodName) {
        super(up, supplier, Set.of());
        this.authenticateMethodName = methodName;
        this.method(methodName, null);
    }

    @Override
    public String getAuthenticateMethodName() {
        return this.authenticateMethodName;
    }

    @Override
    protected void doAutoDetection() throws ApiException {
        // No auto-detection for authentication method binders - all configuration is explicit
    }

    @Override
    protected void doPreBuildWithDependency_(Object dependency) {
    }

    @Override
    protected void doPostBuildWithDependency(Object dependency) {
    }

    @Override
    protected void doAutoDetectionWithDependency(Object dependency) {
    }

}
