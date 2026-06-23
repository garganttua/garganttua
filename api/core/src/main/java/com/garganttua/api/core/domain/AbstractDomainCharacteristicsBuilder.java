package com.garganttua.api.core.domain;

import java.util.Objects;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.core.dsl.AbstractAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.fields.FieldResolver;

/**
 * Holds the entity-characteristic field setters ({@code owner / owned / shared / hiddenable /
 * geolocalized / superOwner / superTenant}, each in String / {@link IField} / {@link ObjectAddress}
 * overloads) of {@link DomainBuilder}. Extracted as an abstract superclass so {@code DomainBuilder}
 * (an inherently wide {@link IDomainBuilder} mirror) stays under the file-size gate; the concrete
 * subclass supplies the entity class and the entity-declared guard via the abstract accessors.
 *
 * @param <E> the entity type
 */
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidDuplicateLiterals"})
abstract class AbstractDomainCharacteristicsBuilder<E>
        extends AbstractAutomaticLinkedBuilder<IDomainBuilder<E>, IApiBuilder, IDomain<E>>
        implements IDomainBuilder<E> {

    protected volatile ObjectAddress owner;
    protected volatile ObjectAddress owned;
    protected volatile ObjectAddress shared;
    protected volatile ObjectAddress hiddenable;
    protected volatile ObjectAddress geolocalized;
    protected volatile ObjectAddress superOwner;
    protected volatile ObjectAddress superTenant;

    protected AbstractDomainCharacteristicsBuilder(IApiBuilder builder) throws ApiException {
        super(builder);
    }

    /** Reflection provider — whatever the user installed via {@code IClass.setReflection()}. */
    private static IReflectionProvider provider() {
        return IClass.getReflection();
    }

    /** @return the entity class, supplied by the concrete builder (mutable as entity() is called). */
    protected abstract IClass<?> characteristicEntityClass();

    /** Throws if no entity has been declared yet. */
    protected abstract void requireCharacteristicEntityDeclared() throws ApiException;

    private ObjectAddress resolveByName(String fieldName, IClass<?> type) throws ApiException {
        Objects.requireNonNull(fieldName, "Field name cannot be null");
        requireCharacteristicEntityDeclared();
        return FieldResolver.fieldByFieldName(characteristicEntityClass(), provider(), fieldName, type).address();
    }

    private ObjectAddress resolveByField(IField field, IClass<?> type) throws ApiException {
        Objects.requireNonNull(field, "Field cannot be null");
        requireCharacteristicEntityDeclared();
        return FieldResolver.fieldByFieldName(characteristicEntityClass(), provider(), field.getName(), type).address();
    }

    private ObjectAddress resolveByAddress(ObjectAddress fieldAddress, IClass<?> type) throws ApiException {
        Objects.requireNonNull(fieldAddress, "Field address cannot be null");
        requireCharacteristicEntityDeclared();
        return FieldResolver.fieldByAddress(characteristicEntityClass(), provider(), fieldAddress, type).address();
    }

    private static IClass<String> str() {
        return IClass.getClass(String.class);
    }

    private static IClass<Boolean> bool() {
        return IClass.getClass(Boolean.class);
    }

    @Override
    public IDomainBuilder<E> owner(String fieldName) throws ApiException {
        this.owner = resolveByName(fieldName, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> owner(IField field) throws ApiException {
        this.owner = resolveByField(field, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> owner(ObjectAddress fieldAddress) throws ApiException {
        this.owner = resolveByAddress(fieldAddress, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> owned(String fieldName) throws ApiException {
        this.owned = resolveByName(fieldName, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> owned(IField field) throws ApiException {
        this.owned = resolveByField(field, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> owned(ObjectAddress fieldAddress) throws ApiException {
        this.owned = resolveByAddress(fieldAddress, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> shared(IField field) throws ApiException {
        this.shared = resolveByField(field, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> shared(String fieldName) throws ApiException {
        this.shared = resolveByName(fieldName, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> shared(ObjectAddress fieldAddress) throws ApiException {
        this.shared = resolveByAddress(fieldAddress, str());
        return this;
    }

    @Override
    public IDomainBuilder<E> hiddenable(String fieldName) throws ApiException {
        this.hiddenable = resolveByName(fieldName, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> hiddenable(IField field) throws ApiException {
        this.hiddenable = resolveByField(field, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> hiddenable(ObjectAddress fieldAddress) throws ApiException {
        this.hiddenable = resolveByAddress(fieldAddress, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> geolocalized(String fieldName) throws ApiException {
        this.geolocalized = resolveByName(fieldName, IClass.getClass(org.geojson.Point.class));
        return this;
    }

    @Override
    public IDomainBuilder<E> geolocalized(IField field) throws ApiException {
        this.geolocalized = resolveByField(field, IClass.getClass(org.geojson.Point.class));
        return this;
    }

    @Override
    public IDomainBuilder<E> geolocalized(ObjectAddress fieldAddress) throws ApiException {
        this.geolocalized = resolveByAddress(fieldAddress, IClass.getClass(org.geojson.Point.class));
        return this;
    }

    @Override
    public IDomainBuilder<E> superOwner(String fieldName) throws ApiException {
        this.superOwner = resolveByName(fieldName, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> superOwner(IField field) throws ApiException {
        this.superOwner = resolveByField(field, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> superOwner(ObjectAddress fieldAddress) throws ApiException {
        this.superOwner = resolveByAddress(fieldAddress, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> superTenant(String fieldName) throws ApiException {
        this.superTenant = resolveByName(fieldName, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> superTenant(IField field) throws ApiException {
        this.superTenant = resolveByField(field, bool());
        return this;
    }

    @Override
    public IDomainBuilder<E> superTenant(ObjectAddress fieldAddress) throws ApiException {
        this.superTenant = resolveByAddress(fieldAddress, bool());
        return this;
    }
}
