package com.garganttua.api.commons.context.dsl;

import com.garganttua.core.reflection.IField;

import com.garganttua.api.commons.context.BuildingStage;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.security.IDomainSecurityBuilder;
import com.garganttua.api.commons.event.IEventPublisher;
import com.garganttua.api.commons.endpoint.IInterface;
import com.garganttua.api.commons.ApiException;
import com.garganttua.core.dsl.IAutomaticLinkedBuilder;
import com.garganttua.core.mutex.IMutex;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

public interface IDomainBuilder<E> extends IAutomaticLinkedBuilder<IDomainBuilder<E>, IApiBuilder, IDomain<E>> {

    IDomainStartupBinderBuilder<E> startup(BuildingStage stage, ISupplierBuilder<?, ? extends ISupplier<?>> method) throws ApiException;

    IDomainBuilder<E> interfasse(ISupplierBuilder<? extends IInterface, ? extends ISupplier<? extends IInterface>> bean) throws ApiException;

    IDomainBuilder<E> interfasse(IClass<? extends IInterface> interfasse) throws ApiException;

    IDomainBuilder<E> events(ISupplierBuilder<?, ? extends ISupplier<?>> bean) throws ApiException;

    IDomainBuilder<E> events(IEventPublisher eventPublisher) throws ApiException;

    IDomainBuilder<E> tenant(boolean b) throws ApiException;

    IDomainBuilder<E> owner(String string) throws ApiException;

    IDomainBuilder<E> owner(IField field) throws ApiException;

    IDomainBuilder<E> owner(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> owned(String string) throws ApiException;

    IDomainBuilder<E> owned(IField field) throws ApiException;

    IDomainBuilder<E> owned(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> publik();

    IDomainBuilder<E> shared(IField field) throws ApiException;

    IDomainBuilder<E> shared(String string) throws ApiException;

    IDomainBuilder<E> shared(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> geolocalized(String string) throws ApiException;

    IDomainBuilder<E> geolocalized(IField field) throws ApiException;

    IDomainBuilder<E> geolocalized(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> hiddenable(String string) throws ApiException;

    IDomainBuilder<E> hiddenable(IField field) throws ApiException;

    IDomainBuilder<E> hiddenable(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> superOwner(String string) throws ApiException;

    IDomainBuilder<E> superOwner(IField field) throws ApiException;

    IDomainBuilder<E> superOwner(ObjectAddress fieldAddress) throws ApiException;

    IDomainBuilder<E> superTenant(String string) throws ApiException;

    IDomainBuilder<E> superTenant(IField field) throws ApiException;

    IDomainBuilder<E> superTenant(ObjectAddress fieldAddress) throws ApiException;

    IEntityBuilder<E> name(String name) throws ApiException;

    IClass<E> getEntityClass() throws ApiException;

    IDomainSecurityBuilder<E> security() throws ApiException;

    // The @Key domain config sub-builder moved under .security(): see
    // IDomainSecurityBuilder.key(). It marks this domain as a key domain whose
    // entity holds cryptographic key material, used as the storage backend when
    // an authenticator's authorization declares .key(IDomainBuilder).

    <D> IDtoBuilder<E, D> dto(IClass<D> dtoClass) throws ApiException;

    <I, O> IUseCaseBuilder<I, O, E> useCase(String useCaseName, IClass<I> inputType, IClass<O> outputType);

    IDomainWorkflowBuilder<E> workflow(String workflowName);

    IDomainBuilder<E> create(Object entity);

    IDomainBuilder<E> upsert(Object entity);

    IDomainBuilder<E> doInjection(boolean enabled);

    IEntityBuilder<E> entity() throws ApiException;

    IDomainBuilder<E> creation(boolean enabled);

    IDomainBuilder<E> readAll(boolean enabled);

    IDomainBuilder<E> readOne(boolean enabled);

    IDomainBuilder<E> update(boolean enabled);

    IDomainBuilder<E> deleteOne(boolean enabled);

    IDomainBuilder<E> deleteAll(boolean enabled);

    /**
     * Serializes this domain's write operations (create / update / delete) through a garganttua-core
     * mutex; read operations stay unsynchronized. Mirrors the events
     * {@code route(...).synchronization(lock, lockObject)} model — see
     * {@link com.garganttua.api.commons.context.DomainSyncDef}.
     *
     * @param lock       the mutex name; a plain name uses the default in-JVM mutex, a qualified
     *                   {@code Type::name} selects a registered {@code @MutexFactory} (e.g. a
     *                   distributed lock). A {@code null}/blank lock leaves the domain unsynchronized.
     * @param lockObject an optional static suffix narrowing the lock name, or {@code null}
     * @return this builder, for chaining
     */
    IDomainBuilder<E> synchronization(String lock, String lockObject);

    /**
     * Serializes this domain's write operations through the given {@link IMutex} instance (registered
     * as a bean and resolved at runtime). Mirrors the events {@code route(...).synchronization(IMutex)}
     * form.
     *
     * @param mutex      the mutex instance write operations acquire; must not be {@code null}
     * @param lockObject accepted for symmetry with the name form but has no runtime effect here (a
     *                   concrete mutex is a single instance and cannot be sub-keyed); may be {@code null}
     * @return this builder, for chaining
     */
    IDomainBuilder<E> synchronization(IMutex mutex, String lockObject);

    /**
     * Serializes this domain's write operations through the {@link IMutex} produced by the given
     * supplier builder (built once at post-build and registered as a bean). Mirrors the events
     * {@code route(...).synchronization(ISupplierBuilder)} form.
     *
     * @param mutexBuilder the supplier builder producing the mutex; must not be {@code null}
     * @param lockObject   accepted for symmetry but has no runtime effect here (single instance); may
     *                     be {@code null}
     * @return this builder, for chaining
     */
    IDomainBuilder<E> synchronization(ISupplierBuilder<IMutex, ISupplier<IMutex>> mutexBuilder,
            String lockObject);

    /**
     * Serializes this domain's write operations through the {@link IMutex} bean resolved from the
     * injection context by the given bean reference (format
     * {@code [provider::][class][!strategy][#name][@qualifier]}). Mirrors the events
     * {@code route(...).synchronizationBean(String)} form.
     *
     * @param beanReference the {@code IMutex} bean reference/name; a {@code null}/blank value leaves
     *                      the domain unsynchronized
     * @param lockObject    accepted for symmetry but has no runtime effect here (single instance); may
     *                      be {@code null}
     * @return this builder, for chaining
     */
    IDomainBuilder<E> synchronizationBean(String beanReference, String lockObject);

}
