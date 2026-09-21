package com.garganttua.dao.postgresql;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.reflection.IClass;
import com.garganttua.dao.postgresql.schema.PgTable;

/**
 * The relational shape of every domain served by one database, so a DAO can follow a reference.
 *
 * <p>
 * A {@code @Composed} field stores the referenced entity's uuid, and reading it back one level deep
 * means querying ANOTHER domain's table — whose shape only that domain's DAO knows. Every DAO built
 * against the same database registers its table here; the one reading a reference looks the target
 * up. A target that is not registered (not stored in this database) cannot be resolved, and the
 * reader says so rather than inventing a shape for it.
 * </p>
 */
public final class PgSchemaRegistry {

    /**
     * One domain's shape and DTO class.
     *
     * @param table    the relational shape
     * @param dtoClass the DTO the rows map to
     */
    public record Entry(PgTable table, IClass<?> dtoClass) {
    }

    private final Map<String, Entry> byDomain = new ConcurrentHashMap<>();

    /**
     * Records a domain's shape. A later registration of the same domain replaces the earlier one.
     *
     * @param domainName the domain name — what {@code DtoComposition.collection()} names
     * @param table      its relational shape
     * @param dtoClass   its DTO class
     */
    public void register(String domainName, PgTable table, IClass<?> dtoClass) {
        byDomain.put(domainName, new Entry(table, dtoClass));
    }

    /**
     * The shape of a domain.
     *
     * @param domainName the domain name
     * @return its entry, or empty when no DAO of this database registered it
     */
    public Optional<Entry> lookup(String domainName) {
        return Optional.ofNullable(byDomain.get(domainName));
    }
}
