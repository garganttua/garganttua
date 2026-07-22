package com.garganttua.core.reflection;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.garganttua.core.reflection.constructors.ConstructorAccessManager;
import com.garganttua.core.reflection.fields.FieldAccessManager;
import com.garganttua.core.reflection.methods.MethodAccessManager;
import com.garganttua.core.reflection.runtime.RuntimeClass;

/**
 * Behaviour tests for the scoped accessibility guards: {@link FieldAccessManager},
 * {@link MethodAccessManager} and {@link ConstructorAccessManager}.
 *
 * <p>Each guard makes the member accessible inside a try-with-resources block. Because the
 * underlying {@link IField} / {@link IMethod} / {@link IConstructor} handles are memoized and
 * shared process-wide, {@code close()} deliberately leaves accessibility open rather than
 * restoring it — restoring would tear the flag down under concurrent consumers of the same
 * handle. These tests pin that contract.
 */
public class AccessManagerBehaviourTest {

    public static class Target {
        private String secret = "hidden";

        public Target() {
        }

        private Target(int ignored) {
        }

        private String reveal() {
            return secret;
        }
    }

    // ===== FieldAccessManager =====

    @Test
    public void field_makesPrivateFieldAccessibleAndLeavesItOpen() throws Exception {
        IField field = RuntimeClass.of(Target.class).getDeclaredField("secret");
        Target target = new Target();

        try (FieldAccessManager mgr = new FieldAccessManager(field)) {
            assertTrue(field.canAccess(target), "field should be accessible within the guard");
        }
        assertTrue(field.canAccess(target),
                "accessibility must stay open after close: the handle is shared process-wide");
    }

    @Test
    public void field_forceConstructorAlsoGrantsAccess() throws Exception {
        IField field = RuntimeClass.of(Target.class).getDeclaredField("secret");
        Target target = new Target();
        try (FieldAccessManager mgr = new FieldAccessManager(field, true)) {
            assertTrue(field.canAccess(target));
        }
    }

    // ===== MethodAccessManager =====

    @Test
    public void method_makesPrivateMethodAccessibleAndLeavesItOpen() throws Exception {
        IMethod method = RuntimeClass.of(Target.class).getDeclaredMethod("reveal");
        Target target = new Target();

        try (MethodAccessManager mgr = new MethodAccessManager(method)) {
            assertTrue(method.canAccess(target));
        }
        assertTrue(method.canAccess(target),
                "accessibility must stay open after close: the handle is shared process-wide");
    }

    // ===== ConstructorAccessManager =====

    @Test
    public void constructor_makesPrivateCtorAccessibleAndLeavesItOpen() throws Exception {
        IConstructor<Target> ctor = RuntimeClass.of(Target.class).getDeclaredConstructor(RuntimeClass.of(int.class));

        try (ConstructorAccessManager mgr = new ConstructorAccessManager(ctor)) {
            assertTrue(ctor.canAccess(null), "static-receiver canAccess(null) is true once accessible");
        }
        assertTrue(ctor.canAccess(null),
                "accessibility must stay open after close: the handle is shared process-wide");
    }

    @Test
    public void publicConstructorStaysAccessibleAfterClose() throws Exception {
        IConstructor<Target> ctor = RuntimeClass.of(Target.class).getDeclaredConstructor();
        try (ConstructorAccessManager mgr = new ConstructorAccessManager(ctor)) {
            assertTrue(ctor.canAccess(null));
        }
        // public ctor on public class: original accessibility was true, remains accessible
        assertTrue(ctor.canAccess(null));
    }

    // ===== Shared-handle regression =====

    /**
     * Regression: {@code RuntimeField} memoizes its mirrors in a static map keyed by the JDK
     * field, and {@link Class#getDeclaredFields()} returns value-equal copies — so two independent
     * lookups hand back the <em>same</em> handle. A guard that restored accessibility on close
     * would therefore revoke access for an unrelated consumer that had already opened the field
     * and not yet written to it, which is what produced random {@code IllegalAccessException}s
     * under concurrent CRUD traffic.
     */
    @Test
    public void closingAGuardDoesNotRevokeAccessForAnotherConsumerOfTheSameHandle() throws Exception {
        IField a = RuntimeClass.of(Target.class).getDeclaredField("secret");
        IField b = RuntimeClass.of(Target.class).getDeclaredField("secret");
        assertSame(a, b, "handles are memoized and shared — this is the premise of the bug");

        Target target = new Target();

        // Consumer 1 opens the field and holds it open (MongoDocumentReader/Writer style).
        a.setAccessible(true);
        a.set(target, "written before");

        // Consumer 2 runs a full guarded access on the same handle (FieldAccessor style).
        try (FieldAccessManager mgr = new FieldAccessManager(b)) {
            b.get(target);
        }

        // Consumer 1 must still be able to write: close() must not have revoked its access.
        a.set(target, "written after guard close");
        assertTrue(a.canAccess(target));
    }
}
