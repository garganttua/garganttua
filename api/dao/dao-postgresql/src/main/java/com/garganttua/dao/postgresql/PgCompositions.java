package com.garganttua.dao.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.dao.postgresql.schema.PgChildTable;
import com.garganttua.dao.postgresql.schema.PgNaming;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Turns the uuids stored for {@code @Composed} fields into the referenced DTOs, one level deep.
 *
 * <p>
 * This is the relational counterpart of the MongoDB reader's DBRef resolution, with the same three
 * rules:
 * </p>
 * <ul>
 * <li><b>One level only.</b> The referenced rows are read by a reader that does NOT resolve their own
 * references — a graph of references (an order pointing at orders) could otherwise loop, or pull the
 * whole database into one response. A reference found one level down is left null, as MongoDB leaves
 * a nested DBRef.</li>
 * <li><b>Dangling reference.</b> A uuid with no row behind it reads as null for a single reference,
 * and is skipped in a collection — exactly what MongoDB does for a DBRef whose document is gone.</li>
 * <li><b>No reference.</b> A NULL uuid, or a reference collection whose presence bit is NULL, leaves
 * the field as the constructor left it — MongoDB omits the key of a null reference and skips it on
 * read.</li>
 * <li><b>Batched.</b> One query per referenced domain for the whole page ({@code id = ANY(?)}), not one
 * per row and field.</li>
 * </ul>
 *
 * <p>
 * A field declared as {@code String} (or {@code String} elements) holds the uuid itself: there is
 * nothing to resolve, and the uuid is set as is, at any level.
 * </p>
 *
 * <p>
 * A target domain that is not in the {@link PgSchemaRegistry} (stored elsewhere, or not yet
 * registered) cannot be resolved: its fields stay null and ONE warning per domain says why, rather
 * than one per row flooding the log.
 * </p>
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // accessor style: a constant/field and the method using it share a name
final class PgCompositions {

    private static final Logger LOG = Logger.getLogger(PgCompositions.class);

    private final PgTable table;
    private final IClass<?> dtoClass;
    private final PgSchemaRegistry registry;
    private final PgBeans beans;
    private final boolean resolve;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    PgCompositions(PgTable table, IClass<?> dtoClass, PgSchemaRegistry registry, PgBeans beans, boolean resolve) {
        this.table = table;
        this.dtoClass = dtoClass;
        this.registry = registry;
        this.beans = beans;
        this.resolve = resolve;
    }

    /**
     * Whether a composition collection's child table is worth reading: always when its uuids are the
     * value (String elements), and otherwise only when this reader resolves references.
     *
     * @param child a {@code COMPOSITION_COLLECTION} child table
     * @return whether to load it
     */
    boolean needsRows(PgChildTable child) {
        return resolve || child.elementType() == null || child.elementType().represents(String.class);
    }

    /**
     * Sets every composition field of the page.
     *
     * @param connection the connection
     * @param rows       the page, references collected
     * @throws ApiException when a referenced table cannot be read
     */
    void resolve(Connection connection, List<PgLoadedRow> rows) throws ApiException {
        Map<String, Map<String, Object>> resolved = fetch(connection, rows);
        for (Map.Entry<String, String> composition : table.compositions().entrySet()) {
            String path = composition.getKey();
            Map<String, Object> targets = resolved.get(composition.getValue());
            for (PgLoadedRow row : rows) {
                if (row.references().containsKey(path)) {
                    setSingle(row, path, targets);
                } else if (row.referenceLists().containsKey(path)) {
                    setCollection(row, path, targets);
                }
            }
        }
    }

    private void setSingle(PgLoadedRow row, String path, Map<String, Object> targets) throws ApiException {
        String uuid = row.references().get(path);
        if (uuid == null) {
            // No reference was saved: MongoDB omits the key and leaves the field as constructed.
            return;
        }
        IField field = beans.field(dtoClass, path);
        if (holdsUuid(field.getType())) {
            beans.set(row.instance(), List.of(path), uuid, false);
        } else if (targets != null) {
            beans.set(row.instance(), List.of(path), targets.get(uuid), false);
        }
    }

