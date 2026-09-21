package com.garganttua.dao.postgresql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One main-table row being turned into a DTO, across the several passes a page read makes.
 *
 * <p>
 * A page is read in passes — main rows, then one query per child table, then one query per
 * referenced domain — so a row has to carry what the later passes need: its id (the child rows'
 * {@code _owner}) and the reference uuids that cannot be set on the DTO until their targets are read.
 * </p>
 *
 * @param id         the row's primary key, as stored
 * @param instance   the DTO being filled
 * @param references single {@code @Composed} fields: dotted path to the stored uuid (null when NULL)
 * @param referenceLists collection {@code @Composed} fields: dotted path to the stored uuids, in order
 */
record PgLoadedRow(String id, Object instance, Map<String, String> references,
        Map<String, List<String>> referenceLists) {

    PgLoadedRow(String id, Object instance) {
        this(id, instance, new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
