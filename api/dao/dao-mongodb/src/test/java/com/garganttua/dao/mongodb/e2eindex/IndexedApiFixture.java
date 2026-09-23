package com.garganttua.dao.mongodb.e2eindex;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoTenantId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityIndexed;
import com.garganttua.api.commons.entity.annotations.EntitySuperTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenantId;
import com.garganttua.api.commons.entity.annotations.EntityUnicity;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.core.dsl.IAutomaticBuilder;
import com.garganttua.core.dsl.dependency.IDependentBuilder;
import com.garganttua.core.expression.dsl.ExpressionContextBuilder;
import com.garganttua.core.expression.dsl.IExpressionContextBuilder;
import com.garganttua.core.injection.context.dsl.IInjectionContextBuilder;
import com.garganttua.core.injection.context.dsl.InjectionContextBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.IReflectionBuilder;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;
import com.garganttua.core.reflections.ReflectionsAnnotationScanner;
import com.garganttua.core.runtime.RuntimeContextFactory;
import com.garganttua.core.runtime.dsl.IRuntimesBuilder;
import com.garganttua.core.runtime.dsl.RuntimesBuilder;
import com.garganttua.core.script.dsl.IScriptsBuilder;
import com.garganttua.core.script.dsl.ScriptsBuilder;
import com.garganttua.core.workflow.dsl.IWorkflowsBuilder;
import com.garganttua.core.workflow.dsl.WorkflowsBuilder;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.mongodb.MongoIndexMode;
import com.mongodb.client.MongoDatabase;

/**
 * The entity a consumer would write, and the engine a consumer would start it on.
 *
 * <p>
 * Everything here is deliberately the REAL thing. The entity declares its constraints with the
 * annotations and nothing else — no DSL call restates them, so what the test observes in the
 * database can only have come from the annotation. The api is assembled with the real
 * {@code ApiBuilder} and the real {@link MongoDao}: the defect the consumer reported lived in the
 * joints between those parts, and a stand-in for any one of them would hide it. The neighbouring
 * suites stop before this joint — one asserts what the scan puts in the definition, the other what
 * the index manager does with a hand-built spec — which is how a constraint nobody held came to be
 * believed.
 * </p>
 *
 * <p>
 * The fixture lives in its own package so that {@code withPackage} scans these two entities and
 * nothing else: the module's other test entities sit in {@code com.garganttua.dao.mongodb} and
 * would otherwise be dragged into the api under test.
 * </p>
 */
final class IndexedApiFixture {

    /** The package the api scans — this one, holding exactly the two entities below. */
    static final String SCANNED_PACKAGE = "com.garganttua.dao.mongodb.e2eindex";

    static final String SUPER_TENANT = "SUPER_TENANT";

    /** The collection the indexed domain is stored in, named by the domain. */
    static final String MEMBERS = "members";

    private IndexedApiFixture() {
        // Fixture holder
    }

    // ------------------------------------------------------------------
    // The entity under test — constraints declared by ANNOTATION only
    // ------------------------------------------------------------------

    /**
     * A member whose email must be unique within its tenant and whose national id must be unique
     * across the whole system — each declared unique to the framework and indexed in the store.
     */
    @Entity
    public static class Member {
        @EntityId private String id;
        @EntityUuid private String uuid;
        @EntityTenantId private String tenantId;

        /** Unique per tenant — the annotation's default scope — and held by the database. */
        @EntityUnicity
        @EntityIndexed(unique = true)
        private String email;

        /** Unique across every tenant, which only the database can enforce for a tenant caller. */
        @EntityUnicity(scope = UnicityScope.system)
        @EntityIndexed(unique = true, scope = UnicityScope.system)
        private String nationalId;

        public String getId() { return this.id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return this.uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return this.tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getEmail() { return this.email; }
        public void setEmail(String email) { this.email = email; }
        public String getNationalId() { return this.nationalId; }
        public void setNationalId(String nationalId) { this.nationalId = nationalId; }
    }

    /** The stored shape of a {@link Member}; field names match, so the index names read plainly. */
    @Dto(entityClass = Member.class)
    public static class MemberDto {
        @DtoId private String id;
        @DtoUuid private String uuid;
        @DtoTenantId private String tenantId;
        private String email;
        private String nationalId;

        public String getId() { return this.id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return this.uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return this.tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getEmail() { return this.email; }
        public void setEmail(String email) { this.email = email; }
        public String getNationalId() { return this.nationalId; }
        public void setNationalId(String nationalId) { this.nationalId = nationalId; }
    }

