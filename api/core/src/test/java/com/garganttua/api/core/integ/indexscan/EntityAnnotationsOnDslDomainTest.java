package com.garganttua.api.core.integ.indexscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityIndexed;
import com.garganttua.api.commons.entity.annotations.EntityUnicity;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.core.api.Api;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.core.dsl.IAutomaticBuilder;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * The entity annotations of a domain declared by hand, through the DSL, with no package scanned.
 *
 * <p>
 * Reported by a consumer: {@code @EntityIndexed} produced no index at all — no error, no log — on
 * an api that declares its domains with {@code apiBuilder.domain(...)}, which is the form the
 * READMEs show. Only the package scan read the annotations, and it returns at once when no package
 * is declared. The annotations now belong to the entity class and are read under auto-detection
 * however the domain was assembled; without auto-detection, none is read, as for every other
 * auto-detected asset.
 * </p>
 */
@DisplayName("Entity annotations on a DSL-declared domain")
class EntityAnnotationsOnDslDomainTest extends AbstractCrudIntegrationTest {

    /** The consumer's shape: no {@code @Entity}, no {@code @Dto} — the DSL declares the domain. */
    public static class Subscription {
        private String id;
        private String uuid;

        /** A second id candidate: the DSL's {@code id("id")} must win over it. */
        @EntityId
        private String code;

        @EntityUuid
        private String reference;

        @EntityIndexed(unique = true, scope = UnicityScope.system)
        @EntityUnicity(scope = UnicityScope.system)
        private String businessKey;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getBusinessKey() { return businessKey; }
        public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    }

    public static class SubscriptionDto {
        private String id;
        private String uuid;
        private String code;
        private String reference;
        private String businessKey;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getBusinessKey() { return businessKey; }
        public void setBusinessKey(String businessKey) { this.businessKey = businessKey; }
    }

    /** The domain as the README declares it: by hand, no package, ids given by the DSL. */
    private static IEntityDefinition<?> declaredByHand(boolean autoDetect, boolean alsoByDsl) throws ApiException {
        IApiBuilder builder = newBuilder();
        builder.multiTenant(false);
        ((IAutomaticBuilder<?, ?>) builder).autoDetect(autoDetect);
        var entity = builder.domain(IClass.getClass(Subscription.class))
                .entity()
                    .id("id").uuid("uuid");
        if (alsoByDsl) {
            entity.index("businessKey", true, UnicityScope.system)
                  .unicity("businessKey", UnicityScope.system);
        }
        entity.up()
                .dto(IClass.getClass(SubscriptionDto.class))
                    .id("id").uuid("uuid")
                    .db(new CapturingDao())
                .up()
            .up();
        IApi api = buildAndStart(builder);

        IDomain<?> domain = ((Api) api).getDomains().values().stream()
                .filter(d -> d.getEntityClass().represents(Subscription.class))
                .findFirst().orElse(null);
        assertNotNull(domain, "the Subscription domain should be registered");
        return domain.getDomainDefinition().entityDefinition();
    }

    private static List<EntityIndexRule> indexesOn(IEntityDefinition<?> def, String field) {
        return def.indexes().stream().filter(i -> field.equals(i.field().toString())).toList();
    }

    @Nested
    @DisplayName("under auto-detection")
    class AutoDetected {

        @Test
        @DisplayName("@EntityIndexed produces the same rule the package scan produces")
        void indexIsRead() throws ApiException {
            List<EntityIndexRule> rules = indexesOn(declaredByHand(true, false), "businessKey");

            assertEquals(1, rules.size(), "one index declared, one rule; got " + rules);
            assertTrue(rules.get(0).unique());
            assertEquals(UnicityScope.system, rules.get(0).scope());
            assertEquals("gg_businessKey_system_standard_unique", rules.get(0).name());
        }

        @Test
        @DisplayName("@EntityUnicity is read with its declared scope")
        void unicityIsRead() throws ApiException {
            IEntityDefinition<?> def = declaredByHand(true, false);

            assertTrue(def.unicities().stream().anyMatch(u -> "businessKey".equals(u.getValue0().toString())
                    && u.getValue1() == UnicityScope.system), "got " + def.unicities());
        }

        @Test
        @DisplayName("the DSL's id and uuid win over @EntityId / @EntityUuid on other fields")
        void dslIdentityWins() throws ApiException {
            IEntityDefinition<?> def = declaredByHand(true, false);

            assertEquals(new ObjectAddress("id"), def.id());
            assertEquals(new ObjectAddress("uuid"), def.uuid());
        }

        @Test
        @DisplayName("a constraint declared both by the DSL and by the annotation is recorded once")
        void declaredTwiceIsRecordedOnce() throws ApiException {
            IEntityDefinition<?> def = declaredByHand(true, true);

            assertEquals(1, indexesOn(def, "businessKey").size(), "got " + def.indexes());
            assertEquals(1, def.unicities().stream()
                    .filter(u -> "businessKey".equals(u.getValue0().toString())).count(),
                    "got " + def.unicities());
        }
    }

    @Nested
    @DisplayName("without auto-detection")
    class NotAutoDetected {

        @Test
        @DisplayName("no annotation is read — only what the DSL declares")
        void nothingIsRead() throws ApiException {
            IEntityDefinition<?> def = declaredByHand(false, false);

            assertTrue(def.indexes().isEmpty(), "got " + def.indexes());
            assertTrue(def.unicities().isEmpty(), "got " + def.unicities());
        }
    }
}
