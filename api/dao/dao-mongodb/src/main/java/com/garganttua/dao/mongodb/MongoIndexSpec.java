package com.garganttua.dao.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.bson.Document;

import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.mongodb.client.model.IndexOptions;

/**
 * One MongoDB index, as the DAO will ask the server for it: an {@link EntityIndexRule} once its
 * entity field name has been translated to the document field name and its scope has been turned
 * into the key order.
 *
 * <p>
 * It holds no {@code Document}: the key, weight and partial-filter documents are built on demand
 * from the declaration, so the spec is immutable and two specs built from the same declaration are
 * equal.
 * </p>
 *
 * @param name      the index name — the one the declaration named, or the stable derived one
 * @param keyFields the indexed document fields in key order; the tenant field comes first for a
 *                  tenant-scoped index, and the declared field is always <em>last</em>
 * @param kind      what the server must build, which decides the marker on the last key
 * @param unique    whether the server must refuse a duplicate
 */
// LooseCoupling: org.bson.Document (a Map subtype) is the MongoDB driver's own index-descriptor
// type, surfaced deliberately rather than behind a Map interface, exactly as MongoDao does.
@SuppressWarnings("PMD.LooseCoupling")
record MongoIndexSpec(String name, List<String> keyFields, IndexKind kind, boolean unique) {

    /**
     * Every BSON type but {@code null} (and the deprecated {@code undefined}), which is how
     * "present and not null" is written in a partial-index filter.
     *
     * <p>
     * The obvious spelling, <code>{$exists: true, $ne: null}</code>, is <strong>refused</strong> by
     * MongoDB: a partial-index filter admits only a closed set of operators, and {@code $ne} compiles
     * to {@code $not}, which is not one of them ({@code CannotCreateIndex}). Two other spellings look
     * right and are not — {@code $exists: true} alone still indexes an explicit null, so a
     * <em>second</em> null document is refused, which changes the contract; and <code>{$gt: null}</code>
     * is accepted but matches <em>nothing</em>, because a comparison against null is bracketed to the
     * null type, leaving an empty index that enforces nothing at all, silently. An explicit type list
     * is the spelling that behaves.
     * </p>
     */
    static final List<String> PRESENT_AND_NOT_NULL = List.of("double", "string", "object", "array",
            "binData", "objectId", "bool", "date", "regex", "dbPointer", "javascript", "symbol", "int",
            "timestamp", "long", "decimal", "minKey", "maxKey");

    /** The two synthetic keys the server substitutes for a {@code "text"} key. */
    private static final String FTS = "_fts";
    private static final String FTSX = "_ftsx";

    /** Defensive copy of the key order, so the spec cannot be mutated through the list it was given. */
    MongoIndexSpec {
        Objects.requireNonNull(name, "name");
        keyFields = List.copyOf(keyFields);
        kind = kind == null ? IndexKind.standard : kind;
    }

    /**
     * Turns what the entity declared into what MongoDB will be asked for.
     *
     * @param rules           the declared indexes, possibly empty or {@code null}
     * @param toDocumentField translates an entity field path to its document field path
     * @param tenantField     the document field holding the tenant identifier, or {@code null} when
     *                        the domain carries none — a tenant-scoped index then degrades to an
     *                        index on the field alone rather than being dropped
     * @return one spec per rule, in declaration order
     */
    static List<MongoIndexSpec> plan(List<EntityIndexRule> rules, UnaryOperator<String> toDocumentField,
            String tenantField) {
        List<MongoIndexSpec> specs = new ArrayList<>();
        for (EntityIndexRule rule : rules == null ? List.<EntityIndexRule>of() : rules) {
            if (rule != null) {
                specs.add(of(rule, toDocumentField, tenantField));
            }
        }
        return specs;
    }

    /** One rule, translated. Package-private so the translation can be asserted on its own. */
    static MongoIndexSpec of(EntityIndexRule rule, UnaryOperator<String> toDocumentField, String tenantField) {
        String field = toDocumentField.apply(rule.field().toString());
        boolean composed = rule.scope() == UnicityScope.tenant && tenantField != null
                && !tenantField.equals(field);
        return new MongoIndexSpec(rule.name(), composed ? List.of(tenantField, field) : List.of(field),
                rule.kind(), rule.unique());
    }

    /** {@return the declared field — the last key, the tenant prefix excluded} */
    String field() {
        return this.keyFields.get(this.keyFields.size() - 1);
    }

