package com.garganttua.dao.postgresql.parity;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.postgresql.PgDao;
import com.garganttua.dao.postgresql.PgJson;
import com.garganttua.dao.postgresql.PgSchemaRegistry;
import com.garganttua.dao.postgresql.PgTestDatabase;
import com.garganttua.dao.postgresql.TestDomains;
import com.garganttua.dao.postgresql.schema.SchemaMode;
import com.mongodb.client.MongoDatabase;

/**
 * Runs one operation against a real MongoDB AND a real PostgreSQL, and demands the same answer.
 *
 * <p>
 * "Iso-functional with MongoDB" is a claim about observable behaviour, so it is checked the only way
 * such a claim can be: same DTOs, same data, same filter, both engines, compare. Each domain gets a
 * {@link MongoDao} on a fresh MongoDB database and a {@link PgDao} on a fresh PostgreSQL database;
 * every write goes to both; every read is asked of both.
 * </p>
 *
 * <p>
 * Results are compared in a canonical JSON form (fields, not getters; map entries by key), so a
 * difference is a difference in what a caller of the api would receive. An operation that fails on
 * one engine and succeeds on the other is a divergence too; failing on both is agreement — the error
 * messages themselves are not compared.
 * </p>
 */
public final class ParityHarness {

    /** One domain of the scenario: its name (= Mongo collection = PostgreSQL table), DTO, compositions. */
    public record Domain(String name, Class<?> dto, Map<String, String> compositions) {

        /** A domain without compositions. */
        public static Domain of(String name, Class<?> dto) {
            return new Domain(name, dto, Map.of());
        }
    }

    /**
     * What each engine answered.
     *
     * @param mongo      MongoDB's result, or null when it threw
     * @param pg         PostgreSQL's result, or null when it threw
     * @param mongoError what MongoDB threw
     * @param pgError    what PostgreSQL threw
     */
    public record Outcome(Object mongo, Object pg, Throwable mongoError, Throwable pgError) {
    }

    private static final ObjectMapper CANONICAL = PgJson.MAPPER.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final Map<String, MongoDao> mongo = new LinkedHashMap<>();
    private final Map<String, PgDao> pg = new LinkedHashMap<>();

    private ParityHarness() {
    }

    /**
     * Both DAOs for every domain, on fresh databases. Skips the test when MongoDB is unavailable.
     *
     * @param domains the domains of the scenario
     * @return the harness
     */
    public static ParityHarness of(Domain... domains) {
        MongoDatabase mongoDb = MongoTestServer.freshDatabase();
        DataSource pgDb = PgTestDatabase.freshDatabase();
        PgSchemaRegistry registry = new PgSchemaRegistry();
        ParityHarness harness = new ParityHarness();
        for (Domain d : domains) {
            MongoDao m = new MongoDao(mongoDb, d.name());
            m.registerDomain(TestDomains.definition(d.dto(), d.compositions()));
            PgDao p = new PgDao(pgDb, d.name(), registry, SchemaMode.CREATE);
            p.registerDomain(TestDomains.definition(d.dto(), d.compositions()));
            harness.mongo.put(d.name(), m);
            harness.pg.put(d.name(), p);
        }
        return harness;
    }

    /** Writes entities to both engines; a write accepted by one and refused by the other fails the test. */
    public void save(String domain, Object... entities) {
        for (Object entity : entities) {
            Outcome o = run(() -> mongo.get(domain).save(entity), () -> pg.get(domain).save(entity));
            if ((o.mongoError() == null) != (o.pgError() == null)) {
                fail("save diverges on " + domain + ": MongoDB " + describe(o.mongo(), o.mongoError())
                        + " / PostgreSQL " + describe(o.pg(), o.pgError()));
            }
        }
    }

    /** Asks both engines to delete. */
    public Outcome delete(String domain, Object entity) {
        return run(() -> {
            mongo.get(domain).delete(entity);
            return "deleted";
        }, () -> {
            pg.get(domain).delete(entity);
            return "deleted";
        });
    }

    /** Asks both engines the same find. */
    public Outcome find(String domain, Optional<IPageable> page, Optional<IFilter> filter, Optional<ISort> sort,
            Optional<List<String>> projection) {
        return run(() -> mongo.get(domain).find(page, filter, sort, projection),
                () -> pg.get(domain).find(page, filter, sort, projection));
    }

    /** A find with a filter only. */
    public Outcome find(String domain, IFilter filter) {
        return find(domain, Optional.empty(), Optional.ofNullable(filter), Optional.empty(), Optional.empty());
    }

    /** Asks both engines the same count. */
    public Outcome count(String domain, IFilter filter) {
        return run(() -> mongo.get(domain).count(filter), () -> pg.get(domain).count(filter));
    }

    /**
     * Fails unless both engines answered the same thing.
     *
     * @param what    the scenario, for the failure message
     * @param outcome both answers
     * @param ordered whether a list's ORDER is part of the answer (a sorted find) or not
     */
    public static void assertSame(String what, Outcome outcome, boolean ordered) {
        if (outcome.mongoError() != null || outcome.pgError() != null) {
            if (outcome.mongoError() == null || outcome.pgError() == null) {
                fail(what + " — one engine failed, the other did not: MongoDB "
                        + describe(outcome.mongo(), outcome.mongoError()) + " / PostgreSQL "
                        + describe(outcome.pg(), outcome.pgError()));
            }
            return;
        }
        JsonNode m = canonical(outcome.mongo(), ordered);
        JsonNode p = canonical(outcome.pg(), ordered);
        if (!m.equals(p)) {
            fail(what + " — engines disagree.\n  MongoDB:    " + m + "\n  PostgreSQL: " + p);
        }
    }

    /** {@return whether both engines answered the same thing} — for scenarios that record, not fail. */
    public static boolean same(Outcome outcome, boolean ordered) {
        try {
            assertSame("", outcome, ordered);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    private static JsonNode canonical(Object value, boolean ordered) {
        if (value instanceof List<?> list && !ordered) {
            List<JsonNode> nodes = new ArrayList<>();
            for (Object item : list) {
                nodes.add(CANONICAL.valueToTree(item));
            }
            nodes.sort(Comparator.comparing(JsonNode::toString));
            return CANONICAL.valueToTree(nodes);
        }
        return CANONICAL.valueToTree(value);
    }

    private static Outcome run(Callable<Object> onMongo, Callable<Object> onPg) {
        Object m = null;
        Object p = null;
        Throwable me = null;
        Throwable pe = null;
        try {
            m = onMongo.call();
        } catch (Exception | Error e) {
            me = e;
        }
        try {
            p = onPg.call();
        } catch (Exception | Error e) {
            pe = e;
        }
        return new Outcome(m, p, me, pe);
    }

    private static String describe(Object value, Throwable error) {
        return error == null ? "returned " + value : "threw " + error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
