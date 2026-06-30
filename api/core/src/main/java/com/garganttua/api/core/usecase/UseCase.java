package com.garganttua.api.core.usecase;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.Set;

import com.garganttua.api.commons.context.IUseCase;
import com.garganttua.api.commons.operation.Scope;
import com.garganttua.api.commons.operation.TechnicalOperation;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethodReturn;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.binders.dsl.IMethodBinderBuilder;
import com.garganttua.core.supply.SupplyException;

/**
 * Builder artifact returned by {@link UseCaseBuilder#doBuild()} to satisfy the
 * {@code IUseCaseBuilder.build()} contract (which yields an {@link IUseCase}).
 *
 * <p>This wrapper is <em>not</em> the runtime executor: a use case is invoked through the
 * {@code UseCaseDefinition}'s real {@code IMethodBinder} (built from {@code bind(...)} and consumed
 * by {@code DomainDefinitionsAssembler}), not through this object. The accessor methods below
 * therefore expose the captured use-case metadata honestly and never throw — execution flows
 * through {@code UseCaseDefinition.binder()}, so {@link #execute()} is a no-op that returns
 * {@link Optional#empty()}.</p>
 *
 * @param <I> the use-case input type
 * @param <O> the use-case output type
 */
@SuppressWarnings("PMD.UnusedPrivateField")
public class UseCase<I,O> implements IUseCase<I,O> {

    private static final Logger log = Logger.getLogger(UseCase.class);

    private final String useCaseName;
    private final IMethodBinderBuilder<?, ?, ?, ?> methodBinder;
    private final String suffix;
    private final String path;
    private final Scope scope;
    private final TechnicalOperation operation;
    private final IClass<Object> useCaseInput;
    private final IClass<Object> useCaseOutput;

    public UseCase(String useCaseName, IMethodBinderBuilder<?, ?, ?, ?> methodBinder, String suffix, String path, Scope scope,
            TechnicalOperation operation, IClass<Object> useCaseInput, IClass<Object> useCaseOutput) {
        this.useCaseName = useCaseName;
        this.methodBinder = methodBinder;
        this.suffix = suffix;
        this.path = path;
        this.scope = scope;
        this.operation = operation;
        this.useCaseInput = useCaseInput;
        this.useCaseOutput = useCaseOutput;
    }

    /** {@return a stable, non-null reference identifying this use case (its name)} */
    @Override
    public String getExecutableReference() {
        return this.useCaseName == null ? "use-case" : this.useCaseName;
    }

    /**
     * No-op for this builder artifact: a use case executes through
     * {@code UseCaseDefinition.binder()}, not this wrapper.
     *
     * @return {@link Optional#empty()} (never throws)
     */
    @Override
    public Optional<IMethodReturn<O>> execute() throws ReflectionException {
        log.warn("UseCase '{}' is a builder artifact; execution flows through UseCaseDefinition.binder()",
                this.useCaseName);
        return Optional.empty();
    }

    /** {@return the use case's type dependencies — none are tracked on this builder artifact} */
    @Override
    public Set<IClass<?>> dependencies() {
        return Set.of();
    }

    @Override
    public Optional<IMethodReturn<O>> supply() throws SupplyException {
        // Delegate to execute()
        try {
            return execute();
        } catch (ReflectionException e) {
            throw new SupplyException(e);
        }
    }

    /** {@return the supplied {@link Type}, namely the use-case output type} */
    @Override
    public Type getSuppliedType() {
        return this.useCaseOutput == null ? IMethodReturn.class : this.useCaseOutput.getType();
    }

    /** {@return the supplied class, namely {@link IMethodReturn}} */
    @SuppressWarnings("unchecked")
    @Override
    public IClass<IMethodReturn<O>> getSuppliedClass() {
        return (IClass<IMethodReturn<O>>) (IClass<?>) IClass.getClass(IMethodReturn.class);
    }

}
