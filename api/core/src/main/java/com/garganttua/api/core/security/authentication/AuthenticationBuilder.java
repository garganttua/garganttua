package com.garganttua.api.core.security.authentication;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.usecase.UseCaseBuilder;
import com.garganttua.core.reflection.annotations.Reflected;

import java.lang.annotation.Annotation;
import com.garganttua.core.reflection.IMethod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.javatuples.Pair;

import com.garganttua.api.core.security.authentication.AuthenticationMethodBinderBuilder;
import com.garganttua.api.core.security.authentication.AuthenticationContext;
import com.garganttua.api.core.security.authentication.AuthenticationDefinition;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IUseCaseBuilder;
import com.garganttua.api.commons.context.dsl.security.IApiSecurityBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticationBuilder;
import com.garganttua.api.commons.context.dsl.security.IAuthenticationMethodBinderBuilder;
import com.garganttua.api.commons.security.authentication.IAuthentication;
import com.garganttua.api.commons.security.context.IAuthenticationContext;
import com.garganttua.core.dsl.AbstractAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

@Reflected
@SuppressFBWarnings(value = {"IS2_INCONSISTENT_SYNC"}, justification = "Single-threaded DSL builder used once during configuration; doBuild() is synchronized to publish a consistent snapshot, the fluent setters run before build on the same thread.")
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public class AuthenticationBuilder<E> extends AbstractAutomaticLinkedBuilder<IAuthenticationBuilder<E>, E, IAuthenticationContext> implements IAuthenticationBuilder<E> {

    private static final String METHOD_NAME_REQUIRED = "Method name cannot be null";
    private static final String METHOD_REQUIRED = "Method cannot be null";
    private static final String METHOD_ADDRESS_REQUIRED = "Method address cannot be null";

    private ISupplierBuilder<?, ? extends ISupplier<?>> supplier;
    private IAuthenticationMethodBinderBuilder<?> authenticate;
    private List<Pair<IClass<? extends Annotation>, IClass<?>>> fieldAnnotations = new ArrayList<>();
    private IAuthenticationMethodBinderBuilder<?> applySecurityOnEntity;
    private IAuthenticationMethodBinderBuilder<?> refresh;
    private Map<String, IUseCaseBuilder<?, ?, ?>> useCases = new HashMap<>();

    protected AuthenticationBuilder(E link) {
        super(link);
    }

    public AuthenticationBuilder(E link, ISupplierBuilder<?, ? extends ISupplier<?>> supplier) {
        super(link);
        this.supplier = Objects.requireNonNull(supplier, "Supplier cannot be null");
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> authenticate(String methodName) throws ApiException {
        Objects.requireNonNull(methodName, METHOD_NAME_REQUIRED);
        this.authenticate = new AuthenticationMethodBinderBuilder<>(this, this.supplier, methodName);
        return this.authenticate;
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> authenticate(IMethod method) throws ApiException {
        Objects.requireNonNull(method, METHOD_REQUIRED);
        this.authenticate = new AuthenticationMethodBinderBuilder<>(this, this.supplier, method.getName());
        return this.authenticate;
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> authenticate(ObjectAddress methodAddress) throws ApiException {
        Objects.requireNonNull(methodAddress, METHOD_ADDRESS_REQUIRED);
        String methodName = methodAddress.getElement(methodAddress.length() - 1);
        this.authenticate = new AuthenticationMethodBinderBuilder<>(this, this.supplier, methodName);
        return this.authenticate;
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> refresh(String methodName) throws ApiException {
        Objects.requireNonNull(methodName, METHOD_NAME_REQUIRED);
        this.refresh = new AuthenticationMethodBinderBuilder<>(this, this.supplier, methodName);
        return this.refresh;
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> refresh(IMethod method) throws ApiException {
        Objects.requireNonNull(method, METHOD_REQUIRED);
        return refresh(method.getName());
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> refresh(ObjectAddress methodAddress) throws ApiException {
        Objects.requireNonNull(methodAddress, METHOD_ADDRESS_REQUIRED);
        return refresh(methodAddress.getElement(methodAddress.length() - 1));
    }

    @Override
    public IAuthenticationBuilder<E> entityMustHaveFieldOfTypeAnnotatedWith(IClass<? extends Annotation> annotation,
            IClass<?> fieldType) throws ApiException {
        Objects.requireNonNull(annotation, "Annotation cannot be null");
        Objects.requireNonNull(fieldType, "Field type cannot be null");
        this.fieldAnnotations.add(new Pair<>(annotation, fieldType));
        return this;
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> applySecurityOnEntity(String methodName) throws ApiException {
        Objects.requireNonNull(methodName, METHOD_NAME_REQUIRED);
        return bindApplySecurityOnEntity(methodName);
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> applySecurityOnEntity(IMethod method) throws ApiException {
        Objects.requireNonNull(method, METHOD_REQUIRED);
        return bindApplySecurityOnEntity(method.getName());
    }

    @Override
    public IAuthenticationMethodBinderBuilder<?> applySecurityOnEntity(ObjectAddress methodAddress) throws ApiException {
        Objects.requireNonNull(methodAddress, METHOD_ADDRESS_REQUIRED);
        return bindApplySecurityOnEntity(methodAddress.getElement(methodAddress.length() - 1));
    }

    /**
     * Binds the custom {@code applySecurityOnEntity} method and returns the binder builder.
     * The method is completely free: NO parameter is imposed — the caller declares whatever
     * it needs via {@code .withParam(i, supplier)} (e.g. {@link SecuredEntitySupplierBuilder}
     * for the entity being written) and returns with {@code .up()}.
     */
    private IAuthenticationMethodBinderBuilder<?> bindApplySecurityOnEntity(String methodName) throws ApiException {
        this.applySecurityOnEntity = new AuthenticationMethodBinderBuilder<>(this, this.supplier, methodName);
        return this.applySecurityOnEntity;
    }

    @Override
    public IUseCaseBuilder<?, ?, ?> useCase(String methodName) throws ApiException {
        Objects.requireNonNull(methodName, METHOD_NAME_REQUIRED);
        return this.useCases.computeIfAbsent(methodName,
                name -> new UseCaseBuilder<>(name, (IDomainBuilder<?>) null));
    }

    @Override
    public IUseCaseBuilder<?, ?, ?> useCase(IMethod method) throws ApiException {
        Objects.requireNonNull(method, METHOD_REQUIRED);
        return this.useCases.computeIfAbsent(method.getName(),
                name -> new UseCaseBuilder<>(name, (IDomainBuilder<?>) null));
    }

    @Override
    public IUseCaseBuilder<?, ?, ?> useCase(ObjectAddress methodAddress) throws ApiException {
        Objects.requireNonNull(methodAddress, METHOD_ADDRESS_REQUIRED);
        String name = methodAddress.getElement(methodAddress.length() - 1);
        return this.useCases.computeIfAbsent(name,
                n -> new UseCaseBuilder<>(n, (IDomainBuilder<?>) null));
    }

    @Override
    protected synchronized IAuthenticationContext doBuild() throws ApiException {

        // Build method binders
        IMethodBinder<?> authenticateBinder = this.authenticate != null ? this.authenticate.build() : null;

        IMethodBinder<?> applySecurityBinder = this.applySecurityOnEntity != null ? this.applySecurityOnEntity.build() : null;

        IMethodBinder<?> refreshBinder = this.refresh != null ? this.refresh.build() : null;

        AuthenticationDefinition definition = new AuthenticationDefinition(
                this.supplier,
                authenticateBinder,
                this.fieldAnnotations,
                applySecurityBinder,
                this.useCases.values(),
                refreshBinder);
        return new AuthenticationContext(definition);
    }

    @Override
    protected void doAutoDetection() throws ApiException {
        // Auto-detection will be implemented when Spring integration is ported
    }

}
