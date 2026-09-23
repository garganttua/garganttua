package com.garganttua.api.commons.context.dsl;

import java.lang.annotation.Annotation;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;

import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.entity.IUuidGenerator;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.core.dsl.IAutomaticLinkedBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

public interface IEntityBuilder<E> extends IAutomaticLinkedBuilder<IEntityBuilder<E>, IDomainBuilder<E>, IEntityContext<E>> {

    IEntityBuilder<E> id(String string) throws ApiException;

    IEntityBuilder<E> id(IField field) throws ApiException;

    IEntityBuilder<E> id(ObjectAddress fieldAddress) throws ApiException;

    IEntityBuilder<E> uuid(String string) throws ApiException;

    IEntityBuilder<E> uuid(IField field) throws ApiException;

    IEntityBuilder<E> uuid(ObjectAddress fieldAddress) throws ApiException;

    /**
     * When {@code true}, the framework (re)generates the uuid at creation even if the
     * client supplied one — the client value is discarded. Default {@code false}
     * (a client-supplied uuid is kept; a missing one is generated).
     */
    IEntityBuilder<E> overwriteUuid(boolean overwrite);

    /**
     * Declares a custom uuid generator for this domain, used wherever the framework
     * assigns the uuid (no client value, or {@link #overwriteUuid(boolean)} on). Default:
     * a time-ordered UUID v7.
     */
    IEntityBuilder<E> uuidGenerator(IUuidGenerator generator);

    IEntityBuilder<E> tenantId(String string) throws ApiException;

    IEntityBuilder<E> tenantId(IField field) throws ApiException;

    IEntityBuilder<E> tenantId(ObjectAddress fieldAddress) throws ApiException;

    IEntityBuilder<E> mandatory(IField field) throws ApiException;

    IEntityBuilder<E> mandatory(String string) throws ApiException;

    IEntityBuilder<E> mandatory(ObjectAddress fieldAddress) throws ApiException;

    /**
     * Declares a mandatory field under an explicit policy.
     *
     * <p>
     * {@code mandatory(field)} means "not {@code null}" and nothing more — an empty string passes.
     * {@link MandatoryPolicy#nonBlank} is the form that matches what a screen guard does: it also
     * refuses {@code ""} and whitespace, at creation, and at update on any value the client
     * actually sent (an absent field is still left alone).
     * </p>
     *
     * @param field  the field on the entity
     * @param policy what the field is required to carry
     * @return this builder
     * @throws ApiException if the field cannot be resolved on the entity
     */
    IEntityBuilder<E> mandatory(IField field, MandatoryPolicy policy) throws ApiException;

    /** @see #mandatory(IField, MandatoryPolicy) */
    IEntityBuilder<E> mandatory(String string, MandatoryPolicy policy) throws ApiException;

    /** @see #mandatory(IField, MandatoryPolicy) */
    IEntityBuilder<E> mandatory(ObjectAddress fieldAddress, MandatoryPolicy policy) throws ApiException;

    IEntityBuilder<E> unicity(IField field) throws ApiException;

    IEntityBuilder<E> unicity(String string) throws ApiException;

    IEntityBuilder<E> unicity(ObjectAddress fieldAddress) throws ApiException;

    IEntityBuilder<E> unicity(String string, UnicityScope system) throws ApiException;

    IEntityBuilder<E> unicity(IField field, UnicityScope system) throws ApiException;

    IEntityBuilder<E> unicity(ObjectAddress fieldAddress, UnicityScope system) throws ApiException;

    /**
     * Declares an index the STORE must carry on the field — the DSL counterpart of
     * {@link com.garganttua.api.commons.entity.annotations.EntityIndexed}, and the only form of
     * uniqueness a concurrent write cannot walk through.
     *
     * <p>
     * The bare form mirrors the annotation's defaults: a non-unique, {@link UnicityScope#tenant}
     * scoped {@link IndexKind#standard} index, named by derivation. Note that this differs from
     * {@link #unicity(String)}, whose scope-less form means {@link UnicityScope#system} — a
     * historical default kept as-is because callers depend on it.
     * </p>
     *
     * @param string the field on the entity
     * @return this builder
     * @throws ApiException if the field cannot be resolved on the entity
     */
    IEntityBuilder<E> index(String string) throws ApiException;

    /** @see #index(String) */
    IEntityBuilder<E> index(IField field) throws ApiException;

    /** @see #index(String) */
    IEntityBuilder<E> index(ObjectAddress fieldAddress) throws ApiException;

    /**
     * Declares a {@link IndexKind#standard} index with an explicit uniqueness and scope, named by
     * derivation.
     *
     * @param string the field on the entity
     * @param unique {@code true} to have the store refuse a duplicate value
     * @param scope  {@link UnicityScope#tenant} to compose the index with the tenant identifier,
     *               {@link UnicityScope#system} to index the field alone
     * @return this builder
     * @throws ApiException if the field cannot be resolved on the entity
     */
    IEntityBuilder<E> index(String string, boolean unique, UnicityScope scope) throws ApiException;

    /** @see #index(String, boolean, UnicityScope) */
    IEntityBuilder<E> index(IField field, boolean unique, UnicityScope scope) throws ApiException;

    /** @see #index(String, boolean, UnicityScope) */
    IEntityBuilder<E> index(ObjectAddress fieldAddress, boolean unique, UnicityScope scope) throws ApiException;