    private void setCollection(PgLoadedRow row, String path, Map<String, Object> targets) throws ApiException {
        PgChildTable child = table.child(path).orElseThrow(() -> new ApiException(
                "Composition collection '" + path + "' of table '" + table.name() + "' has no child table"));
        List<String> uuids = row.referenceLists().get(path);
        List<Object> elements = new ArrayList<>(uuids.size());
        if (holdsUuid(child.elementType())) {
            elements.addAll(uuids);
        } else if (targets != null) {
            uuids.stream().map(targets::get).filter(dto -> dto != null).forEach(elements::add);
        } else {
            return;
        }
        Object built = PgCollections.collection(child.collectionType(), child.elementType(), elements);
        beans.set(row.instance(), child.fieldPath(), built, false);
    }

    private static boolean holdsUuid(IClass<?> type) {
        return type == null || type.represents(String.class);
    }

    /**
     * Reads every referenced row of the page, one query per target domain.
     *
     * @return target domain to (uuid to DTO); a domain that is absent could not or need not be resolved
     */
    private Map<String, Map<String, Object>> fetch(Connection connection, List<PgLoadedRow> rows)
            throws ApiException {
        Map<String, Map<String, Object>> resolved = new HashMap<>();
        if (!resolve) {
            return resolved;
        }
        for (Map.Entry<String, Set<String>> wanted : wanted(rows).entrySet()) {
            Optional<PgSchemaRegistry.Entry> target = registry.lookup(wanted.getKey());
            if (target.isEmpty()) {
                warnUnregistered(wanted.getKey());
                continue;
            }
            resolved.put(wanted.getKey(), read(connection, target.get(), wanted.getValue()));
        }
        return resolved;
    }

    /** The uuids to read, by target domain — only for fields that hold DTOs, not bare uuids. */
    private Map<String, Set<String>> wanted(List<PgLoadedRow> rows) throws ApiException {
        Map<String, Set<String>> wanted = new HashMap<>();
        for (Map.Entry<String, String> composition : table.compositions().entrySet()) {
            String path = composition.getKey();
            Set<String> uuids = wanted.computeIfAbsent(composition.getValue(), d -> new LinkedHashSet<>());
            boolean collection = table.child(path).isPresent();
            IClass<?> held = collection ? table.child(path).get().elementType() : beans.field(dtoClass, path).getType();
            if (holdsUuid(held)) {
                continue;
            }
            for (PgLoadedRow row : rows) {
                if (collection) {
                    uuids.addAll(row.referenceLists().getOrDefault(path, List.of()));
                } else if (row.references().get(path) != null) {
                    uuids.add(row.references().get(path));
                }
            }
        }
        wanted.values().removeIf(Set::isEmpty);
        return wanted;
    }

    private Map<String, Object> read(Connection connection, PgSchemaRegistry.Entry target, Set<String> uuids)
            throws ApiException {
        PgTable targetTable = target.table();
        String where = PgQuery.ALIAS + "." + PgNaming.quote(targetTable.id().name()) + " = ANY(?)";
        Object ids;
        try {
            ids = connection.createArrayOf("text", uuids.toArray());
        } catch (SQLException e) {
            throw new ApiException("Cannot bind the referenced uuids of table '" + targetTable.name() + "': "
                    + e.getMessage(), e);
        }
        PgReader reader = new PgReader(targetTable, target.dtoClass(), registry, false);
        Map<String, Object> byUuid = new HashMap<>();
        for (Object dto : reader.find(connection, new PgQuery(where, List.of(ids), "", null, null, null))) {
            Optional<Object> uuid = beans.get(dto, targetTable.id().fieldPath());
            uuid.ifPresent(u -> byUuid.put(u.toString(), dto));
        }
        return byUuid;
    }

    private void warnUnregistered(String domain) {
        if (warned.add(domain)) {
            LOG.warn("Table '{}' references domain '{}', which no PostgreSQL DAO of this database registered: "
                    + "its @Composed fields are left null. Register the target domain in the same "
                    + "PgSchemaRegistry to resolve them.", table.name(), domain);
        }
    }
}
