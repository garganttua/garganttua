package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The list digests, proved against a real PostgreSQL: a write that leaves a collection as it is leaves
 * its rows as they are — witnessed by {@code xmin}, the transaction that wrote a row — and every real
 * change, wherever it is, is still written. A digest never lies, whoever writes the child rows.
 */
@DisplayName("List digests — rewrite only the collections that changed")
class PgListDigestTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Service {
        String code;
        List<String> codes;

        Service() {
        }

        Service(String code, String... codes) {
            this.code = code;
            this.codes = new ArrayList<>(List.of(codes));
        }
    }

    public static class Facility {
        String uuid;
        String name;
        List<String> phones;
        List<Service> services;
        Map<String, Integer> beds;
    }

    private static final List<String> CHILD_TABLES = List.of("facilities__phones", "facilities__services",
            "facilities__services__codes", "facilities__beds");

    private DataSource db;
    private PgDao facilities;

    @BeforeEach
    void setUp() throws ApiException {
        db = PgTestDatabase.freshDatabase();
        facilities = dao(SchemaMode.CREATE);
        facilities.save(facility("Clinique"));
    }

    private PgDao dao(SchemaMode mode) {
        PgDao dao = new PgDao(db, "facilities", new PgSchemaRegistry(), mode, PgIndexMode.NONE);
        dao.registerDomain(TestDomains.definition(Facility.class, Map.of()));
        return dao;
    }

    private static Facility facility(String name) {
        Facility facility = new Facility();
        facility.uuid = "f1";
        facility.name = name;
        facility.phones = new ArrayList<>(List.of("0102", "0304"));
        facility.services = new ArrayList<>(List.of(new Service("PAL", "a", "b"), new Service("ONC", "c", "d", "e")));
        facility.beds = new LinkedHashMap<>(Map.of("day", 4, "night", 2));
        return facility;
    }

    private Facility read() throws ApiException {
        return (Facility) facilities.find(Optional.empty(), Optional.empty(), Optional.empty()).get(0);
    }

    /** Which transaction wrote each row of each child table — unchanged when the rows were not rewritten. */
    private Map<String, List<String>> writers() throws SQLException {
        Map<String, List<String>> writers = new LinkedHashMap<>();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String table : CHILD_TABLES) {
                List<String> xmins = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("SELECT xmin::text FROM \"" + table + "\" ORDER BY 1")) {
                    while (rs.next()) {
                        xmins.add(rs.getString(1));
                    }
                }
                writers.put(table, xmins);
            }
        }
        return writers;
    }

    private void sql(String statement) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private String digest(String column) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT \"" + column + "\" FROM facilities")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Nested
    @DisplayName("a write that changes no collection")
    class Unchanged {

        @Test
        @DisplayName("rewrites no child row, at any depth — the PATCH of a name")
        void noRewrite() throws Exception {
            Map<String, List<String>> before = writers();
            facilities.save(facility("Clinique du Parc"));
            assertEquals(before, writers(), "not one child row may have been deleted and re-inserted");
            assertEquals("Clinique du Parc", read().name);
            assertEquals(List.of("0102", "0304"), read().phones);
        }
    }

    @Nested
    @DisplayName("a real change")
    class Changes {

        @Test
        @DisplayName("an element rewrites its collection, and only it")
        void element() throws Exception {
            Map<String, List<String>> before = writers();
            Facility changed = facility("Clinique");
            changed.phones.set(1, "0999");
            facilities.save(changed);
            Map<String, List<String>> after = writers();
            assertNotEquals(before.get("facilities__phones"), after.get("facilities__phones"));
            assertEquals(before.get("facilities__services"), after.get("facilities__services"));
            assertEquals(before.get("facilities__beds"), after.get("facilities__beds"));
            assertEquals(List.of("0102", "0999"), read().phones);
        }

        @Test
        @DisplayName("the order of the elements")
        void order() throws Exception {
            Facility changed = facility("Clinique");
            changed.phones = new ArrayList<>(List.of("0304", "0102"));
            facilities.save(changed);
            assertEquals(List.of("0304", "0102"), read().phones);
        }

        @Test
        @DisplayName("an element of a sub-list")
        void subList() throws Exception {
            Map<String, List<String>> before = writers();
            Facility changed = facility("Clinique");
            changed.services.get(1).codes.set(2, "z");
            facilities.save(changed);
            assertNotEquals(before.get("facilities__services__codes"), writers().get("facilities__services__codes"));
            assertEquals(List.of("c", "d", "z"), read().services.get(1).codes);
        }

        @Test
        @DisplayName("a map value, and a list emptied")
        void mapAndEmpty() throws Exception {
            Facility changed = facility("Clinique");
            changed.beds.put("night", 3);
            changed.phones = new ArrayList<>();
            facilities.save(changed);
            Facility read = read();
            assertEquals(3, read.beds.get("night"));
            assertTrue(read.phones.isEmpty());
        }
    }

    @Nested
    @DisplayName("a writer other than the DAO")
    class OtherWriters {

        @Test
        @DisplayName("a row inserted by SQL clears the digest; the next save restores the list")
        void sqlInsert() throws Exception {
            String digest = digest("_digest__phones");
            sql("INSERT INTO \"facilities__phones\" (\"_owner\", \"_ord\", \"value\") VALUES ('f1', 2, 'intruder')");
            assertNull(digest("_digest__phones"), "the trigger clears the owner's digest");
            facilities.save(facility("Clinique"));
            assertEquals(List.of("0102", "0304"), read().phones);
            assertEquals(digest, digest("_digest__phones"));
        }

        @Test
        @DisplayName("a row deleted in a sub-list clears the digest of its top-level collection")
        void nestedDelete() throws Exception {
            sql("DELETE FROM \"facilities__services__codes\" WHERE \"value\" = 'e'");
            assertNull(digest("_digest__services"));
            facilities.save(facility("Clinique"));
            assertEquals(List.of("c", "d", "e"), read().services.get(1).codes);
        }

        @Test
        @DisplayName("a TRUNCATE clears every digest of the table")
        void truncate() throws Exception {
            sql("TRUNCATE \"facilities__beds\"");
            assertNull(digest("_digest__beds"));
            facilities.save(facility("Clinique"));
            assertEquals(2, read().beds.size());
        }

        @Test
        @DisplayName("an instance without digests (rolling upgrade) writing another list cannot leave a lying digest")
        void olderInstance() throws Exception {
            // What an ALPHA29 instance does to the lists: delete and re-insert, with no flag and no digest.
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.execute("UPDATE facilities SET name = 'Autre' WHERE uuid = 'f1'");
                    s.execute("DELETE FROM \"facilities__phones\" WHERE \"_owner\" = 'f1'");
                    s.execute("INSERT INTO \"facilities__phones\" (\"_owner\", \"_ord\", \"value\") VALUES ('f1', 0, '0555')");
                }
                c.commit();
            }
            assertNull(digest("_digest__phones"));
            facilities.save(facility("Clinique"));
            assertEquals(List.of("0102", "0304"), read().phones,
                    "the list the older instance wrote must not survive behind a digest of the original one");
        }
    }

    @Test
    @DisplayName("under VALIDATE without the digest DDL, every write still lands — every collection rewritten")
    void validateWithoutDigests() throws Exception {
        for (String table : CHILD_TABLES) {
            sql("DROP TRIGGER IF EXISTS \"" + table + "__digest\" ON \"" + table + "\"");
            sql("DROP TRIGGER IF EXISTS \"" + table + "__digest_truncate\" ON \"" + table + "\"");
        }
        PgDao validating = dao(SchemaMode.VALIDATE);
        Facility changed = facility("Validée");
        changed.phones = new ArrayList<>(List.of("0777"));
        validating.save(changed);
        Facility read = (Facility) validating.find(Optional.empty(), Optional.empty(), Optional.empty()).get(0);
        assertEquals("Validée", read.name);
        assertEquals(List.of("0777"), read.phones);
    }

    @Test
    @DisplayName("deleting the entity takes its whole tree")
    void delete() throws Exception {
        facilities.delete(facility("Clinique"));
        for (List<String> rows : writers().values()) {
            assertTrue(rows.isEmpty());
        }
    }
}