    /**
     * {@return the key document to create the index with}
     *
     * <p>
     * Prefix keys are always ascending; only the last key carries the kind marker, which is what
     * makes a tenant-scoped geo or text index a compound index the tenant filter can also use.
     * </p>
     */
    Document keys() {
        Document keys = new Document();
        for (int i = 0; i < this.keyFields.size(); i++) {
            boolean last = i == this.keyFields.size() - 1;
            keys.append(this.keyFields.get(i), last ? marker() : Integer.valueOf(1));
        }
        return keys;
    }

    private Object marker() {
        return switch (this.kind) {
            case geo -> "2dsphere";
            case text -> "text";
            case standard -> Integer.valueOf(1);
        };
    }

    /**
     * {@return the key document as {@code listIndexes} reports it back}
     *
     * <p>
     * Identical to {@link #keys()} except for a text index, where the server replaces the
     * {@code "text"} key by the synthetic pair {@code _fts}/{@code _ftsx} and moves the field name
     * into {@link #weights()}. Comparing a stored text index against {@link #keys()} would therefore
     * never match, and the DAO would try to create it again at every start.
     * </p>
     */
    Document storedKeys() {
        if (this.kind != IndexKind.text) {
            return keys();
        }
        Document keys = new Document();
        for (int i = 0; i < this.keyFields.size() - 1; i++) {
            keys.append(this.keyFields.get(i), Integer.valueOf(1));
        }
        return keys.append(FTS, "text").append(FTSX, Integer.valueOf(1));
    }

    /** {@return the per-field weights a text index carries, empty for any other kind} */
    Optional<Document> weights() {
        return this.kind == IndexKind.text
                ? Optional.of(new Document(field(), Integer.valueOf(1)))
                : Optional.empty();
    }

    /**
     * {@return the partial-index filter, present only for a unique index}
     *
     * <p>
     * It restricts the uniqueness to the documents where the field is present and not null, which is
     * exactly what the framework's own {@code validateUnicity} does — it returns early on a null
     * value. Without it the index would additionally refuse a <em>second</em> document with no value,
     * which no application relying on the framework check ever had to satisfy.
     * </p>
     */
    Optional<Document> partialFilter() {
        return this.unique
                ? Optional.of(new Document(field(), new Document("$type", PRESENT_AND_NOT_NULL)))
                : Optional.empty();
    }

    /** {@return the driver options for {@code createIndex}} */
    IndexOptions options() {
        IndexOptions options = new IndexOptions().name(this.name);
        if (this.unique) {
            options.unique(true);
            partialFilter().ifPresent(options::partialFilterExpression);
        }
        return options;
    }

    /**
     * Whether a stored index covers the same keys as this one — regardless of its name, uniqueness or
     * filter. That is the question MongoDB itself asks: it refuses a second index over the same keys
     * under a different name ({@code IndexOptionsConflict}).
     *
     * @param stored one entry of {@code listIndexes()}
     * @return {@code true} when the key shape is the same
     */
    boolean coversSameKeysAs(Document stored) {
        if (!storedKeys().equals(stored.get("key"))) {
            return false;
        }
        return weights().map(w -> w.equals(stored.get("weights"))).orElse(Boolean.TRUE);
    }

    /**
     * Whether a stored index is exactly the one this spec declares.
     *
     * @param stored one entry of {@code listIndexes()}
     * @return {@code true} when keys, uniqueness and partial filter all match — anything else is a
     *         difference the DAO reports and leaves alone
     */
    boolean matches(Document stored) {
        if (!coversSameKeysAs(stored) || this.unique != Boolean.TRUE.equals(stored.getBoolean("unique"))) {
            return false;
        }
        Object storedFilter = stored.get("partialFilterExpression");
        return partialFilter().map(f -> f.equals(storedFilter)).orElseGet(() -> storedFilter == null);
    }

    /** {@return a one-line description of this index, for a log line} */
    String describe() {
        return "keys=" + keys().toJson() + ", unique=" + this.unique
                + partialFilter().map(f -> ", partial=" + f.toJson()).orElse("");
    }

    /**
     * {@return a one-line description of a STORED index, in the same shape as {@link #describe()},
     * so a WARN can put the two side by side}
     *
     * @param stored one entry of {@code listIndexes()}
     */
    static String describe(Document stored) {
        Object filter = stored.get("partialFilterExpression");
        return "keys=" + String.valueOf(stored.get("key"))
                + ", unique=" + Boolean.TRUE.equals(stored.getBoolean("unique"))
                + (filter == null ? "" : ", partial=" + filter);
    }
}
