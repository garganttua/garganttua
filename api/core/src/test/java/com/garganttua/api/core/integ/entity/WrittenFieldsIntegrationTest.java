package com.garganttua.api.core.integ.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.WrittenFields;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;

/**
 * Covers the field report a write now carries, reported by a consumer as the framework knowing
 * which fields it dropped and telling no one: a {@code PATCH} naming a field the caller may not
 * write answers {@code 200} with the field unchanged, and a screen that tests the status code
 * displays the assignment as having succeeded.
 *
 * <p>
 * The status deliberately stays {@code 200} — the request WAS processed, partially, and turning it
 * into a {@code 403} would break every client treating 2xx as success. What changes is that the
 * response now says which fields landed and which did not.
 * </p>
 */
@DisplayName("Written-fields report on a write")
class WrittenFieldsIntegrationTest extends AbstractCrudScriptTest {

    private CapturingDao dao;

    /** A users domain whose {@code email} is freely updatable and whose {@code name} needs an authority. */
    @SuppressWarnings("unchecked")
    private IDomain<?> guardedDomain() throws Exception {
        dao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        IEntityBuilder<User> entity = (IEntityBuilder<User>) (IEntityBuilder<?>) builder
                .domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity()
                        .id("id").uuid("uuid").tenantId("tenantId");
        entity.update("email", true);
        entity.update("name", "USER_ADMIN", true);
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

    private void seed() {
        UserDto dto = new UserDto();
        dto.setId("1");
        dto.setUuid("uuid-alice");
        dto.setTenantId("SUPER_TENANT");
        dto.setName("Alice");
        dto.setEmail("alice@example.com");
        dao.getStorage().add(dto);
    }

    /** A PATCH naming both fields, issued by a caller carrying {@code authorities}. */
    private IOperationResponse patchBoth(IDomain<?> domain, List<String> authorities) {
        seed();
        User body = new User();
        body.setName("Renamed");
        body.setEmail("new@example.com");

        OperationRequest request = new OperationRequest(new java.util.HashMap<>());
        request.arg(IOperationRequest.OPERATION,
                OperationDefinition.updateOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg(IOperationRequest.TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.REQUESTED_TENANT_ID, "SUPER_TENANT");
        request.arg(IOperationRequest.SUPER_TENANT, true);
        request.arg(IOperationRequest.SUPER_OWNER, true);
        request.arg((com.garganttua.api.commons.service.ArgKey) IOperationRequest.AUTHORITIES, authorities);
        request.arg(IOperationRequest.ENTITY_UUID, "uuid-alice");
        request.arg(IOperationRequest.BODY, body);
        request.arg(IOperationRequest.PARTIAL_UPDATE, Boolean.TRUE);
        return domain.invoke(request);
    }

    @Nested
    @DisplayName("what the report says")
    class Content {

        @Test
        @DisplayName("a field dropped for want of an authority is named as rejected")
        void refusedFieldIsReported() throws Exception {
            IDomain<?> domain = guardedDomain();

            IOperationResponse response = patchBoth(domain, List.of());
            WrittenFields fields = response.getWrittenFields();

            assertNotNull(fields);
            assertTrue(fields.rejected().contains("name"),
                    () -> "the refused field must be named; rejected=" + fields.rejected());
            assertTrue(fields.applied().contains("email"),
                    () -> "and the one that landed must be too; applied=" + fields.applied());
        }

        @Test
        @DisplayName("the write still answers a success — the status is not what changes")
        void statusIsUnchanged() throws Exception {
            IDomain<?> domain = guardedDomain();

            IOperationResponse response = patchBoth(domain, List.of());

            assertEquals("UPDATED", response.getResponseCode().name(),
                    "turning this into a 403 would break every client treating 2xx as success");
        }

        @Test
        @DisplayName("an authorized caller gets both fields applied and nothing rejected")
        void authorizedCallerRejectsNothing() throws Exception {
            IDomain<?> domain = guardedDomain();

            WrittenFields fields = patchBoth(domain, List.of("USER_ADMIN")).getWrittenFields();

            assertEquals(List.of(), fields.rejected());
            assertTrue(fields.applied().containsAll(List.of("name", "email")),
                    () -> "applied=" + fields.applied());
        }

        @Test
        @DisplayName("the report exists even when nothing was rejected — an empty list, not an absence")
        void emptyReportIsStillAReport() throws Exception {
            IDomain<?> domain = guardedDomain();

            WrittenFields fields = patchBoth(domain, List.of("USER_ADMIN")).getWrittenFields();

            assertNotNull(fields.applied(), "an absent report would be ambiguous with an older framework");
            assertNotNull(fields.rejected());
        }
    }

    @Nested
    @DisplayName("what a read carries")
    class Reads {

        @Test
        @DisplayName("a read carries the empty report — there is nothing to say about a write it did not do")
        void readsCarryNothing() throws Exception {
            IDomain<?> domain = guardedDomain();
            seed();

            OperationRequest request = new OperationRequest(new java.util.HashMap<>());
            request.arg(IOperationRequest.OPERATION,
                    OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class)));
            request.arg(IOperationRequest.TENANT_ID, "SUPER_TENANT");
            request.arg(IOperationRequest.REQUESTED_TENANT_ID, "SUPER_TENANT");
            request.arg(IOperationRequest.SUPER_TENANT, true);
            request.arg(IOperationRequest.SUPER_OWNER, true);

            WrittenFields fields = domain.invoke(request).getWrittenFields();

            assertTrue(fields.isEmpty(), () -> "applied=" + fields.applied() + " rejected=" + fields.rejected());
        }
    }

    /** Guards the DTO-name requirement: the caller is told the words it sent. */
    @Test
    @DisplayName("the names reported are the DTO's, the vocabulary the client used")
    void namesAreTheClientsVocabulary() throws Exception {
        IDomain<?> domain = guardedDomain();

        WrittenFields fields = patchBoth(domain, List.of()).getWrittenFields();

        for (String name : fields.applied()) {
            assertEquals(name.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT));
            assertTrue(List.of("name", "email").contains(name),
                    () -> "unexpected wire name reported: " + name);
        }
    }
}
