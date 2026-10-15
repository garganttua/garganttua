package com.garganttua.api.core.expression;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unguarded merge the framework uses when it writes on its own behalf (startup upsert, token
 * persist, key rotation).
 *
 * <p>
 * Reported by a consumer: a {@code public static final} constant on a mapped entity made the first
 * framework write fail with {@code Cannot set final field … in AOT mode}. The merge walked every
 * declared field, a constant is never null, so it tried to write the constant onto itself — the
 * three other write paths of the framework (creation, document reading, mapping conventions)
 * already left static fields alone.
 * </p>
 */
@DisplayName("EntityUpdateSupport.mergeAllNonNullFields")
class EntityUpdateSupportTest {

    public static class Ledger {
        public static final String COLLECTION = "ledgers";
        private static int instances;

        private String name;
        private String email;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    private static Ledger ledger(String name, String email) {
        Ledger ledger = new Ledger();
        ledger.setName(name);
        ledger.setEmail(email);
        return ledger;
    }

    @Test
    @DisplayName("a static final constant on the entity does not stop the merge")
    void staticFinalConstantIsLeftAlone() {
        Ledger stored = ledger("old", "kept@palliad.care");

        Object merged = assertDoesNotThrow(
                () -> EntityUpdateSupport.mergeAllNonNullFields(stored, ledger("new", null)));

        assertSame(stored, merged);
        assertEquals("new", stored.getName());
        assertEquals("ledgers", Ledger.COLLECTION);
    }

    @Test
    @DisplayName("a non-final static field is not written either — it belongs to no instance")
    void mutableStaticIsNotWritten() {
        Ledger.instances = 7;

        EntityUpdateSupport.mergeAllNonNullFields(ledger("a", null), ledger("b", null));

        assertEquals(7, Ledger.instances);
    }

    @Test
    @DisplayName("null fields of the update still leave the stored value untouched")
    void nullStillMeansUntouched() {
        Ledger stored = ledger("old", "kept@palliad.care");

        EntityUpdateSupport.mergeAllNonNullFields(stored, ledger("new", null));

        assertEquals("kept@palliad.care", stored.getEmail());
    }
}