    /**
     * Full form: uniqueness, scope, kind, and the name the index is created under.
     *
     * @param string the field on the entity
     * @param unique {@code true} to have the store refuse a duplicate value
     * @param scope  the scope the index spans
     * @param kind   what kind of index the store must build
     * @param name   the index name, or {@code null}/empty to derive a stable one from the declaration
     * @return this builder
     * @throws ApiException if the field cannot be resolved on the entity
     */
    IEntityBuilder<E> index(String string, boolean unique, UnicityScope scope, IndexKind kind, String name)
            throws ApiException;

    /** @see #index(String, boolean, UnicityScope, IndexKind, String) */
    IEntityBuilder<E> index(IField field, boolean unique, UnicityScope scope, IndexKind kind, String name)
            throws ApiException;

    /** @see #index(String, boolean, UnicityScope, IndexKind, String) */
    IEntityBuilder<E> index(ObjectAddress fieldAddress, boolean unique, UnicityScope scope, IndexKind kind,
            String name) throws ApiException;

    /**
     * Declares a field a caller may valorize at CREATION (no authority required). Declaring any
     * {@code create(...)} turns creation into a WHITELIST: only declared fields are kept from the
     * client body, every other client-supplied field is stripped before persist. With no
     * {@code create(...)} declared at all, creation is unrestricted (the client body is kept as-is).
     * The CREATE-time analogue of {@link #update(String)}.
     */
    IEntityBuilder<E> create(String string) throws ApiException;

    IEntityBuilder<E> create(IField field) throws ApiException;

    IEntityBuilder<E> create(ObjectAddress fieldAddress) throws ApiException;

    /**
     * Declares a field a caller may valorize at CREATION only when it carries {@code authority};
     * otherwise the field is stripped from the created entity. The CREATE-time analogue of
     * {@link #update(String, String)}.
     */
    IEntityBuilder<E> create(String string, String authority) throws ApiException;

    IEntityBuilder<E> create(IField field, String authority) throws ApiException;

    IEntityBuilder<E> create(ObjectAddress fieldAddress, String authority) throws ApiException;

    /**
     * Declares a field a caller may valorize at UPDATE (no authority required). Declaring any
     * {@code update(...)} turns the update into a WHITELIST: only declared fields are merged from
     * the client body onto the stored entity.
     *
     * <p>A {@code null} incoming value ERASES the stored value (PUT semantics). Use
     * {@link #update(String, boolean)} with {@code ignoreNull = true} for PATCH semantics, where a
     * {@code null} means "not supplied" and leaves the stored value untouched.
     */
    IEntityBuilder<E> update(String string) throws ApiException;

    IEntityBuilder<E> update(IField field) throws ApiException;

    IEntityBuilder<E> update(ObjectAddress fieldAddress) throws ApiException;

    /**
     * Declares a field a caller may valorize at UPDATE, choosing how a {@code null} incoming value
     * is interpreted: {@code ignoreNull = false} (the default) lets a {@code null} erase the stored
     * value, {@code ignoreNull = true} treats it as "not supplied" and leaves the stored value
     * untouched.
     */
    IEntityBuilder<E> update(String string, boolean ignoreNull) throws ApiException;

    IEntityBuilder<E> update(IField field, boolean ignoreNull) throws ApiException;

    IEntityBuilder<E> update(ObjectAddress fieldAddress, boolean ignoreNull) throws ApiException;

    /**
     * Declares a field a caller may valorize at UPDATE only when it carries {@code authority};
     * otherwise the field is left untouched on the stored entity. A {@code null} incoming value
     * erases — see {@link #update(String, String, boolean)} to opt into PATCH semantics.
     */
    IEntityBuilder<E> update(String string, String authority) throws ApiException;

    IEntityBuilder<E> update(IField field, String authority) throws ApiException;

    IEntityBuilder<E> update(ObjectAddress fieldAddress, String authority) throws ApiException;

    /**
     * Full form: an authority gate plus the null-handling policy for the field.
     *
     * @param authority  authority the caller must carry, or {@code null}/empty for none
     * @param ignoreNull {@code true} to leave the stored value untouched when the incoming value is
     *                   {@code null}; {@code false} to let the {@code null} erase it
     */
    IEntityBuilder<E> update(String string, String authority, boolean ignoreNull) throws ApiException;

    IEntityBuilder<E> update(IField field, String authority, boolean ignoreNull) throws ApiException;

    IEntityBuilder<E> update(ObjectAddress fieldAddress, String authority, boolean ignoreNull) throws ApiException;

    IEntityBuilder<E> annotation(String elementName, IClass<? extends Annotation> annotation) throws ApiException;

    IEntityBuilder<E> annotation(IField field, IClass<? extends Annotation> annotation) throws ApiException;

    IEntityBuilder<E> annotation(ObjectAddress elementAddress, IClass<? extends Annotation> annotation) throws ApiException;

    IEntityBuilder<E> annotation(IMethod method, IClass<? extends Annotation> annotation) throws ApiException;

    IEntityMethodBinderBuilder<E> afterGet(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> afterGet(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> afterGet(ObjectAddress methodAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeCreate(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeCreate(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeCreate(ObjectAddress methodAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeUpdate(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeUpdate(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeUpdate(ObjectAddress fieldmethodAddressAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeDelete(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeDelete(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> beforeDelete(ObjectAddress methodAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> afterCreate(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> afterCreate(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> afterCreate(ObjectAddress methodAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> afterUpdate(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> afterUpdate(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> afterUpdate(ObjectAddress methodAddress) throws ApiException;

    IEntityMethodBinderBuilder<E> afterDelete(String methodName) throws ApiException;

    IEntityMethodBinderBuilder<E> afterDelete(IMethod method) throws ApiException;

    IEntityMethodBinderBuilder<E> afterDelete(ObjectAddress methodAddress) throws ApiException;

}
