package com.garganttua.api.core.entity;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.api.core.domain.DomainBuilder;
import com.garganttua.core.reflection.annotations.Reflected;

import java.lang.annotation.Annotation;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.javatuples.Pair;

import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IObjectQuery;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.fields.FieldResolver;
import com.garganttua.core.reflection.query.ObjectQueryFactory;

import com.garganttua.core.observability.Logger;

@Reflected
@SuppressFBWarnings(value = {"CT_CONSTRUCTOR_THROW", "EI_EXPOSE_REP2", "IS2_INCONSISTENT_SYNC"}, justification = "Immutable-by-contract value/definition carrier; collections & arrays carried by reference as a snapshot (framework-internal, built once).")
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidDuplicateLiterals"})
public class EntityBuilder<E> extends AbstractEntityHookBuilder<E> {
	private static final Logger log = Logger.getLogger(EntityBuilder.class);


    // Reflection provider is whatever the user installed via IClass.setReflection().
    // Resolved lazily per call so the framework never picks an implementation.
    private static IReflectionProvider provider() {
        return IClass.getReflection();
    }

    private IClass<?> entityClass;

    public IClass<?> getEntityClass() { return this.entityClass; }
    private IObjectQuery objectQuery;
    private ObjectAddress id;
    private ObjectAddress uuid;
    private boolean overwriteUuid = false;
    private com.garganttua.api.commons.entity.IUuidGenerator uuidGenerator;
    private ObjectAddress tenantId;
    private List<ObjectAddress> mandatories = new ArrayList<>();
    private List<Pair<ObjectAddress, UnicityScope>> unicities = new ArrayList<>();
    private List<Pair<ObjectAddress, String>> creates = new ArrayList<>();
    private List<Pair<ObjectAddress, String>> updates = new ArrayList<>();
    private List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedFields = new ArrayList<>();
    private List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedMethods = new ArrayList<>();

    /**
     * Resolver registry to auto-wire free-hook method parameters (injected framework context like
     * {@code @DomainContext}/{@code @ApiContext}). Set by {@code DomainBuilder} before {@code build()};
     * null when no injection context is available (the entity-typed parameter is still wired).
     */
    private com.garganttua.core.injection.IInjectableElementResolver resolverRegistry;

    /** Provided by {@code DomainBuilder.doBuild} so free-hook parameters can be auto-wired at build. */
    public void setResolverRegistry(com.garganttua.core.injection.IInjectableElementResolver resolverRegistry) {
        this.resolverRegistry = resolverRegistry;
    }

    @Override
    protected IClass<?> hookEntityClass() {
        return this.entityClass;
    }

    @Override
    protected com.garganttua.core.injection.IInjectableElementResolver hookResolverRegistry() {
        return this.resolverRegistry;
    }

    public EntityBuilder(IClass<?> entityClass, IDomainBuilder<E> domainBuilder) throws ApiException {
        super(domainBuilder);
        this.entityClass = Objects.requireNonNull(entityClass, "Entity class cannot be null");

        try {
            this.objectQuery = ObjectQueryFactory.objectQuery(this.entityClass, provider());
        } catch (ReflectionException e) {
            throw new ApiException(e.getMessage(), e);
        }
    }

