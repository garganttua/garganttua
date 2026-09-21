package com.garganttua.dao.postgresql.parity;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Known, documented divergences between the MongoDB and PostgreSQL DAOs — the parity checks the
 * nested suites still run but no longer fail on, because fixing them was deliberately left out of
 * scope. Each one is PINNED: while the engines disagree the check prints {@code [KNOWN]} and passes;
 * the day they agree the check FAILS, so a residual that disappears is turned back into a plain parity
 * test instead of staying silently excused.
 */
final class ParityResiduals {

    /** An intermediate POJO inside a child element has no presence bit: null and absent read the same. */
    static final String PRESENCE_IN_ELEMENT = "intermediate POJO presence inside a child element "
            + "(no presence bit: a null object and an absent one read back the same)";

    /** A null element inside a nested list is stored as an element whose fields are all null. */
    static final String NULL_ELEMENT = "null element inside a nested list "
            + "(read as an element whose fields are null, so it matches $eq null)";

    /** A recursive type is JSONB; sorting into JSONB is refused since it does not reproduce BSON order. */
    static final String JSONB_SORT = "sort into a recursive type stored as JSONB "
            + "(refused by design: JSONB ordering is not BSON ordering)";

    /** A subclass-only field is not part of the declared type's schema. */
    static final String POLYMORPHISM = "polymorphism (a subclass-only field is not in the declared type's schema)";

    /** A generic field ({@code Wrapper<T>}) is stored as JSONB, so a sort into it is refused. */
    static final String GENERICS = "generics resolution (Wrapper<T> is stored as JSONB, sort refused)";

    /** {@code home__city} collides with the flattened {@code home.city}. */
    static final String NAME_COLLISION = "'__' name escaping (home__city collides with the flattened home.city)";

    private ParityResiduals() {
    }

    /** Runs a parity check expected to diverge for {@code reason}; fails if it now agrees. */
    static void pinned(String reason, Runnable parityCheck) {
        try {
            parityCheck.run();
        } catch (AssertionError expected) {
            System.out.println("[KNOWN] " + reason + " — " + expected.getMessage());
            return;
        }
        fail("residual gone (" + reason + "): the engines now agree — turn it back into a parity test");
    }
}
