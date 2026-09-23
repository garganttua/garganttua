package com.garganttua.api.core.unit.builder;

import com.garganttua.api.core.api.ApiBuilder;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.List;

import org.javatuples.Pair;

import com.garganttua.api.commons.context.IEntityContext;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.api.commons.context.dsl.IDomainBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.core.entity.EntityContext;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;

@DisplayName("EntityBuilder Tests")
class EntityBuilderTest {

    @BeforeAll
    static void initReflection() {
        IClass.setReflection(ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner())
                .build());
    }

    // Test entity class
    public static class TestEntity {
        private String id;
        private String uuid;
        private String tenantId;
        private String name;
        private String optionalField;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getOptionalField() { return optionalField; }
        public void setOptionalField(String optionalField) { this.optionalField = optionalField; }
    }

    private IEntityBuilder<TestEntity> entityBuilder;
    private IDomainBuilder<TestEntity> domainBuilder;

    @BeforeEach
    void setUp() throws ApiException {
        domainBuilder = ApiBuilder.builder().domain(IClass.getClass(TestEntity.class));
        entityBuilder = domainBuilder.entity();
    }

    @Nested
    @DisplayName("Field Configuration")
    class FieldConfiguration {

        @Test
        @DisplayName("id() accepts valid field name")
        void idAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.id("id"));
        }

        @Test
        @DisplayName("id() rejects null")
        void idRejectsNull() {
            assertThrows(NullPointerException.class, () -> entityBuilder.id((String) null));
        }

        @Test
        @DisplayName("uuid() accepts valid field name")
        void uuidAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.uuid("uuid"));
        }

        @Test
        @DisplayName("uuid() rejects null")
        void uuidRejectsNull() {
            assertThrows(NullPointerException.class, () -> entityBuilder.uuid((String) null));
        }

        @Test
        @DisplayName("tenantId() accepts valid field name")
        void tenantIdAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.tenantId("tenantId"));
        }

        @Test
        @DisplayName("tenantId() rejects null")
        void tenantIdRejectsNull() {
            assertThrows(NullPointerException.class, () -> entityBuilder.tenantId((String) null));
        }

        @Test
        @DisplayName("Field methods return builder for chaining")
        void fieldMethodsReturnBuilder() throws ApiException {
            IEntityBuilder<TestEntity> result = entityBuilder.id("id");
            assertSame(entityBuilder, result);

            result = entityBuilder.uuid("uuid");
            assertSame(entityBuilder, result);

            result = entityBuilder.tenantId("tenantId");
            assertSame(entityBuilder, result);
        }
    }

    @Nested
    @DisplayName("Mandatory Fields")
    class MandatoryFields {

        @Test
        @DisplayName("mandatory() accepts valid field name")
        void mandatoryAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.mandatory("name"));
        }

        @Test
        @DisplayName("mandatory() can be called multiple times")
        void mandatoryCanBeCalledMultipleTimes() throws ApiException {
            entityBuilder.mandatory("name");
            assertDoesNotThrow(() -> entityBuilder.mandatory("optionalField"));
        }
    }

    @Nested
    @DisplayName("Unicity Fields")
    class UnicityFields {

        @Test
        @DisplayName("unicity() accepts valid field name")
        void unicityAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.unicity("name"));
        }

        @Test
        @DisplayName("unicity() can be called multiple times")
        void unicityCanBeCalledMultipleTimes() throws ApiException {
            entityBuilder.unicity("name");
            assertDoesNotThrow(() -> entityBuilder.unicity("uuid"));
        }

        @SuppressWarnings("unchecked")
        private IEntityDefinition<TestEntity> definition() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId");
            return ((EntityContext<TestEntity>) entityBuilder.build()).getEntityDefinition();
        }

        @Test
        @DisplayName("unicity(field) without a scope still means system — callers depend on it")
        void unicityWithoutScopeIsSystem() throws ApiException {
            entityBuilder.unicity("name");

            List<Pair<ObjectAddress, UnicityScope>> unicities = definition().unicities();
            assertEquals(1, unicities.size());
            assertEquals("name", unicities.get(0).getValue0().toString());
            assertEquals(UnicityScope.system, unicities.get(0).getValue1());
        }

        @Test
        @DisplayName("unicity(field, scope) records the scope it was given")
        void unicityKeepsTheDeclaredScope() throws ApiException {
            entityBuilder.unicity("name", UnicityScope.tenant);

            assertEquals(UnicityScope.tenant, definition().unicities().get(0).getValue1());
        }
    }

    @Nested
    @DisplayName("Index Declarations")
    class IndexDeclarations {

        @SuppressWarnings("unchecked")
        private List<EntityIndexRule> indexes() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId");
            return ((EntityContext<TestEntity>) entityBuilder.build()).getEntityDefinition().indexes();
        }

        @Test
        @DisplayName("index(field) carries the annotation defaults and a derived name")
        void bareIndexUsesAnnotationDefaults() throws ApiException {
            entityBuilder.index("name");

            List<EntityIndexRule> declared = indexes();
            assertEquals(1, declared.size());
            EntityIndexRule rule = declared.get(0);
            assertEquals("name", rule.field().toString());
            assertFalse(rule.unique());
            assertEquals(UnicityScope.tenant, rule.scope());
            assertEquals(IndexKind.standard, rule.kind());
            assertEquals("gg_name_tenant_standard_idx", rule.name());
        }

        @Test
        @DisplayName("index(field, unique, scope) reaches the definition — no annotation needed")
        void dslDeclaredUniqueIndex() throws ApiException {
            entityBuilder.index("name", true, UnicityScope.system);

            EntityIndexRule rule = indexes().get(0);
            assertTrue(rule.unique());
            assertEquals(UnicityScope.system, rule.scope());
            assertEquals("gg_name_system_standard_unique", rule.name());
        }

        @Test
        @DisplayName("index(field, unique, scope, kind, name) keeps the name it was given")
        void dslDeclaredNamedIndex() throws ApiException {
            entityBuilder.index("name", false, UnicityScope.system, IndexKind.text, "contacts_fulltext");

            EntityIndexRule rule = indexes().get(0);
            assertEquals(IndexKind.text, rule.kind());
            assertEquals("contacts_fulltext", rule.name());
        }

        @Test
        @DisplayName("index() rejects an unknown field")
        void indexRejectsUnknownField() {
            assertThrows(Exception.class, () -> entityBuilder.index("noSuchField"));
        }
    }

    @Nested
    @DisplayName("Update Fields")
    class UpdateFields {

        @Test
        @DisplayName("update() accepts valid field name")
        void updateAcceptsValidFieldName() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.update("name"));
        }

        @Test
        @DisplayName("update() with authority accepts valid parameters")
        void updateWithAuthorityAcceptsValidParams() throws ApiException {
            assertDoesNotThrow(() -> entityBuilder.update("name", "users:update"));
        }

        @SuppressWarnings("unchecked")
        private EntityUpdateRule ruleFor(String field) throws ApiException {
            EntityContext<TestEntity> context = (EntityContext<TestEntity>) entityBuilder.build();
            return context.getEntityDefinition().updates().stream()
                    .filter(r -> field.equals(r.field().toString()))
                    .findFirst().orElseThrow(() -> new AssertionError("no update rule for " + field));
        }

        @Test
        @DisplayName("update(field) defaults to no authority and ignoreNull = false")
        void updateDefaultsToErasingNulls() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId").update("name");

            EntityUpdateRule rule = ruleFor("name");
            assertNull(rule.authority());
            assertFalse(rule.ignoreNull(), "the default policy lets a null erase the stored value");
        }

        @Test
        @DisplayName("update(field, ignoreNull) records the null policy without an authority")
        void updateWithIgnoreNull() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId").update("name", true);

            EntityUpdateRule rule = ruleFor("name");
            assertNull(rule.authority());
            assertTrue(rule.ignoreNull());
        }

        @Test
        @DisplayName("update(field, authority, ignoreNull) records both")
        void updateWithAuthorityAndIgnoreNull() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId")
                    .update("name", "users:update", true)
                    .update("optionalField", "users:update", false);

            EntityUpdateRule guardedPatch = ruleFor("name");
            assertEquals("users:update", guardedPatch.authority());
            assertTrue(guardedPatch.ignoreNull());

            EntityUpdateRule guardedPut = ruleFor("optionalField");
            assertEquals("users:update", guardedPut.authority());
            assertFalse(guardedPut.ignoreNull(), "the policy is per-field, not per-builder");
        }
    }

    @Nested
    @DisplayName("Builder Navigation")
    class BuilderNavigation {

        @Test
        @DisplayName("up() returns parent domain builder")
        void upReturnsParentDomainBuilder() throws ApiException {
            IDomainBuilder<TestEntity> parent = entityBuilder
                    .id("id")
                    .uuid("uuid")
                    .tenantId("tenantId")
                    .up();

            assertSame(domainBuilder, parent);
        }
    }

    @Nested
    @DisplayName("Method Annotation")
    class MethodAnnotation {

        @Test
        @DisplayName("annotation(IMethod, IClass) no longer throws UnsupportedOperationException")
        void methodAnnotationDoesNotThrowUnsupported() throws Exception {
            IMethod getName = IClass.getClass(TestEntity.class).getMethod("getName");
            IClass<? extends Annotation> deprecated = IClass.getClass(Deprecated.class);

            assertDoesNotThrow(() -> entityBuilder.annotation(getName, deprecated));
        }

        @Test
        @DisplayName("annotation(IMethod, IClass) returns the builder for chaining")
        void methodAnnotationReturnsBuilder() throws Exception {
            IMethod getName = IClass.getClass(TestEntity.class).getMethod("getName");
            IClass<? extends Annotation> deprecated = IClass.getClass(Deprecated.class);

            IEntityBuilder<TestEntity> result = entityBuilder.annotation(getName, deprecated);
            assertSame(entityBuilder, result);
        }

        @Test
        @DisplayName("annotation(IMethod, IClass) records the pair into the method-annotation storage")
        void methodAnnotationIsRecordedInDefinition() throws Exception {
            IMethod getName = IClass.getClass(TestEntity.class).getMethod("getName");
            IClass<? extends Annotation> deprecated = IClass.getClass(Deprecated.class);

            entityBuilder.id("id").uuid("uuid").tenantId("tenantId").annotation(getName, deprecated);

            IEntityContext<TestEntity> context = entityBuilder.build();
            EntityContext<TestEntity> entityContext = (EntityContext<TestEntity>) context;

            assertEquals(1, entityContext.getEntityDefinition().annotatedMethods().size());
            assertEquals(deprecated, entityContext.getEntityDefinition().annotatedMethods().get(0).getValue1());
            assertTrue(entityContext.getEntityDefinition().annotatedFields().isEmpty());
        }

        @Test
        @DisplayName("annotation(IMethod, IClass) de-duplicates identical pairs")
        void methodAnnotationDeduplicates() throws Exception {
            IMethod getName = IClass.getClass(TestEntity.class).getMethod("getName");
            IClass<? extends Annotation> deprecated = IClass.getClass(Deprecated.class);

            entityBuilder.id("id").uuid("uuid").tenantId("tenantId")
                    .annotation(getName, deprecated)
                    .annotation(getName, deprecated);

            IEntityContext<TestEntity> context = entityBuilder.build();
            EntityContext<TestEntity> entityContext = (EntityContext<TestEntity>) context;

            assertEquals(1, entityContext.getEntityDefinition().annotatedMethods().size());
        }

        @Test
        @DisplayName("annotation(IMethod, IClass) rejects null arguments")
        void methodAnnotationRejectsNull() throws Exception {
            IMethod getName = IClass.getClass(TestEntity.class).getMethod("getName");
            IClass<? extends Annotation> deprecated = IClass.getClass(Deprecated.class);

            assertThrows(NullPointerException.class, () -> entityBuilder.annotation((IMethod) null, deprecated));
            assertThrows(NullPointerException.class, () -> entityBuilder.annotation(getName, null));
        }
    }

    @Nested
    @DisplayName("Build Validation")
    class BuildValidation {

        @Test
        @DisplayName("build() fails without id configured")
        void buildFailsWithoutId() throws ApiException {
            entityBuilder.uuid("uuid").tenantId("tenantId");
            assertThrows(ApiException.class, () -> entityBuilder.build());
        }

        @Test
        @DisplayName("build() fails without uuid configured")
        void buildFailsWithoutUuid() throws ApiException {
            entityBuilder.id("id").tenantId("tenantId");
            assertThrows(ApiException.class, () -> entityBuilder.build());
        }

        @Test
        @DisplayName("build() fails without tenantId configured")
        void buildFailsWithoutTenantId() throws ApiException {
            entityBuilder.id("id").uuid("uuid");
            assertThrows(ApiException.class, () -> entityBuilder.build());
        }

        @Test
        @DisplayName("build() succeeds with all required fields")
        void buildSucceedsWithAllRequiredFields() throws ApiException {
            entityBuilder.id("id").uuid("uuid").tenantId("tenantId");
            IEntityContext<TestEntity> context = entityBuilder.build();

            assertNotNull(context);
            assertEquals(IClass.getClass(TestEntity.class), context.getEntityClass());
        }
    }
}