    // ── Multi-tenancy needs a tenant entity; the test is not about it ──

    /** The tenant domain the api requires. It declares no index, so it costs no command. */
    @Entity
    @EntityTenant
    public static class MemberTenant {
        @EntityId private String id;
        @EntityUuid private String uuid;
        @EntityTenantId private String tenantId;
        @EntitySuperTenant private Boolean superTenant;

        public String getId() { return this.id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return this.uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return this.tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getSuperTenant() { return this.superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    /** The stored shape of a {@link MemberTenant}. */
    @Dto(entityClass = MemberTenant.class)
    public static class MemberTenantDto {
        @DtoId private String id;
        @DtoUuid private String uuid;
        @DtoTenantId private String tenantId;
        private Boolean superTenant;

        public String getId() { return this.id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return this.uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return this.tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getSuperTenant() { return this.superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    /**
     * Starts the api over {@code database}, with both domains backed by a real {@link MongoDao}.
     *
     * <p>
     * Starting it is what lays the indexes: {@code registerDomain} is called during the build, so
     * by the time this returns the database has been asked for everything the entity declared.
     * </p>
     *
     * @param database  the database to store both domains in
     * @param indexMode what the members DAO may do about the declared indexes
     * @return the started api
     * @throws ApiException if the assembly or the start fails
     */
    static IApi start(MongoDatabase database, MongoIndexMode indexMode) throws ApiException {
        IApiBuilder builder = newBuilder();
        ((ApiBuilder) builder).withPackage(SCANNED_PACKAGE);
        ((IAutomaticBuilder<?, ?>) builder).autoDetect(true);

        builder.domain(IClass.getClass(MemberTenant.class))
                .dto(IClass.getClass(MemberTenantDto.class))
                    .db(new MongoDao(database, "tenants", indexMode))
                .up()
            .up();
        builder.domain(IClass.getClass(Member.class))
                .dto(IClass.getClass(MemberDto.class))
                    .db(new MongoDao(database, MEMBERS, indexMode))
                .up()
                .security().disable(true).up()
            .up();

        IApi api = builder.build();
        api.onInit();
        api.onStart();
        return api;
    }

    /**
     * The builder chain an application server would assemble — reflection, injection, expressions,
     * runtimes, scripts and workflows, wired exactly as {@code AbstractCrudIntegrationTest} does in
     * api-core, which is the closest thing the platform has to a reference assembly.
     */
    @SuppressWarnings("unchecked")
    private static IApiBuilder newBuilder() throws ApiException {
        IReflectionBuilder reflection = ReflectionBuilder.builder()
                .withProvider(new RuntimeReflectionProvider())
                .withScanner(new ReflectionsAnnotationScanner());
        IClass.setReflection(reflection.build());

        IInjectionContextBuilder injection = InjectionContextBuilder.builder()
                .childContextFactory(new RuntimeContextFactory());
        ((IDependentBuilder<IInjectionContextBuilder, ?>) injection).provide(reflection);
        injection.build();

        IExpressionContextBuilder expressions = ExpressionContextBuilder.builder();
        expressions.autoDetect(true);
        expressions.withPackage("com.garganttua.core.expression.functions");
        expressions.withPackage("com.garganttua.core.script.functions");
        expressions.withPackage("com.garganttua.core.observability");
        expressions.withPackage("com.garganttua.api.core.expression");
        ((IDependentBuilder<IExpressionContextBuilder, ?>) expressions).provide(injection);
        expressions.build();

        IRuntimesBuilder runtimes = RuntimesBuilder.builder();
        ((IDependentBuilder<IRuntimesBuilder, ?>) runtimes).provide(injection);
        IScriptsBuilder scripts = ScriptsBuilder.builder();
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(injection);
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(expressions);
        ((IDependentBuilder<IScriptsBuilder, ?>) scripts).provide(runtimes);
        IWorkflowsBuilder workflows = WorkflowsBuilder.builder();
        ((IDependentBuilder<IWorkflowsBuilder, ?>) workflows).provide(injection);
        ((IDependentBuilder<IWorkflowsBuilder, ?>) workflows).provide(scripts);

        IApiBuilder builder = ApiBuilder.builder();
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(reflection);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(injection);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(expressions);
        ((IDependentBuilder<IApiBuilder, IApi>) builder).provide(workflows);
        return builder.superTenantId(SUPER_TENANT).superTenantAutoCreate(false);
    }
}
