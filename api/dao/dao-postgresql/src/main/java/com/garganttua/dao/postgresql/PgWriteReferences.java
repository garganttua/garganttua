package com.garganttua.dao.postgresql;

import java.util.List;
import java.util.UUID;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * Turns a {@code @Composed} value into the uuid the row stores — the relational counterpart of the
 * MongoDB DAO writing a {@code DBRef}.
 *
 * <p>
 * The referenced entity belongs to ANOTHER domain, so its uuid is read with THAT domain's id field,
 * found in the {@link PgSchemaRegistry}. When the target is not registered (it is not stored in
 * this database), the writer falls back to the OWNING domain's uuid field name — exactly what the
 * MongoDB DAO does in every case, since it reads a composed DTO's uuid with its own uuid field name.
 * Nothing about the target is invented: if the composed object has no such field, the write fails.
 * </p>
 *
 * <p>
 * A composed value that is already a {@code String} or {@code UUID} is taken as the uuid itself:
 * a DTO may hold the reference rather than the referenced object, and there is nothing to read
 * from a string.
 * </p>
 */
final class PgWriteReferences {

    private static final Logger LOG = Logger.getLogger(PgWriteReferences.class);

    private final PgTable table;
    private final PgSchemaRegistry registry;

    PgWriteReferences(PgTable table, PgSchemaRegistry registry) {
        this.table = table;
        this.registry = registry;
    }

    /**
     * The uuid of a referenced entity.
     *
     * @param targetDomain the domain the reference points to, possibly null when unknown
     * @param referenced   the composed value, possibly null
     * @return the uuid, or null when there is no reference
     * @throws ApiException when the composed object has no uuid field, or its uuid is null
     */
    Object uuidOf(String targetDomain, Object referenced) throws ApiException {
        if (referenced == null) {
            return null;
        }
        if (referenced instanceof CharSequence || referenced instanceof UUID) {
            return referenced.toString();
        }
        List<String> idPath = idPathOf(targetDomain);
        Object uuid;
        try {
            uuid = PgWriteSupport.valueAt(referenced, idPath);
        } catch (ApiException e) {
            throw new ApiException("Composed " + referenced.getClass().getName() + " (domain '" + targetDomain
                    + "', referenced from table '" + table.name() + "') has no uuid field '"
                    + String.join(".", idPath) + "' to reference", e);
        }
        if (uuid == null) {
            // MongoDB would store a DBRef with a null $id, which resolves to nothing: the reference
            // would be lost in silence. A relational NULL would lose it the same way — refuse instead.
            throw new ApiException("Composed " + referenced.getClass().getName() + " (domain '" + targetDomain
                    + "', referenced from table '" + table.name() + "') has a null uuid — save it before "
                    + "referencing it");
        }
        return uuid;
    }

    private List<String> idPathOf(String targetDomain) {
        if (targetDomain != null) {
            var entry = registry.lookup(targetDomain);
            if (entry.isPresent()) {
                return entry.get().table().id().fieldPath();
            }
        }
        LOG.debug("Domain '{}' referenced from table '{}' is not registered; reading its uuid with field '{}'",
                targetDomain, table.name(), table.id().dottedPath());
        return table.id().fieldPath();
    }
}
