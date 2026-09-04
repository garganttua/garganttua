package com.garganttua.api.commons.service;

import java.util.List;

/**
 * Which of the fields a write request named were applied, and which were dropped — in the
 * <strong>client's</strong> vocabulary (the DTO field names), not the entity's.
 *
 * <p>
 * The names matter: the caller sent DTO field names, and a {@code @FieldMappingRule} may rename
 * them on the way to the entity. Reporting entity names would hand the client back words it never
 * used and leave it to work out the correspondence.
 * </p>
 *
 * <p>
 * Both lists are always present on a write response, empty rather than absent when nothing was
 * rejected: an absent header would be ambiguous between "nothing rejected" and "an older
 * framework", and no client could then rely on it.
 * </p>
 *
 * @param applied  DTO field names actually written
 * @param rejected DTO field names named by the client and not written
 */
public record WrittenFields(List<String> applied, List<String> rejected) {

    private static final WrittenFields NONE = new WrittenFields(List.of(), List.of());

    public WrittenFields {
        applied = applied == null ? List.of() : List.copyOf(applied);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    /** {@return the empty report} — used by every operation that is not a write. */
    public static WrittenFields none() {
        return NONE;
    }

    /** {@return whether this report says nothing at all} */
    public boolean isEmpty() {
        return applied.isEmpty() && rejected.isEmpty();
    }
}
