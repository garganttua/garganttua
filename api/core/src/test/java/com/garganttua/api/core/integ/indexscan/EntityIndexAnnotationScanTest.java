package com.garganttua.api.core.integ.indexscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.javatuples.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoTenantId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityIndexed;
import com.garganttua.api.commons.entity.annotations.EntitySuperTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenantId;
import com.garganttua.api.commons.entity.annotations.EntityUnicity;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.core.api.Api;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.core.observability.IObserver;
import com.garganttua.core.observability.LogEvent;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.observability.ObservableEvent;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * What {@code @EntityUnicity} and {@code @EntityIndexed} actually carry into the entity definition.
 *
 * <p>
 * The scope used to be dropped on the way: the scanner called the scope-less DSL overload, which
 * means {@code system}, while the annotation means {@code tenant}. Every per-tenant constraint was
 * silently promoted to a global one. And a unicity has never reached the database at all, which is
 * what {@code @EntityIndexed} is for — so the assembly says out loud which unicity is left without
 * an index.
 * </p>
 */
@DisplayName("@EntityUnicity scope and @EntityIndexed reach the entity definition")
class EntityIndexAnnotationScanTest extends AbstractCrudIntegrationTest {

    private static final String SCANNED_PACKAGE = "com.garganttua.api.core.integ.indexscan";

    /** The ApiSummary logger, addressed by name — the class itself is package-private. */
    private static final String SUMMARY_LOGGER = "com.garganttua.api.core.api.ApiSummary";

    // ── Tenant entity: multi-tenancy needs one, the test is not about it ──
    @Entity
    @EntityTenant
    public static class IndexTenant {
        @EntityId private String id;
        @EntityUuid private String uuid;
        @EntityTenantId private String tenantId;
        @EntitySuperTenant private Boolean superTenant;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    @Dto(entityClass = IndexTenant.class)
    public static class IndexTenantDto {
        @DtoId private String id;
        @DtoUuid private String uuid;
        @DtoTenantId private String tenantId;
        private Boolean superTenant;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    // ── Entity carrying both constraint families ──────────────────────────
    @Entity
    public static class IndexedContact {
        @EntityId private String id;
        @EntityUuid private String uuid;
        @EntityTenantId private String tenantId;

        /** Unique per tenant (annotation default), and the store is asked to hold it. */
        @EntityUnicity
        @EntityIndexed(unique = true)
        private String email;

        /** Globally unique, with NO index declared — the case the assembly must report. */
        @EntityUnicity(scope = UnicityScope.system)
        private String nationalId;

