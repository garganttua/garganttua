package com.garganttua.dao.postgresql.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * The parity harness itself: it really reaches both engines, and it really sees a disagreement.
 */
@DisplayName("The parity harness")
class ParityHarnessSmokeTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Item {
        String uuid;
        String name;
        Integer age;
        List<String> tags;
    }

    private static Item item(String uuid, String name, Integer age, String... tags) {
        Item i = new Item();
        i.uuid = uuid;
        i.name = name;
        i.age = age;
        i.tags = new ArrayList<>(List.of(tags));
        return i;
    }

    @Test
    @DisplayName("reaches both engines and finds them agreeing on a plain filter")
    void bothEnginesAnswer() {
        ParityHarness h = ParityHarness.of(Domain.of("items", Item.class));
        h.save("items", item("i1", "a", 18, "x"), item("i2", "b", 30, "y", "x"), item("i3", "a", null));

        Outcome all = h.count("items", null);
        assertEquals(3L, all.mongo(), "MongoDB must really hold the three items");
        assertEquals(3L, all.pg(), "PostgreSQL must really hold the three items");

        ParityHarness.assertSame("name = a", h.find("items", ParityFilter.field("name", "$eq", "a")), false);
        ParityHarness.assertSame("tags contains x", h.find("items", ParityFilter.field("tags", "$eq", "x")), false);
    }

    @Test
    @DisplayName("sees a disagreement when there is one")
    void detectsDivergence() {
        Outcome different = new Outcome(List.of("a"), List.of("b"), null, null);
        assertFalse(ParityHarness.same(different, false));
        Outcome oneFails = new Outcome(List.of(), null, null, new IllegalStateException("x"));
        assertFalse(ParityHarness.same(oneFails, false));
    }
}
