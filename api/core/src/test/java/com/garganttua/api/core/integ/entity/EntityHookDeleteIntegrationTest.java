package com.garganttua.api.core.integ.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
 * Covers the delete lifecycle hooks, reported by a consumer as never firing: a purge written on
 * {@code beforeDelete} / {@code afterDelete} was accepted by the DSL, built without complaint, and
 * never called — leaving dead references behind every deletion, with nothing logged.
 *
 * <p>
 * Two distinct defects sat on that path, and both are exercised here:
 * </p>
 * <ul>
 *   <li>{@code runListLifecycleHooks} (the delete route) never ran FREE hooks at all, unlike its
 *       create/update counterpart;</li>
 *   <li>ENTITY-BOUND hooks were addressed through {@code getExecutableReference()}, an
 *       ANSI-colored label meant for logs, which resolves to no method.</li>
 * </ul>
 */
@DisplayName("Delete lifecycle hooks (beforeDelete / afterDelete)")
class EntityHookDeleteIntegrationTest extends AbstractCrudScriptTest {

    /** An external purge — the shape the consumer reported: it cleans up what cited the entity. */
    public static final class ReferencePurge {
        static final List<String> purgedBefore = new ArrayList<>();
        static final List<String> purgedAfter = new ArrayList<>();

        public static void beforeDelete(User user) {
            purgedBefore.add(user == null ? "<null>" : user.getName());
        }

        public static void afterDelete(User user) {
            purgedAfter.add(user == null ? "<null>" : user.getName());
        }
    }

    private CapturingDao userDao;

    @BeforeEach
    void reset() {
        ReferencePurge.purgedBefore.clear();
        ReferencePurge.purgedAfter.clear();
    }

    private static IMethod purge(String name) throws Exception {
        return IClass.getClass(ReferencePurge.class).getMethod(name, IClass.getClass(User.class));
    }

    /** Users domain carrying free before/after delete hooks, plus an entity-bound afterDelete. */
    @SuppressWarnings("unchecked")
    private IDomain<?> userDomainWithDeleteHooks(boolean entityBound) throws Exception {
        userDao = new CapturingDao();
        IApiBuilder builder = newBuilder();
        IEntityBuilder<User> entity = (IEntityBuilder<User>) (IEntityBuilder<?>) builder
                .domain(IClass.getClass(User.class))
                    .tenant(true)
                    .superTenant("superTenant")
                    .entity()
                        .id("id").uuid("uuid").tenantId("tenantId");
        entity.beforeDelete(purge("beforeDelete"));
        entity.afterDelete(purge("afterDelete"));
        if (entityBound) {
            entity.afterDelete("touch");
        }
        entity.up()
                .dto(IClass.getClass(UserDto.class))
                    .id("id").uuid("uuid").tenantId("tenantId")
                    .db(userDao)
                .up()
                .security().disable(true).up()
            .up();
        IApi context = buildAndStart(builder);
        return context.getDomain("users").orElseThrow();
    }

    private void seed(String... names) {
        int i = 1;
        for (String name : names) {
            UserDto dto = new UserDto();
            dto.setId(String.valueOf(i++));
            dto.setUuid("uuid-" + name.toLowerCase(java.util.Locale.ROOT));
            dto.setTenantId("SUPER_TENANT");
            dto.setName(name);
            userDao.getStorage().add(dto);
        }
    }

    private WorkflowResult deleteOne(IDomain<?> userCtx, String uuid) {
        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.deleteOneWithStandardSecurity("users", IClass.getClass(User.class)));
        request.arg("type", "uuid");
        request.arg("identifier", uuid);
        return executeScript(userCtx, request);
    }

    @Test
    @DisplayName("a free beforeDelete/afterDelete hook runs on DELETE, on the entity being deleted")
    void freeDeleteHooksRun() throws Exception {
        IDomain<?> userCtx = userDomainWithDeleteHooks(false);
        seed("Alice", "Bob");

        WorkflowResult result = deleteOne(userCtx, "uuid-alice");

        assertTrue(result.isSuccess(), () -> "delete must succeed; code=" + result.code());
        assertEquals(List.of("Alice"), ReferencePurge.purgedBefore,
                "beforeDelete must fire — this is the purge the consumer wrote and never saw run");
        assertEquals(List.of("Alice"), ReferencePurge.purgedAfter, "afterDelete must fire too");
        assertEquals(1, userDao.getStorage().size(), "and the entity is still actually deleted");
    }

    @Test
    @DisplayName("an entity-bound afterDelete hook is invoked by NAME, not by its ANSI display label")
    void entityBoundDeleteHookRuns() throws Exception {
        IDomain<?> userCtx = userDomainWithDeleteHooks(true);
        seed("Carol");

        WorkflowResult result = deleteOne(userCtx, "uuid-carol");

        assertTrue(result.isSuccess(), () -> "delete must succeed; code=" + result.code());
        User deleted = assertInstanceOf(User.class, result.output());
        assertEquals(1, deleted.getHookCalls(),
                "the entity's own afterDelete method must have been invoked exactly once");
    }

    @Test
    @DisplayName("the free hook receives the very entity the pipeline deleted")
    void hookSeesThePipelineEntity() throws Exception {
        IDomain<?> userCtx = userDomainWithDeleteHooks(false);
        seed("Dave");

        WorkflowResult result = deleteOne(userCtx, "uuid-dave");

        assertTrue(result.isSuccess());
        User deleted = assertInstanceOf(User.class, result.output());
        assertEquals(List.of(deleted.getName()), ReferencePurge.purgedAfter);
        assertEquals("Dave", deleted.getName());
    }

    @Test
    @DisplayName("deleteAll runs the hooks once per deleted entity")
    void deleteAllRunsHooksPerEntity() throws Exception {
        IDomain<?> userCtx = userDomainWithDeleteHooks(false);
        seed("Eve", "Frank");

        OperationRequest request = superTenantScriptRequest(
                OperationDefinition.deleteAllWithStandardSecurity("users", IClass.getClass(User.class)));
        WorkflowResult result = executeScript(userCtx, request);

        assertTrue(result.isSuccess(), () -> "deleteAll must succeed; code=" + result.code());
        // The DAO returns rows in no defined order — what matters is that each entity was seen once.
        assertEquals(Set.of("Eve", "Frank"), new java.util.HashSet<>(ReferencePurge.purgedBefore));
        assertEquals(2, ReferencePurge.purgedBefore.size(), "once per deleted entity, not more");
        assertEquals(Set.of("Eve", "Frank"), new java.util.HashSet<>(ReferencePurge.purgedAfter));
    }
}
