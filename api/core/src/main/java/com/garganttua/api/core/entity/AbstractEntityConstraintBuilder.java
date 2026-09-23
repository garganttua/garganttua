package com.garganttua.api.core.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.fields.FieldResolver;

/**
 * Holds the field-constraint portion of {@link EntityBuilder}'s DSL — {@code mandatory}, the
 * framework-checked {@code unicity}, and the store-side {@code index} — with the address resolution
 * each of them performs. Extracted as an abstract superclass so {@code EntityBuilder} (an
 * inherently wide {@link IEntityBuilder} mirror) stays under the file-size gate; the concrete
 * subclass supplies the entity class through {@code hookEntityClass()} and reads the collected
 * declarations at build time.
 *
 * @param <E> the entity type
 */
abstract class AbstractEntityConstraintBuilder<E> extends AbstractEntityHookBuilder<E> {

    /** Mandatory fields, each paired with what it is required to carry. */
    protected final List<Pair<ObjectAddress, MandatoryPolicy>> mandatories = new ArrayList<>();

    /** Fields under a framework-checked uniqueness constraint, with the scope it spans. */
    protected final List<Pair<ObjectAddress, UnicityScope>> unicities = new ArrayList<>();

    /** Indexes the store must carry — see {@link EntityIndexRule}. */
    protected final List<EntityIndexRule> indexes = new ArrayList<>();

    protected AbstractEntityConstraintBuilder(IDomainBuilder<E> domainBuilder) {
        super(domainBuilder);
    }

    /** The reflection provider is whatever the user installed via {@code IClass.setReflection()}. */
    private static IReflectionProvider reflectionProvider() {
        return IClass.getReflection();
    }

    /** Resolves {@code fieldName} against the entity and returns its address. */
    private ObjectAddress addressOfName(String fieldName) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        return FieldResolver.fieldByFieldName(hookEntityClass(), reflectionProvider(), fieldName, null).address();
    }

    /** Re-resolves {@code fieldAddress} against the entity — a declaration never trusts an unchecked address. */
    private ObjectAddress addressOfAddress(ObjectAddress fieldAddress) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address name cannot be null");
        return FieldResolver.fieldByAddress(hookEntityClass(), reflectionProvider(), fieldAddress, null).address();
    }

    @Override
    public IEntityBuilder<E> mandatory(IField field) throws ApiException {
        return mandatory(field, MandatoryPolicy.anyValue);
    }

    @Override
    public IEntityBuilder<E> mandatory(String fieldName) throws ApiException {
        return mandatory(fieldName, MandatoryPolicy.anyValue);
    }

    @Override
    public IEntityBuilder<E> mandatory(ObjectAddress fieldAddress) throws ApiException {
        return mandatory(fieldAddress, MandatoryPolicy.anyValue);
    }

    @Override
    public IEntityBuilder<E> mandatory(IField field, MandatoryPolicy policy) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        return addMandatory(addressOfName(field.getName()), policy);
    }

    @Override
    public IEntityBuilder<E> mandatory(String fieldName, MandatoryPolicy policy) throws ApiException {
        return addMandatory(addressOfName(fieldName), policy);
    }

    @Override
    public IEntityBuilder<E> mandatory(ObjectAddress fieldAddress, MandatoryPolicy policy) throws ApiException {
        return addMandatory(addressOfAddress(fieldAddress), policy);
    }

    /** Records one resolved mandatory field; a null policy reads as the historical {@code anyValue}. */
    private IEntityBuilder<E> addMandatory(ObjectAddress address, MandatoryPolicy policy) {
        this.mandatories.add(new Pair<>(address, policy == null ? MandatoryPolicy.anyValue : policy));
        return this;
    }

    @Override
    public IEntityBuilder<E> unicity(IField field) throws ApiException {
        return unicity(field, UnicityScope.system);
    }

    @Override
    public IEntityBuilder<E> unicity(String fieldName) throws ApiException {
        return unicity(fieldName, UnicityScope.system);
    }

    @Override
    public IEntityBuilder<E> unicity(ObjectAddress fieldAddress) throws ApiException {
        return unicity(fieldAddress, UnicityScope.system);
    }

    @Override
    public IEntityBuilder<E> unicity(String fieldName, UnicityScope scope) throws ApiException {
        return addUnicity(addressOfName(fieldName), scope);
    }

    @Override
    public IEntityBuilder<E> unicity(IField field, UnicityScope scope) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        return addUnicity(addressOfName(field.getName()), scope);
    }

    @Override
    public IEntityBuilder<E> unicity(ObjectAddress fieldAddress, UnicityScope scope) throws ApiException {
        return addUnicity(addressOfAddress(fieldAddress), scope);
    }

    /** Records one resolved unicity. The scope is kept as declared — {@code null} included. */
    private IEntityBuilder<E> addUnicity(ObjectAddress address, UnicityScope scope) {
        this.unicities.add(new Pair<>(address, scope));
        return this;
    }

    @Override
    public IEntityBuilder<E> index(String fieldName) throws ApiException {
        return index(fieldName, false, UnicityScope.tenant);
    }

    @Override
    public IEntityBuilder<E> index(IField field) throws ApiException {
        return index(field, false, UnicityScope.tenant);
    }

    @Override
    public IEntityBuilder<E> index(ObjectAddress fieldAddress) throws ApiException {
        return index(fieldAddress, false, UnicityScope.tenant);
    }

    @Override
    public IEntityBuilder<E> index(String fieldName, boolean unique, UnicityScope scope) throws ApiException {
        return index(fieldName, unique, scope, IndexKind.standard, null);
    }

    @Override
    public IEntityBuilder<E> index(IField field, boolean unique, UnicityScope scope) throws ApiException {
        return index(field, unique, scope, IndexKind.standard, null);
    }

    @Override
    public IEntityBuilder<E> index(ObjectAddress fieldAddress, boolean unique, UnicityScope scope)
            throws ApiException {
        return index(fieldAddress, unique, scope, IndexKind.standard, null);
    }

    @Override
    public IEntityBuilder<E> index(String fieldName, boolean unique, UnicityScope scope, IndexKind kind, String name)
            throws ApiException {
        return addIndex(addressOfName(fieldName), unique, scope, kind, name);
    }

    @Override
    public IEntityBuilder<E> index(IField field, boolean unique, UnicityScope scope, IndexKind kind, String name)
            throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        return addIndex(addressOfName(field.getName()), unique, scope, kind, name);
    }

    @Override
    public IEntityBuilder<E> index(ObjectAddress fieldAddress, boolean unique, UnicityScope scope, IndexKind kind,
            String name) throws ApiException {
        return addIndex(addressOfAddress(fieldAddress), unique, scope, kind, name);
    }

    /** Records one resolved index; the rule normalises a null scope, kind or name. */
    private IEntityBuilder<E> addIndex(ObjectAddress address, boolean unique, UnicityScope scope, IndexKind kind,
            String name) {
        this.indexes.add(new EntityIndexRule(address, unique, scope, kind, name));
        return this;
    }
}
