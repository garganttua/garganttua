package com.garganttua.dao.mongodb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

import org.bson.Document;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;

/**
 * Creates, at startup, the indexes a domain declares — and only those.
 *
 * <p>
 * It exists because a declared uniqueness was never reaching the database. The framework enforced it
 * by reading the collection and then writing, two calls with a gap between them that two concurrent
 * requests walk straight through. Only the store can hold a constraint, and a store holds it with a
 * unique index. This is the component that asks for one.
 * </p>
 *
 * <h2>Additive only</h2>
 * <p>
 * Nothing is ever dropped, renamed or re-created — not even an index whose stored definition differs
 * from the declared one. Dropping a live index takes a production query plan away without warning,
 * and re-creating it under load is a decision with a cost; both are a human's call. A difference is
 * a WARN naming both sides, and nothing else. The same goes for an index that already covers the
 * declared keys under another name: MongoDB refuses a second one anyway
 * ({@code IndexOptionsConflict}), so it is reported rather than attempted.
 * </p>
 *
 * <h2>Failure is not a reason to stay down</h2>
 * <p>
 * The case that decides whether this can be deployed at all: a collection that already holds
 * duplicates <em>refuses</em> a unique index. That is precisely the database this feature exists
 * for, and it is where the PostgreSQL side's choice does not transfer —
 * {@code PgSchemaManager} lets the failure out as an {@code IllegalStateException}, because a
 * missing table means no query can run at all. A missing index means every query still runs, just
 * without the constraint that was never held in the first place. So {@link MongoIndexMode#CREATE}
 * logs a WARN naming the domain, the field and an aggregation ready to paste that lists the
 * offending documents, and the application starts. Only {@link MongoIndexMode#STRICT}, asked for
 * explicitly, refuses to start.
 * </p>
 *
 * <h2>Concurrent instances</h2>
 * <p>
 * No lock, deliberately. PostgreSQL needs one because {@code CREATE TABLE IF NOT EXISTS} is not
 * atomic against a concurrent create and can fail on its own catalog. MongoDB's {@code createIndex}
 * has no such flaw: the server serialises index builds per collection and an identical request is a
 * no-op, so two instances starting together both end up with the one index.
 * </p>
 */
// LooseCoupling: org.bson.Document (a Map subtype) is the MongoDB driver's own index-descriptor
// type — listIndexes() returns it and createIndex() takes it — so it is surfaced deliberately
// rather than behind a Map interface, exactly as MongoDao does.
@SuppressWarnings("PMD.LooseCoupling")
public final class MongoIndexManager {

    private static final Logger log = Logger.getLogger(MongoIndexManager.class);

    /** MongoDB's duplicate-key error — the one failure that means "the data, not the request". */
    private static final int DUPLICATE_KEY = 11000;

    private final MongoIndexMode mode;

    /**
     * @param mode what the manager may do, and what happens on failure; {@code null} means the
     *             default, {@link MongoIndexMode#CREATE}
     */
    public MongoIndexManager(MongoIndexMode mode) {
        this.mode = mode == null ? MongoIndexMode.CREATE : mode;
    }

    /**
     * Makes the collection carry the declared indexes.
     *
     * <p>
     * A domain that declares none costs nothing: not one command is sent, so an application that
     * never asked for an index starts exactly as it did before this existed.
     * </p>
     *
     * @param collection the domain's collection
     * @param domainName the domain, named in every message so a log line points at something
     * @param specs      the declared indexes, already translated to document field names
     * @throws ApiException in {@link MongoIndexMode#STRICT} only, when an index cannot be created
     */
    public void ensure(MongoCollection<Document> collection, String domainName, List<MongoIndexSpec> specs)
            throws ApiException {
        Objects.requireNonNull(collection, "collection");
        if (this.mode == MongoIndexMode.NONE || specs == null || specs.isEmpty()) {
            return;
        }
        Optional<List<Document>> stored = read(collection, domainName);
        if (stored.isEmpty()) {
            return;
        }
        for (MongoIndexSpec spec : specs) {
            ensureOne(collection, domainName, spec, stored.get());
        }
    }

    /**
     * The indexes the collection already carries, or empty when they could not be read.
     *
     * <p>
     * A database that cannot be reached must not turn {@code registerDomain} — which did no I/O at
     * all before this existed — into the thing that throws. It goes through the same policy as any
     * other failure: a WARN in {@link MongoIndexMode#CREATE}, a refusal in
     * {@link MongoIndexMode#STRICT}.
     * </p>
     */
    private Optional<List<Document>> read(MongoCollection<Document> collection, String domainName)
            throws ApiException {
        try {
            List<Document> stored = new ArrayList<>();
            collection.listIndexes().into(stored);
            return Optional.of(stored);
        } catch (MongoException e) {
            fail("Domain '" + domainName + "': the indexes it declares could not be read from the "
                    + "database (" + e.getMessage() + "), so none of them was created.", e);
            return Optional.empty();
        }
    }

