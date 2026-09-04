package com.garganttua.api.commons.entity;

import java.util.List;

import com.garganttua.core.reflection.ObjectAddress;

/**
 * What a write actually did to an entity: the entity itself, plus which of the fields the client
 * named were written and which were dropped.
 *
 * <p>
 * The framework has always known this — it is the framework that drops them, in the field whitelist
 * — but it told no one. A {@code PATCH} naming a field the caller may not write answers {@code 200}
 * with the field unchanged, which is the correct SECURITY outcome and a misleading one to observe:
 * a screen that tests the status code concludes the assignment succeeded and displays it as such.
 * Carrying the two lists out of the write is what lets the transport say so.
 * </p>
 *
 * @param entity   the written entity
 * @param applied  addresses of the fields actually written
 * @param rejected addresses of the fields the client named that were NOT written — whatever the
 *                 reason (a missing authority, a field the domain does not declare as updatable)
 */
public record EntityWriteOutcome(Object entity, List<ObjectAddress> applied, List<ObjectAddress> rejected) {

    public EntityWriteOutcome {
        applied = applied == null ? List.of() : List.copyOf(applied);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    /** {@return an outcome for a write that touched nothing and refused nothing} */
    public static EntityWriteOutcome unrestricted(Object entity) {
        return new EntityWriteOutcome(entity, List.of(), List.of());
    }
}
