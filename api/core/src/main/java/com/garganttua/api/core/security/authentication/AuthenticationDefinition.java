package com.garganttua.api.core.security.authentication;

import com.garganttua.api.core.SuppressFBWarnings;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

import org.javatuples.Pair;

import com.garganttua.api.commons.context.dsl.IUseCaseBuilder;
import com.garganttua.api.commons.definition.IAuthenticationDefinition;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
public record AuthenticationDefinition(
            ISupplierBuilder<?, ? extends ISupplier<?>> supplier,
            IMethodBinder<?> authenticateMethodBinder,
            List<Pair<IClass<? extends Annotation>, IClass<?>>> entityFieldAnnotations,
            IMethodBinder<?> applySecurityOnEntityMethodBinder,
            Collection<IUseCaseBuilder<?, ?, ?>> useCasesMethodBinders) implements IAuthenticationDefinition {

}