    private void ensureOne(MongoCollection<Document> collection, String domainName, MongoIndexSpec spec,
            List<Document> stored) throws ApiException {
        Optional<Document> sameName = stored.stream()
                .filter(index -> spec.name().equals(index.getString("name"))).findFirst();
        if (sameName.isPresent()) {
            reportExisting(domainName, spec, sameName.get());
            return;
        }
        Optional<Document> sameKeys = stored.stream().filter(spec::coversSameKeysAs).findFirst();
        if (sameKeys.isPresent()) {
            log.warn("Domain '{}': index '{}' ({}) is already covered by index '{}' ({}), left untouched — "
                    + "MongoDB holds one index per key shape. Drop the existing one deliberately if the "
                    + "declaration is the right one.", domainName, spec.name(), spec.describe(),
                    sameKeys.get().getString("name"), MongoIndexSpec.describe(sameKeys.get()));
            return;
        }
        create(collection, domainName, spec);
    }

    /** An index under the declared name already exists: keep it, and say so when it is not the one. */
    private static void reportExisting(String domainName, MongoIndexSpec spec, Document stored) {
        if (spec.matches(stored)) {
            log.debug("Domain '{}': index '{}' already in place ({})", domainName, spec.name(),
                    spec.describe());
            return;
        }
        log.warn("Domain '{}': index '{}' exists as [{}] but is declared as [{}] — left as is. This DAO "
                + "never alters an existing index: drop it deliberately if the declaration is the right "
                + "one.", domainName, spec.name(), MongoIndexSpec.describe(stored), spec.describe());
    }

    private void create(MongoCollection<Document> collection, String domainName, MongoIndexSpec spec)
            throws ApiException {
        try {
            collection.createIndex(spec.keys(), spec.options());
            log.info("Domain '{}': created index '{}' ({})", domainName, spec.name(), spec.describe());
        } catch (MongoException e) {
            String reason = e.getCode() == DUPLICATE_KEY
                    ? duplicateMessage(collection, domainName, spec)
                    : "Domain '" + domainName + "': index '" + spec.name() + "' on field '" + spec.field()
                            + "' (" + spec.describe() + ") could not be created: " + e.getMessage();
            fail(reason, e);
        }
    }

    /**
     * The message for the one failure an operator can act on: the collection already holds the
     * duplicates the declared index refuses. It carries the aggregation that lists them, so the
     * first thing the reader needs is in the log line rather than three pages away.
     */
    private static String duplicateMessage(MongoCollection<Document> collection, String domainName,
            MongoIndexSpec spec) {
        return "Domain '" + domainName + "': unique index '" + spec.name() + "' on field '" + spec.field()
                + "' could NOT be created — the collection '"
                + collection.getNamespace().getCollectionName() + "' already holds duplicate values for it. "
                + "The uniqueness is therefore NOT enforced by the database. List the offending documents "
                + "with: " + duplicateQuery(collection, spec) + " — then merge or delete them and restart.";
    }

    /**
     * A ready-to-paste aggregation listing the duplicate groups the index would refuse.
     *
     * <p>
     * It groups on every key of the index, so a tenant-scoped duplicate is listed per tenant. The
     * {@code $match} uses {@code $ne: null}, which an ordinary query accepts and which excludes both
     * null and absent — the partial-index filter cannot be written that way, but this is not one.
     * </p>
     */
    static String duplicateQuery(MongoCollection<Document> collection, MongoIndexSpec spec) {
        StringJoiner group = new StringJoiner(",", "{", "}");
        for (String key : spec.keyFields()) {
            group.add("\"" + key + "\":\"$" + key + "\"");
        }
        return "db.getCollection(\"" + collection.getNamespace().getCollectionName() + "\").aggregate(["
                + "{$match:{\"" + spec.field() + "\":{$ne:null}}},"
                + "{$group:{_id:" + group + ",count:{$sum:1},ids:{$push:\"$_id\"}}},"
                + "{$match:{count:{$gt:1}}}])";
    }

    /** WARN and carry on, or stop — the single point where the mode decides. */
    private void fail(String reason, MongoException cause) throws ApiException {
        if (this.mode == MongoIndexMode.STRICT) {
            throw new ApiException(reason + " (mongodb.index.auto=strict refuses to start on this.)", cause);
        }
        log.warn("{}", reason);
    }
}