        /** Indexed without any uniqueness: a field queried often, not a constraint. */
        @EntityIndexed(kind = IndexKind.geo, scope = UnicityScope.system, name = "contacts_location")
        private String location;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getNationalId() { return nationalId; }
        public void setNationalId(String nationalId) { this.nationalId = nationalId; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    @Dto(entityClass = IndexedContact.class)
    public static class IndexedContactDto {
        @DtoId private String id;
        @DtoUuid private String uuid;
        @DtoTenantId private String tenantId;
        private String email;
        private String nationalId;
        private String location;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getNationalId() { return nationalId; }
        public void setNationalId(String nationalId) { this.nationalId = nationalId; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    private static IApi scannedApi() throws ApiException {
        IApiBuilder builder = newBuilder();
        ((com.garganttua.api.core.api.ApiBuilder) builder).withPackage(SCANNED_PACKAGE);
        ((com.garganttua.core.dsl.IAutomaticBuilder<?, ?>) builder).autoDetect(true);
        builder.domain(IClass.getClass(IndexTenant.class))
                .dto(IClass.getClass(IndexTenantDto.class))
                    .db(new CapturingDao())
                .up()
            .up();
        builder.domain(IClass.getClass(IndexedContact.class))
                .dto(IClass.getClass(IndexedContactDto.class))
                    .db(new CapturingDao())
                .up()
            .up();
        return buildAndStart(builder);
    }

    private static IEntityDefinition<?> contactDefinition(IApi api) {
        Map<String, IDomain<?>> domains = ((Api) api).getDomains();
        IDomain<?> domain = domains.values().stream()
                .filter(d -> d.getEntityClass().represents(IndexedContact.class))
                .findFirst().orElse(null);
        assertNotNull(domain, "IndexedContact domain should be registered");
        return domain.getDomainDefinition().entityDefinition();
    }

    private static UnicityScope scopeOf(IEntityDefinition<?> def, String field) {
        for (Pair<ObjectAddress, UnicityScope> unicity : def.unicities()) {
            if (field.equals(unicity.getValue0().toString())) {
                return unicity.getValue1();
            }
        }
        throw new AssertionError("no unicity declared for " + field + "; got " + def.unicities());
    }

    private static EntityIndexRule indexOf(IEntityDefinition<?> def, String field) {
        return def.indexes().stream()
                .filter(index -> field.equals(index.field().toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no index declared for " + field + "; got " + def.indexes()));
    }

    @Nested
    @DisplayName("the declared scope survives the scan")
    class Scopes {

        @Test
        @DisplayName("@EntityUnicity keeps its tenant default instead of being promoted to system")
        void tenantScopeIsKept() throws ApiException {
            assertEquals(UnicityScope.tenant, scopeOf(contactDefinition(scannedApi()), "email"));
        }

        @Test
        @DisplayName("@EntityUnicity(scope = system) is carried as declared")
        void systemScopeIsKept() throws ApiException {
            assertEquals(UnicityScope.system, scopeOf(contactDefinition(scannedApi()), "nationalId"));
        }
    }

    @Nested
    @DisplayName("@EntityIndexed reaches the definition")
    class Indexes {

        @Test
        @DisplayName("a unique index keeps its uniqueness, scope and derived name")
        void uniqueIndexIsCarried() throws ApiException {
            EntityIndexRule rule = indexOf(contactDefinition(scannedApi()), "email");

            assertTrue(rule.unique());
            assertEquals(UnicityScope.tenant, rule.scope());
            assertEquals(IndexKind.standard, rule.kind());
            assertEquals("gg_email_tenant_standard_unique", rule.name());
        }

        @Test
        @DisplayName("kind and explicit name are carried, and a bare index stays non-unique")
        void kindAndNameAreCarried() throws ApiException {
            EntityIndexRule rule = indexOf(contactDefinition(scannedApi()), "location");

            assertFalse(rule.unique());
            assertEquals(IndexKind.geo, rule.kind());
            assertEquals(UnicityScope.system, rule.scope());
            assertEquals("contacts_location", rule.name());
        }

        @Test
        @DisplayName("a field with no @EntityIndexed declares no index")
        void unindexedFieldDeclaresNothing() throws ApiException {
            IEntityDefinition<?> def = contactDefinition(scannedApi());

            assertFalse(def.indexes().stream().anyMatch(i -> "nationalId".equals(i.field().toString())),
                    "nationalId carries no @EntityIndexed; got " + def.indexes());
        }
    }

    @Nested
    @DisplayName("the assembly reports a unicity the database does not hold")
    class UnbackedUnicityWarning {

        @Test
        @DisplayName("the summary warns for the un-indexed unicity, and only for it")
        void warnsOnlyForTheUnbackedUnicity() throws ApiException {
            IApi api = scannedApi();

            List<String> warnings = new ArrayList<>();
            Logger summaryLog = Logger.getLogger(SUMMARY_LOGGER);
            IObserver<ObservableEvent> collect = event -> {
                if (event instanceof LogEvent log && log.level() == LogEvent.Level.WARN) {
                    warnings.add(log.message());
                }
            };
            summaryLog.addObserver(collect);
            try {
                ((Api) api).getSummaryItems();
            } finally {
                summaryLog.removeObserver(collect);
            }

            assertTrue(warnings.stream().anyMatch(w -> w.contains("nationalId") && w.contains("system")),
                    "the system-scoped unicity has no unique index and must be named; got " + warnings);
            assertFalse(warnings.stream().anyMatch(w -> w.contains("email")),
                    "email IS backed by a unique tenant-scoped index and must not be reported; got " + warnings);
        }
    }
}
