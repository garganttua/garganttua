package com.garganttua.core.condition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two-value {@code and} / {@code or} of scripts and stage guards: the same truthiness for both. */
@DisplayName("two-value and / or")
class TwoValueLogicTest {

    @Test
    @DisplayName("or is true as soon as one operand is truthy")
    void or() {
        assertTrue(OrCondition.or(true, null));
        assertTrue(OrCondition.or(null, "x"));
        assertTrue(OrCondition.or(false, 1));
        assertFalse(OrCondition.or(null, null));
        assertFalse(OrCondition.or(false, "false"));
        assertFalse(OrCondition.or(0, ""));
    }

    @Test
    @DisplayName("and needs both operands truthy")
    void and() {
        assertTrue(AndCondition.and(true, "x"));
        assertFalse(AndCondition.and(true, null));
        assertFalse(AndCondition.and(0, true));
    }
}
