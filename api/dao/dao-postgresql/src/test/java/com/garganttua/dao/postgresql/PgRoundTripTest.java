package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.service.Page;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * What each operation costs in round trips to the server — the figure that decides its latency over a
 * network, whatever the SQL. Pinned here so that it does not creep back: a read used to cost a
 * {@code SET} of isolation, a {@code SET} back and a {@code COMMIT} around its query, and one more query
 * per collection table.
 */
@DisplayName("Round trips per operation")
class PgRoundTripTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Term {
        String uuid;
        String name;
    }

    public static class Service {
        String code;
        List<String> codes;
    }

    public static class Facility {
        String uuid;
        String name;
        List<String> phones;
        List<Service> services;
    }

    private RoundTripCounter db;
    private PgDao terms;
    private PgDao facilities;

    @BeforeEach
    void setUp() throws SQLException, ApiException {
        db = new RoundTripCounter(PgTestDatabase.freshDatabase());
        terms = new PgDao(db, "terms", new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.NONE);
        terms.registerDomain(TestDomains.definition(Term.class, Map.of()));
        facilities = new PgDao(db, "facilities", new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.NONE);
        facilities.registerDomain(TestDomains.definition(Facility.class, Map.of()));
        for (int i = 0; i < 5; i++) {
            terms.save(term("t" + i));
            facilities.save(facility("f" + i, "facility " + i));
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static Term term(String uuid) {
        Term term = new Term();
        term.uuid = uuid;
        term.name = uuid;
        return term;
    }

    private static Facility facility(String uuid, String name) {
        Facility facility = new Facility();
        facility.uuid = uuid;
        facility.name = name;
        facility.phones = List.of("01", "02");
        facility.services = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Service service = new Service();
            service.code = "S" + i;
            service.codes = List.of("a", "b");
            facility.services.add(service);
        }
        return facility;
    }

    private interface Operation {
        void run() throws ApiException;
    }

    private int trips(Operation operation) throws ApiException {
        db.reset();
        operation.run();
        return db.trips();
    }

    @Test
    @DisplayName("a read, however many collection tables it spans: ONE statement, no transaction")
    void reads() throws ApiException {
        assertEquals(1, trips(() -> terms.find(Optional.empty(), Optional.of(PgQueryFixture.field("uuid", "$eq", "t1")),
                Optional.empty())), () -> db.sent().toString());
        assertEquals(1, trips(() -> facilities.find(Optional.empty(),
                Optional.of(PgQueryFixture.field("uuid", "$eq", "f1")), Optional.empty())), () -> db.sent().toString());
        assertEquals(1, trips(() -> facilities.count(null)), () -> db.sent().toString());
    }

    @Test
    @DisplayName("a page AND its total: ONE statement")
    void page() throws ApiException {
        Page[] page = new Page[1];
        assertEquals(1, trips(() -> page[0] = facilities.findPage(Optional.of(new Pageable(1, 2)), Optional.empty(),
                Optional.empty(), Optional.empty())), () -> db.sent().toString());
        assertEquals(5, page[0].totalCount());
        assertEquals(2, page[0].entities().size());
        assertEquals(2, trips(() -> page[0] = facilities.findPage(Optional.of(new Pageable(9, 2)), Optional.empty(),
                Optional.empty(), Optional.empty())), "a page past the end has no row to carry the total: + a count");
        assertEquals(5, page[0].totalCount());
    }

    @Test
    @DisplayName("writes: one statement without collections; the cascade carries the delete")
    void writes() throws ApiException {
        assertEquals(1, trips(() -> terms.save(term("new"))), () -> db.sent().toString());
        assertEquals(1, trips(() -> terms.delete(term("new"))), () -> db.sent().toString());
        assertEquals(1, trips(() -> facilities.delete(facility("f4", "x"))), () -> db.sent().toString());
        assertEquals(3, trips(() -> facilities.save(facility("f1", "renamed"))),
                () -> "digests read and locked, the main row, the commit — no collection rewritten: " + db.sent());
    }
}
