package com.garganttua.api.core.integ.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Covers {@code mandatory}, reported by a consumer as doing less than its name promises: it let the
 * empty string through, and a second request could always erase what the first had required.
 *
 * <p>
 * The measured mechanism was not quite the one reported. {@code validateMandatories} DOES run on
 * update — on the MERGED entity, where the field is never {@code null} (it is either kept or set to
 * {@code ""}), so the null test always passed. The gap was the empty value, on both routes.
 * </p>
 */
@DisplayName("mandatory — the nonBlank policy")
class MandatoryPolicyIntegrationTest extends AbstractCrudScriptTest {

    private CapturingDao dao;

    /** A users domain whose {@code name} is mandatory under the given policy. */
    @SuppressWarnings("unchecked")
    private IDomain<?> domainWith(MandatoryPolicy policy) throws Exception {
        dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        IEntityBuilder<User> entity = (IEntityBuilder<User>) (IEntityBuilder<?>) builder
                .domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity()
                        .id("id").uuid("uuid").tenantId("tenantId");
        if (policy == null) {
            entity.mandatory("name");
        } else {
            entity.mandatory("name", policy);
        }
        entity.update("name", true);
        entity.update("email", true);
        entity.up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(dao)
                .up()
                .security().disable(true).up()
            .up();
        IApi context = buildAndStart(builder);
        return context.getDomain("users").orElseThrow();
    }

    private WorkflowResult create(IDomain<?> domain, String name) {
        User user = new User();
        user.setName(name);
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.createOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("entity", user);
        return executeScript(domain, request);
    }

    private WorkflowResult patchName(IDomain<?> domain, String name) {
        seed();
        User body = new User();
        body.setName(name);
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-alice");
        request.arg("entity", body);
        request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);
        return executeScript(domain, request);
    }

    private void seed() {
        UserDto dto = new UserDto();
        dto.setId("1");
        dto.setUuid("uuid-alice");
        dto.setTenantId("SUPER_TENANT");
        dto.setName("Alice");
        dao.getStorage().add(dto);
    }

    @Nested
    @DisplayName("nonBlank")
    class NonBlank {

        @Test
        @DisplayName("creation refuses null, the empty string and whitespace alike")
        void creationRefusesEveryEmptyForm() throws Exception {
            IDomain<?> domain = domainWith(MandatoryPolicy.nonBlank);

            assertFalse(create(domain, null).isSuccess(), "null was already refused");
            assertEquals(400, create(domain, "").code(), "the empty string used to be accepted");
            assertEquals(400, create(domain, "   ").code(), "whitespace used to be accepted");
            assertTrue(create(domain, "Alice").isSuccess(), "a real value still passes");
        }

        @Test
        @DisplayName("a PATCH may not erase the field with an empty value")
        void updateRefusesAnExplicitErasure() throws Exception {
            IDomain<?> domain = domainWith(MandatoryPolicy.nonBlank);

            assertEquals(400, patchName(domain, "").code(),
                    "this used to answer 200 and empty the field — the point of the report");
            assertEquals(400, patchName(domain, "   ").code());
        }

        @Test
        @DisplayName("a PATCH that does not name the field is left alone")
        void updateIgnoresAnAbsentField() throws Exception {
            IDomain<?> domain = domainWith(MandatoryPolicy.nonBlank);
            seed();

            User body = new User();
            body.setEmail("alice@example.com"); // name absent — the ordinary partial body
            OperationRequest request = superTenantScriptRequest(
                    OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg("type", "uuid");
            request.arg("identifier", "uuid-alice");
            request.arg("entity", body);
            request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);

            WorkflowResult result = executeScript(domain, request);

            assertTrue(result.isSuccess(),
                    () -> "replaying the creation rule would refuse nearly every screen request; code="
                            + result.code());
        }

        @Test
        @DisplayName("a PATCH naming a real value passes")
        void updateAcceptsARealValue() throws Exception {
            IDomain<?> domain = domainWith(MandatoryPolicy.nonBlank);

            assertTrue(patchName(domain, "Bob").isSuccess());
        }
    }

    @Nested
    @DisplayName("the default is unchanged")
    class DefaultPolicy {

        @Test
        @DisplayName("a bare mandatory still refuses only null")
        void bareMandatoryRefusesOnlyNull() throws Exception {
            IDomain<?> domain = domainWith(null);

            assertEquals(400, create(domain, null).code());
            assertTrue(create(domain, "").isSuccess(),
                    "no existing declaration changes meaning — the empty string still passes");
        }

        @Test
        @DisplayName("a bare mandatory still lets a PATCH empty the field")
        void bareMandatoryLetsUpdateEmptyIt() throws Exception {
            IDomain<?> domain = domainWith(null);

            assertTrue(patchName(domain, "").isSuccess(),
                    "unchanged: opting in is what tightens it");
        }
    }
}
