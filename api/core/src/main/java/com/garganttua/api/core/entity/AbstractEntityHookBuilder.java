package com.garganttua.api.core.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.context.dsl.IEntityMethodBinderBuilder;
import com.garganttua.core.dsl.AbstractAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.binders.IMethodBinder;
import com.garganttua.core.supply.ISupplier;
import com.garganttua.core.supply.dsl.FixedSupplierBuilder;
import com.garganttua.core.supply.dsl.ISupplierBuilder;

/**
 * Holds the lifecycle-hook portion of {@link EntityBuilder}'s DSL — the {@code beforeCreate /
 * afterGet / …} method-binder builders and the free-hook ({@code IMethod}-bound) registration —
 * along with their build-time assembly. Extracted as an abstract superclass so {@code EntityBuilder}
 * (an inherently wide {@link IEntityBuilder} mirror) stays under the file-size gate; the concrete
 * subclass supplies the entity class and resolver registry via the abstract accessors.
 *
 * @param <E> the entity type
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
abstract class AbstractEntityHookBuilder<E>
        extends AbstractAutomaticLinkedBuilder<IEntityBuilder<E>, IDomainBuilder<E>, IEntityContext<E>>
        implements IEntityBuilder<E> {

    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> afterGetMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> beforeCreateMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> afterCreateMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> beforeUpdateMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> afterUpdateMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> beforeDeleteMethodBuilders = new ArrayList<>();
    protected final List<Pair<String, EntityMethodBinderBuilder<E>>> afterDeleteMethodBuilders = new ArrayList<>();

    /** Free hooks (bound to the EXACT IMethod, possibly external) keyed by hook name. */
    protected final Map<String, List<EntityMethodBinderBuilder<E>>> freeHookBuilders = new HashMap<>();

    protected AbstractEntityHookBuilder(IDomainBuilder<E> domainBuilder) {
        super(domainBuilder);
    }

    /** @return the entity class, supplied by the concrete builder. */
    protected abstract IClass<?> hookEntityClass();

    /** @return the resolver registry for auto-wiring hook parameters (may be null). */
    protected abstract com.garganttua.core.injection.IInjectableElementResolver hookResolverRegistry();

    @SuppressWarnings("unchecked")
    private IEntityMethodBinderBuilder<E> createEntityMethodBuilder(
            String methodName, List<Pair<String, EntityMethodBinderBuilder<E>>> list, boolean collection)
            throws ApiException {
        // Create a supplier builder that will supply the entity instance at runtime.
        ISupplierBuilder<Object, ISupplier<Object>> supplierBuilder =
                FixedSupplierBuilder.ofNullable(null, (IClass<Object>) (IClass<?>) hookEntityClass());

        EntityMethodBinderBuilder<E> builder = new EntityMethodBinderBuilder<>(this, supplierBuilder, collection);
        // Entity lifecycle methods are void and take no parameters.
        builder.method(methodName, IClass.getClass(Void.class));

        list.add(new Pair<>(methodName, builder));
        return builder;
    }

    private IEntityMethodBinderBuilder<E> createEntityMethodBuilder(
            String methodName, List<Pair<String, EntityMethodBinderBuilder<E>>> list) throws ApiException {
        return createEntityMethodBuilder(methodName, list, false);
    }

    /**
     * Binds a lifecycle hook to the EXACT {@link IMethod} provided — honouring its declaring class
     * (which may be external), its static/instance nature and its signature.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private IEntityMethodBinderBuilder<E> bindFreeHook(IMethod method, String hookName) throws ApiException {
        Objects.requireNonNull(method, "Method cannot be null");
        IClass<?> declaringClass = method.getDeclaringClass();
        ISupplierBuilder<?, ? extends ISupplier<?>> target;
        if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            // Static: the target instance is irrelevant — supply null typed to the declaring class.
            target = FixedSupplierBuilder.ofNullable(null, (IClass<Object>) (IClass<?>) declaringClass);
        } else {
            // Instance: materialise one instance of the (external) hook class via its public no-arg ctor.
            try {
                Object instance = declaringClass.getConstructor().newInstance();
                target = new FixedSupplierBuilder(instance, declaringClass);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new ApiException("Failed to instantiate hook class '" + declaringClass.getName()
                        + "' for " + hookName + "(IMethod) — it needs a public no-arg constructor, "
                        + "or make the hook method static.", e);
            }
        }
        EntityMethodBinderBuilder<E> builder = new EntityMethodBinderBuilder<>(this, target);
        builder.method(method);
        this.freeHookBuilders.computeIfAbsent(hookName, k -> new ArrayList<>()).add(builder);
        return builder;
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterGet(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.afterGetMethodBuilders, true);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterGet(IMethod method) throws ApiException {
        return bindFreeHook(method, "afterGet");
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterGet(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.afterGetMethodBuilders, true);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeCreate(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.beforeCreateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeCreate(IMethod method) throws ApiException {
        return bindFreeHook(method, "beforeCreate");
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeCreate(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.beforeCreateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeUpdate(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.beforeUpdateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeUpdate(IMethod method) throws ApiException {
        return bindFreeHook(method, "beforeUpdate");
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeUpdate(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.beforeUpdateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeDelete(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.beforeDeleteMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeDelete(IMethod method) throws ApiException {
        return bindFreeHook(method, "beforeDelete");
    }

    @Override
    public IEntityMethodBinderBuilder<E> beforeDelete(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.beforeDeleteMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterCreate(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.afterCreateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterCreate(IMethod method) throws ApiException {
        return bindFreeHook(method, "afterCreate");
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterCreate(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.afterCreateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterUpdate(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.afterUpdateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterUpdate(IMethod method) throws ApiException {
        return bindFreeHook(method, "afterUpdate");
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterUpdate(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.afterUpdateMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterDelete(String methodName) throws ApiException {
        return createEntityMethodBuilder(methodName, this.afterDeleteMethodBuilders);
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterDelete(IMethod method) throws ApiException {
        return bindFreeHook(method, "afterDelete");
    }

    @Override
    public IEntityMethodBinderBuilder<E> afterDelete(ObjectAddress methodAddress) throws ApiException {
        return createEntityMethodBuilder(methodAddress.getElement(methodAddress.length() - 1),
                this.afterDeleteMethodBuilders);
    }

    /** Builds the ordered instance-method binders for one hook list. */
    protected List<IMethodBinder<Void>> buildMethodBinders(
            List<Pair<String, EntityMethodBinderBuilder<E>>> builders) throws ApiException {
        List<IMethodBinder<Void>> binders = new ArrayList<>();
        for (Pair<String, EntityMethodBinderBuilder<E>> pair : builders) {
            binders.add(pair.getValue1().build());
        }
        return binders;
    }

    /** Builds the free-hook binders keyed by hook name (delegates to {@link EntityHookBinderFactory}). */
    protected Map<String, List<IMethodBinder<?>>> buildFreeBinders() throws ApiException {
        return EntityHookBinderFactory.buildFreeBinders(this.freeHookBuilders, hookEntityClass(), hookResolverRegistry());
    }
}
