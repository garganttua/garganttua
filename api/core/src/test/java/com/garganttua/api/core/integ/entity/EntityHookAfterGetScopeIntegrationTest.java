package com.garganttua.api.core.integ.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.context.dsl.IEntityBuilder;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.api.core.integ.crud.AbstractCrudScriptTest;
import com.garganttua.api.core.service.OperationRequest;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.workflow.WorkflowResult;

/**
 * Covers the scope of {@code afterGet}, reported by a consumer as firing on
 * {@code GET /<domain>/{uuid}} and never on {@code GET /<domain>}.
 *
 * <p>
 * The natural use of the hook is to strip a secret before the entity goes on the wire, so covering
 * one read route and not the other is a half-protection that nothing announces. The cause was not a
 * deliberate contract but a default: {@code READ_ALL.gs} guarded both the injection and the hook
 * behind {@code mode == "full"}, and a plain read sends no {@code mode} at all — so the guard was
 * false and BOTH were skipped.
 * </p>
 */
@DisplayName("afterGet scope (collection read vs unit read)")
class EntityHookAfterGetScopeIntegrationTest extends AbstractCrudScriptTest {

    /** Stands in for the reported use: blanking a field before the entity is rendered. */
    public static final class SecretStripper {
        static final List<String> seen = new ArrayList<>();

        public static void strip(User user) {
            if (user != null) {
                seen.add(user.getName());
                user.setEmail(null);
            }
        }
    }

    private CapturingDao userDao;
    private IDomain<?> userCtx;

    @BeforeEach
    void setUp() throws Exception {
        SecretStripper.seen.clear();
        userDao = new CapturingDao();

        IApiBuilder builder = newBuilder();
        @SuppressWarnings("unchecked")
        IEntityBuilder<User> entity = (IEntityBuilder<User>) (IEntityBuilder<?>) builder
                .domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity()
                        .id("id").uuid("uuid").tenantId("tenantId");
        IMethod strip = IClass.getClass(SecretStripper.class)
                .getMethod("strip", IClass.getClass(User.class));
        entity.afterGet(strip);
        entity.up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(userDao)
                .up()
                .security().disable(true).up()
            .up();
        IApi context = buildAndStart(builder);
        userCtx = context.getDomain("users").orElseThrow();

        seed("Alice", "Bob");
    }

    private void seed(String... names) {
        int i = 1;
        for (String name : names) {
            UserDto dto = new UserDto();
            dto.setId(String.valueOf(i++));
            dto.setUuid("uuid-" + name.toLowerCase(Locale.ROOT));
            dto.setTenantId("SUPER_TENANT");
            dto.setName(name);
            dto.setEmail(name.toLowerCase(Locale.ROOT) + "@example.com");
            userDao.getStorage().add(dto);
        }
    }

    /** The DAO returns rows in no defined order, so a multi-entity result is compared as a set. */
    private static Set<String> asSet(List<String> names) {
        return new HashSet<>(names);
    }

    private WorkflowResult readAll(String mode) {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class)));
        if (mode != null) {
            request.arg("mode", mode);
        }
        return executeScript(userCtx, request);
    }

    @Test
    @DisplayName("a plain collection read (no ?mode=) runs afterGet on every entity")
    void collectionReadWithoutModeRunsAfterGet() {
        WorkflowResult result = readAll(null);

        assertTrue(result.isSuccess(), () -> "readAll must succeed; code=" + result.code());
        assertEquals(Set.of("Alice", "Bob"), asSet(SecretStripper.seen),
                "afterGet must fire on the collection route too — this is the route that used to skip it");
    }

    @Test
    @DisplayName("what the hook strips is actually absent from the collection result")
    void strippedFieldIsAbsentFromCollectionResult() {
        @SuppressWarnings("unchecked")
        List<Object> output = (List<Object>) readAll(null).output();

        assertEquals(2, output.size());
        for (Object o : output) {
            User user = assertInstanceOf(User.class, o,
                    "an unqualified read returns full entities, as mode=full does");
            assertEquals(null, user.getEmail(),
                    "the field the hook blanked must not reach the caller");
        }
    }

    @Test
    @DisplayName("the unit read runs it too — the two routes agree")
    void unitReadRunsAfterGet() {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.readOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", "uuid-alice");

        WorkflowResult result = executeScript(userCtx, request);

        assertTrue(result.isSuccess(), () -> "readOne must succeed; code=" + result.code());
        assertEquals(List.of("Alice"), SecretStripper.seen);
    }

    @Test
    @DisplayName("an explicit mode=full behaves exactly as before")
    void explicitFullIsUnchanged() {
        WorkflowResult result = readAll("full");

        assertTrue(result.isSuccess());
        assertEquals(Set.of("Alice", "Bob"), asSet(SecretStripper.seen));
    }

    @Test
    @DisplayName("a framework-INTERNAL read never runs it — that exemption is what makes the hook usable")
    void frameworkInternalReadSkipsTheHook() {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.readAllWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg(com.garganttua.api.core.expression.SecurityExpressions.FRAMEWORK_INTERNAL_WRITE_ARG,
                Boolean.TRUE);

        WorkflowResult result = executeScript(userCtx, request);

        assertTrue(result.isSuccess());
        assertEquals(List.of(), SecretStripper.seen,
                "the framework consulting its own store (a signing key, a principal) must not get the "
                        + "caller-facing shaping — a hook that strips the key would break signing");
        @SuppressWarnings("unchecked")
        List<Object> output = (List<Object>) result.output();
        for (Object o : output) {
            User user = assertInstanceOf(User.class, o);
            assertEquals(user.getName().toLowerCase(Locale.ROOT) + "@example.com", user.getEmail(),
                    "the internal reader still sees the complete entity");
        }
    }

    @Test
    @DisplayName("the reduced modes still skip it — they return scalars, not entities")
    void reducedModesStillSkipTheHook() {
        WorkflowResult uuids = readAll("uuid");

        assertTrue(uuids.isSuccess());
        @SuppressWarnings("unchecked")
        List<Object> output = (List<Object>) uuids.output();
        assertEquals(Set.of("uuid-alice", "uuid-bob"), new HashSet<>(output));
        assertEquals(List.of(), SecretStripper.seen,
                "mode=uuid never materialises an entity for a hook to act on");
    }
}
