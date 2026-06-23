package com.garganttua.api.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDtoContext;
import com.garganttua.api.commons.context.dsl.IDomainStartupBinderBuilder;
import com.garganttua.api.commons.context.dsl.IDtoBuilder;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.definition.IUseCaseDefinition;
import com.garganttua.api.commons.definition.IWorkflowDefinition;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.api.core.dto.DtoContext;
import com.garganttua.api.core.entity.EntityDefinition;
import com.garganttua.api.core.security.DomainSecurityBuilder;
import com.garganttua.core.injection.BeanDefinition;
import com.garganttua.core.injection.IBeanFactory;
import com.garganttua.core.injection.IInjectableElementResolverBuilder;
import com.garganttua.core.injection.context.dsl.BeanFactoryBuilder;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.injection.context.dsl.InjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

/**
 * Stateless build-step helpers for {@link DomainBuilder#doBuild()}: builds the DTO contexts,
 * entity bean definition, startup binders, interface and event suppliers from their respective
 * builder collections. Extracted from {@code DomainBuilder} to keep that wide-interface builder
 * under the file-size gate while preserving identical build behaviour.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // repeated "unchecked" suppression strings
final class DomainBuilderBuildSupport {

    private static final com.garganttua.core.observability.Logger log =
            com.garganttua.core.observability.Logger.getLogger(DomainBuilderBuildSupport.class);

    private DomainBuilderBuildSupport() {
    }

    /**
     * Records the entity-to-DTO mapping configuration, tolerating the absence of mapping annotations
     * (the mapping can still be configured manually downstream).
     */
    static void recordDtoMapping(com.garganttua.core.mapper.IMapper mapper, IClass<?> entityClass,
            IClass<?> dtoClass) {
        try {
            mapper.recordMappingConfiguration(entityClass, dtoClass);
        } catch (com.garganttua.core.mapper.MapperException e) {
            // Mapper configuration may fail if no mapping annotations are present
            // This is acceptable - mapping will need to be done manually
            log.trace("No mapping configuration recorded for {} -> {}: {}",
                    entityClass, dtoClass, e.getMessage());
        }
    }

    /** Assembles the immutable {@link DomainDefinition} from the builder's configured state. */
    @SuppressWarnings("unchecked")
    static <E> DomainDefinition<E> assembleDomainDefinition(DomainBuilder<E> b,
            EntityDefinition<E> entityDefinition,
            List<IDtoDefinition<E>> dtoDefinitions,
            List<IMethodBinder<Void>> startupBinders,
            Map<String, IUseCaseDefinition> useCaseDefinitions,
            Map<String, IWorkflowDefinition> workflowDefinitions) throws ApiException {
        DomainSecurityBuilder<E> sec = (DomainSecurityBuilder<E>) b.securityBuilder;
        var securityDefinition = sec != null ? sec.buildSecurityDefinition() : null;
        var keyDefinition = sec == null ? null : sec.buildKeyDefinition();
        return new DomainDefinition<E>(
                b.getDomainName(), entityDefinition, dtoDefinitions, startupBinders,
                b.publik, b.tenant,
                DomainBuilderBuildSupport.<E>castEntities(b.createEntities),
                DomainBuilderBuildSupport.<E>castEntities(b.upsertEntities),
                b.owner, b.owned, b.shared, b.hiddenable, b.geolocalized,
                b.superOwner, b.superTenant,
                useCaseDefinitions, workflowDefinitions, securityDefinition, keyDefinition);
    }

    /**
     * Assembles the runtime {@link Domain} context from a built {@link DomainDefinition} and the
     * builder's remaining configured state (security context, interface/event suppliers, workflow
     * and DI metadata).
     */
    static <E> Domain<E> assembleDomain(DomainBuilder<E> builder, DomainDefinition<E> domainDefinition,
            com.garganttua.api.commons.context.IEntityContext<E> entityContext,
            List<IDtoContext<?>> dtoContexts,
            com.garganttua.core.workflow.IWorkflow builtWorkflow,
            BeanDefinition<?> entityBeanDefinition) throws ApiException {
        Domain<E> domainContext = new Domain<E>(
                domainDefinition,
                entityContext,
                builder.buildSecurityContext(),
                dtoContexts,
                buildInterfaceSuppliers(builder.interfaces),
                buildEventSuppliers(builder.events));
        domainContext.setWorkflow(builtWorkflow);
        domainContext.setEntityBeanDefinition(entityBeanDefinition);
        domainContext.setDoInjection(builder.doInjection);
        return domainContext;
    }

    /** Builds the DTO contexts and extracts their definitions into the supplied collectors. */
    @SuppressWarnings("unchecked")
    static <E> void buildDtoContexts(Map<IClass<?>, IDtoBuilder> dtos,
            List<IDtoContext<?>> dtoContexts, List<IDtoDefinition<E>> dtoDefinitions)
            throws ApiException {
        for (IDtoBuilder<?, ?> builder : dtos.values()) {
            IDtoContext<?> dtoContext = builder.build();
            dtoContexts.add(dtoContext);
            if (dtoContext instanceof DtoContext<?> dtc) {
                dtoDefinitions.add((IDtoDefinition<E>) dtc.getDtoDefinition());
            }
        }
    }

    /** Extracts the {@link EntityDefinition} from a built entity context, or null if unavailable. */
    @SuppressWarnings("unchecked")
    static <E> EntityDefinition<E> entityDefinitionOf(
            com.garganttua.api.commons.context.IEntityContext<E> entityContext) {
        return entityContext instanceof com.garganttua.api.core.entity.EntityContext<E> ec
                ? ec.getEntityDefinition() : null;
    }

    /** Builds the entity bean definition used for runtime DI injection (null when no DI context). */
    static BeanDefinition<?> buildEntityBeanDefinition(IInjectionContextBuilder injectionContextBuilder,
            IClass<?> entityClass) throws ApiException {
        if (injectionContextBuilder == null) {
            return null;
        }
        // Ensure @Property, @Null, @Fixed resolvers are registered
        // (setBuiltInResolvers is normally called during InjectionContext.doBuild(),
        // but we need the resolvers now for BeanFactoryBuilder auto-detection)
        IInjectableElementResolverBuilder resolversBuilder = injectionContextBuilder.resolvers();
        InjectionContextBuilder.setBuiltInResolvers(resolversBuilder, Set.of(), false);

        BeanFactoryBuilder<?> bfb = new BeanFactoryBuilder<>(entityClass);
        bfb.provide(resolversBuilder);
        IBeanFactory<?> templateFactory = bfb.build();
        return templateFactory.definition();
    }

    /**
     * Instantiates a catalogued {@code @Interface} type via its public no-arg constructor, wrapping
     * any reflective failure in an {@link ApiException} with a fix-up hint.
     */
    static IInterface instantiateInterface(IClass<? extends IInterface> interfasse)
            throws ApiException {
        try {
            return (IInterface) interfasse.getConstructor().newInstance();
        } catch (Exception e) {
            throw new ApiException("Failed to instantiate @Interface class '" + interfasse.getName()
                    + "'. A public no-arg constructor is required.", e);
        }
    }

    /** Builds the startup method binders from their builders. */
    static List<IMethodBinder<Void>> buildStartupBinders(
            List<IDomainStartupBinderBuilder> startupBinderBuilders) throws ApiException {
        List<IMethodBinder<Void>> startupBinders = new ArrayList<>();
        for (IDomainStartupBinderBuilder<?> builder : startupBinderBuilders) {
            startupBinders.add(builder.build());
        }
        return startupBinders;
    }

    @SuppressWarnings("unchecked")
    static <E> List<E> castEntities(List<Object> entities) {
        return entities.stream().map(e -> (E) e).toList();
    }

    /** Builds the interface suppliers from their builders. */
    @SuppressWarnings("unchecked")
    static List<ISupplier<IInterface>> buildInterfaceSuppliers(
            List<ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>>> interfaces)
            throws ApiException {
        List<ISupplier<IInterface>> builtInterfaces = new ArrayList<>();
        for (ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>> interfaceBuilder : interfaces) {
            builtInterfaces.add((ISupplier<IInterface>) interfaceBuilder.build());
        }
        return builtInterfaces;
    }

    /** Builds the event publisher suppliers from their builders. */
    @SuppressWarnings("unchecked")
    static List<ISupplier<IEventPublisher>> buildEventSuppliers(
            List<ISupplierBuilder<?, ? extends ISupplier<?>>> events) throws ApiException {
        List<ISupplier<IEventPublisher>> builtEvents = new ArrayList<>();
        for (ISupplierBuilder<?, ? extends ISupplier<?>> eventBuilder : events) {
            builtEvents.add((ISupplier<IEventPublisher>) eventBuilder.build());
        }
        return builtEvents;
    }
}