    @Override
    public IEntityBuilder<E> id(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.id = FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> id(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        this.id = FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> id(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        this.id = FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> uuid(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.uuid = FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> uuid(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        this.uuid = FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> uuid(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        this.uuid = FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> overwriteUuid(boolean overwrite) {
        this.overwriteUuid = overwrite;
        return this;
    }

    @Override
    public IEntityBuilder<E> uuidGenerator(com.garganttua.api.commons.entity.IUuidGenerator generator) {
        Objects.requireNonNull(generator, "UUID generator cannot be null");
        this.uuidGenerator = generator;
        return this;
    }

    @Override
    public IEntityBuilder<E> tenantId(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.tenantId = FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> tenantId(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        this.tenantId = FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> tenantId(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        this.tenantId = FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, IClass.getClass(String.class)).address();
        return this;
    }

    @Override
    public IEntityBuilder<E> mandatory(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");

        this.mandatories.add(FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address());

        return this;
    }

    @Override
    public IEntityBuilder<E> mandatory(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");

        this.mandatories.add(FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address());

        return this;
    }

    @Override
    public IEntityBuilder<E> mandatory(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");

        this.mandatories.add(FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address());

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");

        this.unicities.add(new Pair<ObjectAddress, UnicityScope>(FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address(),
                UnicityScope.system));

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");

        this.unicities.add(new Pair<ObjectAddress, UnicityScope>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address(), UnicityScope.system));

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");

        this.unicities.add(new Pair<ObjectAddress, UnicityScope>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), UnicityScope.system));

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(String fieldName, UnicityScope scope) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");

        this.unicities.add(new Pair<ObjectAddress, UnicityScope>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address(), scope));

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(IField field, UnicityScope scope) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");

        this.unicities.add(
                new Pair<ObjectAddress, UnicityScope>(FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address(), scope));

        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(ObjectAddress fieldAddress, UnicityScope scope) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");

        this.unicities.add(new Pair<ObjectAddress, UnicityScope>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), scope));

        return this;
    }

    @Override
    public IEntityBuilder<E> create(String fieldName) throws ApiException {
        return create(fieldName, null);
    }

    @Override
    public IEntityBuilder<E> create(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        return create(field.getName(), null);
    }

    @Override
    public IEntityBuilder<E> create(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        this.creates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), null));
        return this;
    }

    @Override
    public IEntityBuilder<E> create(String fieldName, String authority) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        this.creates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address(), authority));
        return this;
    }

    @Override
    public IEntityBuilder<E> create(IField field, String authority) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        this.creates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address(), authority));
        return this;
    }

    @Override
    public IEntityBuilder<E> create(ObjectAddress fieldAddress, String authority) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        this.creates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), authority));
        return this;
    }

    @Override
    public IEntityBuilder<E> update(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");

        this.updates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address(), null));

        return this;
    }

    @Override
    public IEntityBuilder<E> update(IField field) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");

        this.updates.add(new Pair<ObjectAddress, String>(FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address(), null));

        return this;
    }

    @Override
    public IEntityBuilder<E> update(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");

        this.updates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), null));

        return this;
    }

    @Override
    public IEntityBuilder<E> update(String fieldName, String authority) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");

        this.updates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByFieldName(this.entityClass, provider(), fieldName, null).address(), authority));

        return this;
    }

    @Override
    public IEntityBuilder<E> update(IField field, String authority) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");

        this.updates
                .add(new Pair<ObjectAddress, String>(FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address(), authority));

        return this;
    }

    @Override
    public IEntityBuilder<E> update(ObjectAddress fieldAddress, String authority) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");

        this.updates.add(new Pair<ObjectAddress, String>(
                FieldResolver.fieldByAddress(this.entityClass, provider(), fieldAddress, null).address(), authority));

        return this;
    }

    @Override
    public IEntityBuilder<E> annotation(String elementName, IClass<? extends Annotation> annotation)
            throws ApiException {
        Objects.requireNonNull(elementName, "Element name cannot be null");
        Objects.requireNonNull(annotation, "Annotation cannot be null");

        try {
            ObjectAddress address = this.objectQuery.address(elementName);
            return this.annotation(address, annotation);
        } catch (ReflectionException e) {
            throw new ApiException(e.getMessage(), e);
        }
    }

    @Override
    public IEntityBuilder<E> annotation(IField field, IClass<? extends Annotation> annotation) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        Objects.requireNonNull(annotation, "Annotation cannot be null");

        ObjectAddress address = FieldResolver.fieldByFieldName(this.entityClass, provider(), field.getName(), null).address();

        Pair<ObjectAddress, IClass<? extends Annotation>> candidate = new Pair<>(address, annotation);

        if (this.annotatedFields.contains(candidate)) {
            return this;
        }

        this.annotatedFields.add(candidate);

        return this;
    }

    @Override
    public IEntityBuilder<E> annotation(IMethod method, IClass<? extends Annotation> annotation) throws ApiException {
        Objects.requireNonNull(method, "Method cannot be null");
        Objects.requireNonNull(annotation, "Annotation cannot be null");

        // TODO: Implement when MethodResolver API is clarified
        throw new UnsupportedOperationException("Unimplemented method 'annotation(Method, IClass)'");
    }

    @Override
    public IEntityBuilder<E> annotation(ObjectAddress elementAddress, IClass<? extends Annotation> annotation)
            throws ApiException {
        Objects.requireNonNull(elementAddress, "Element address cannot be null");
        Objects.requireNonNull(annotation, "Annotation cannot be null");

        try {
            Object leaf = this.objectQuery.find(elementAddress).getLast();
            if (leaf instanceof IField f)
                this.annotation(f, annotation);
            else if (leaf instanceof IMethod m)
                this.annotation(m, annotation);
            else
                throw new ApiException("Unsupported element type: " + leaf.getClass().getName());

            return this;
        } catch (ReflectionException e) {
            throw new ApiException(e.getMessage(), e);
        }

    }

    @Override
    protected synchronized IEntityContext<E> doBuild() throws ApiException {
        this.throwExceptionIfNoUuid();
        this.throwExceptionIfNoTenantId();
        this.throwExceptionIfNoId();

        EntityDefinition<E> definition = new EntityDefinition<>(
                (IClass<E>) this.entityClass,
                this.id,
                this.uuid,
                this.tenantId,
                new ArrayList<>(this.mandatories),
                new ArrayList<>(this.unicities),
                new ArrayList<>(this.creates),
                new ArrayList<>(this.updates),
                new ArrayList<>(this.annotatedFields),
                new ArrayList<>(this.annotatedMethods),
                this.buildMethodBinders(this.afterGetMethodBuilders),
                this.buildMethodBinders(this.beforeCreateMethodBuilders),
                this.buildMethodBinders(this.afterCreateMethodBuilders),
                this.buildMethodBinders(this.beforeUpdateMethodBuilders),
                this.buildMethodBinders(this.afterUpdateMethodBuilders),
                this.buildMethodBinders(this.beforeDeleteMethodBuilders),
                this.buildMethodBinders(this.afterDeleteMethodBuilders),
                this.overwriteUuid,
                this.uuidGenerator,
                this.buildFreeBinders());

        return new EntityContext<>(definition);
    }

    private void throwExceptionIfNoUuid() throws ApiException {
        EntityBuilderValidation.requireUuid(this.entityClass, this.uuid);
    }

    private void throwExceptionIfNoTenantId() throws ApiException {
        EntityBuilderValidation.requireTenantId(this.entityClass, this.tenantId,
                isMultiTenantEnabled(), isTenantDomain());
    }

    private boolean isMultiTenantEnabled() {
        try {
            return up().up() instanceof ApiBuilder acb && acb.isMultiTenant();
        } catch (RuntimeException e) {
            return true; // default to strict
        }
    }

    /**
     * True when the parent domain is marked {@code .tenant(true)} — i.e. the
     * entity IS the tenant. Such entities don't carry a tenantId field; their
     * uuid plays that role downstream (see {@code FilterContext.buildTenantFilter}).
     */
    private boolean isTenantDomain() {
        try {
            return up() instanceof DomainBuilder<?> db && db.isTenantDomain();
        } catch (Exception e) {
            return false;
        }
    }

    private void throwExceptionIfNoId() throws ApiException {
        EntityBuilderValidation.requireId(this.entityClass, this.id);
    }

    @Override
    protected void doAutoDetection() {

    }
}
