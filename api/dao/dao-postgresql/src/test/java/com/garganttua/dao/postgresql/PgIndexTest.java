package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.dao.postgresql.schema.PgIndexMode;
import com.garganttua.dao.postgresql.schema.PgSchemaModel;
import com.garganttua.dao.postgresql.schema.PgTable;
import com.garganttua.dao.postgresql.schema.SchemaMode;

/**
 * The indexes a domain declares, proved against a real PostgreSQL: what is created, that the
 * database then holds the uniqueness under concurrency and answers 409, and what each mode does
 * with an index it cannot create.
 */
@DisplayName("Declared indexes on PostgreSQL")
class PgIndexTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    public static class Address {
        String city;
    }

    public static class Claim {
        String uuid;
        String tenantId;
        String claimKey;
        String title;
        Address address;
        List<String> sharedTenantIds;
    }

    private static final EntityIndexRule UNIQUE_KEY = EntityIndexRule.of(new ObjectAddress("claimKey"), true,
            UnicityScope.tenant);

    private DataSource db;

    @BeforeEach
    void setUp() {
        db = PgTestDatabase.freshDatabase();
    }

    private PgDao dao(PgIndexMode mode, EntityIndexRule... rules) {
        return dao(db, SchemaMode.CREATE, mode, rules);
    }

    private static PgDao dao(DataSource source, SchemaMode schema, PgIndexMode mode, EntityIndexRule... rules) {
        PgDao dao = new PgDao(source, "claims", new PgSchemaRegistry(), schema, mode);
        dao.registerDomain(TestDomains.indexed(Claim.class, "tenantId", List.of(rules)));
        return dao;
    }

    private static Claim claim(String uuid, String tenant, String key) {
        Claim claim = new Claim();
        claim.uuid = uuid;
        claim.tenantId = tenant;
        claim.claimKey = key;
        return claim;
    }

    /** One stored index as the catalog describes it: its definition, as PostgreSQL renders it back. */
    private List<String> indexDefinitions() throws SQLException {
        List<String> definitions = new ArrayList<>();
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() "
                        + "AND indexname NOT LIKE '%\\_pkey' ORDER BY indexname")) {
            while (rs.next()) {
                definitions.add(rs.getString(1));
            }
        }
        return definitions;
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Nested
    @DisplayName("a unique, tenant-scoped index")
    class Unique {

        @Test
        @DisplayName("is created on (tenantId, field), partial on the field being set, named after its table")
        void created() throws SQLException {
            dao(PgIndexMode.CREATE, UNIQUE_KEY);
            List<String> definitions = indexDefinitions();
            assertEquals(1, definitions.size(), definitions::toString);
            String definition = definitions.get(0);
            assertTrue(definition.startsWith("CREATE UNIQUE INDEX \"claims__gg_claimKey_tenant_standard_unique\" ON "),
                    definition);
            assertTrue(definition.endsWith("USING btree (\"tenantId\", \"claimKey\") WHERE (\"claimKey\" IS NOT NULL)"),
                    definition);
        }

        @Test
        @DisplayName("30 concurrent writers of the same key: one row, 29 conflicts (409)")
        void concurrentWritersOneWins() throws Exception {
            PgDao dao = dao(PgIndexMode.CREATE, UNIQUE_KEY);
            int writers = 30;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            List<Future<Integer>> outcomes = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String uuid = "c" + i;
                Callable<Integer> write = () -> {
                    start.await();
                    try {
                        dao.save(claim(uuid, "T1", "nightly-job"));
                        return 200;
                    } catch (ApiException e) {
                        return e.getCode();
                    }
                };
                outcomes.add(pool.submit(write));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> outcome : outcomes) {
                codes.add(outcome.get());
            }
            pool.shutdown();
            assertEquals(1, codes.stream().filter(c -> c == 200).count(), codes::toString);
            assertEquals(writers - 1, codes.stream().filter(c -> c == ApiException.CONFLICT).count(), codes::toString);
            assertEquals(1, dao.count(null));
        }

        @Test
        @DisplayName("the 409 names the index; the same key in another tenant, and several unset keys, are accepted")
        void conflictAndScope() throws ApiException {
            PgDao dao = dao(PgIndexMode.CREATE, UNIQUE_KEY);
            dao.save(claim("a", "T1", "k"));
            ApiException conflict = assertThrows(ApiException.class, () -> dao.save(claim("b", "T1", "k")));
            assertEquals(ApiException.CONFLICT, conflict.getCode());
            assertTrue(conflict.getMessage().contains("claims__gg_claimKey_tenant_standard_unique"),
                    conflict.getMessage());

            dao.save(claim("c", "T2", "k"));
            dao.save(claim("d", "T1", null));
            dao.save(claim("e", "T1", null));
            dao.save(claim("a", "T1", "k"));
            assertEquals(4, dao.count(null), "re-saving the holder of the key is an update, not a duplicate");
        }

        @Test
        @DisplayName("a refused write leaves its connection usable: the transaction is rolled back, not left aborted")
        void connectionUsableAfterConflict() throws Exception {
            dao(PgIndexMode.CREATE, UNIQUE_KEY);
            try (Connection only = db.getConnection()) {
                PgDao dao = dao(singleConnection(only), SchemaMode.CREATE, PgIndexMode.CREATE, UNIQUE_KEY);
                dao.save(claim("a", "T1", "k"));
                assertThrows(ApiException.class, () -> dao.save(claim("b", "T1", "k")));
                dao.save(claim("c", "T1", "other"));
                assertEquals(2, dao.count(null));
                assertTrue(only.getAutoCommit(), "the connection is handed back as it was lent");
            }
        }

        @Test
        @DisplayName("restarting finds the index in place and creates nothing more")
        void idempotent() throws SQLException {
            dao(PgIndexMode.CREATE, UNIQUE_KEY);
            dao(PgIndexMode.STRICT, UNIQUE_KEY);
            assertEquals(1, indexDefinitions().size());
            assertRecognised(PgIndexPlanner.plan(PgSchemaModel.of("claims", IClass.getClass(Claim.class), "uuid",
                    Map.of()), List.of(UNIQUE_KEY), p -> p, "tenantId").get(0).spec().orElseThrow());
        }

        @Test
        @DisplayName("two domains declaring the same field get two indexes, not one silently skipped")
        void namesDoNotCollideAcrossTables() throws SQLException {
            dao(PgIndexMode.CREATE, UNIQUE_KEY);
            PgDao other = new PgDao(db, "relays", new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.STRICT);
            other.registerDomain(TestDomains.indexed(Claim.class, "tenantId", List.of(UNIQUE_KEY)));
            try (Connection c = db.getConnection(); Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() "
                            + "AND indexname LIKE '%gg_claimKey%'")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Nested
    @DisplayName("a table that already holds duplicates")
    class ExistingDuplicates {

        @BeforeEach
        void duplicates() throws ApiException {
            PgDao bare = dao(PgIndexMode.NONE, UNIQUE_KEY);
            bare.save(claim("a", "T1", "k"));
            bare.save(claim("b", "T1", "k"));
        }

        @Test
        @DisplayName("CREATE starts without the index")
        void createStarts() throws SQLException {
            dao(PgIndexMode.CREATE, UNIQUE_KEY);
            assertTrue(indexDefinitions().isEmpty());
        }

        @Test
        @DisplayName("STRICT refuses to start, with a query that lists the offending rows")
        void strictRefuses() throws SQLException {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> dao(PgIndexMode.STRICT, UNIQUE_KEY));
            String message = refused.getMessage();
            assertTrue(message.contains("already holds duplicate values"), message);
            String query = message.substring(message.indexOf("SELECT "), message.indexOf(" — then"));
            try (Connection c = db.getConnection(); Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(query)) {
                assertTrue(rs.next(), "the query in the message lists the duplicate group");
                assertEquals("T1", rs.getString(1));
                assertEquals("k", rs.getString(2));
                assertEquals(2, rs.getInt(3));
            }
        }
    }

    @Nested
    @DisplayName("under schema mode VALIDATE")
    class Validate {

        @Test
        @DisplayName("no index is created; STRICT refuses with the statement to apply")
        void reportsWithoutDdl() throws SQLException {
            dao(PgIndexMode.NONE);
            dao(db, SchemaMode.VALIDATE, PgIndexMode.CREATE, UNIQUE_KEY);
            assertTrue(indexDefinitions().isEmpty(), "VALIDATE issues no DDL");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> dao(db, SchemaMode.VALIDATE, PgIndexMode.STRICT, UNIQUE_KEY));
            assertTrue(refused.getMessage().contains("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "\"claims__gg_claimKey_tenant_standard_unique\""), refused.getMessage());
        }
    }

    @Nested
    @DisplayName("fields that do not live in a main-table column of their own")
    class Placement {

        @Test
        @DisplayName("a flattened field is indexed on its flattened column")
        void flattened() throws SQLException {
            dao(PgIndexMode.STRICT, EntityIndexRule.of(new ObjectAddress("address.city")));
            String definition = indexDefinitions().get(0);
            assertTrue(definition.endsWith("USING btree (\"tenantId\", address__city)"), definition);
        }

        @Test
        @DisplayName("a list is indexed on its child table's value column")
        void list() throws SQLException {
            dao(PgIndexMode.STRICT, EntityIndexRule.of(new ObjectAddress("sharedTenantIds")));
            String definition = indexDefinitions().get(0);
            assertTrue(definition.contains(" ON ") && definition.contains("claims__sharedTenantIds")
                    && definition.endsWith("USING btree (value)"), definition);
        }

        @Test
        @DisplayName("a unique list is refused: STRICT stops, CREATE starts without it")
        void uniqueListRefused() throws SQLException {
            EntityIndexRule uniqueList = EntityIndexRule.of(new ObjectAddress("sharedTenantIds"), true,
                    UnicityScope.tenant);
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> dao(PgIndexMode.STRICT, uniqueList));
            assertTrue(refused.getMessage().contains("child table"), refused.getMessage());
            dao(PgIndexMode.CREATE, uniqueList);
            assertTrue(indexDefinitions().isEmpty());
        }

        @Test
        @DisplayName("an unknown field is refused by name")
        void unknown() {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> dao(PgIndexMode.STRICT, EntityIndexRule.of(new ObjectAddress("nowhere"))));
            assertTrue(refused.getMessage().contains("'nowhere' maps no column"), refused.getMessage());
        }
    }

    @Nested
    @DisplayName("the tenant, which the framework adds to every read of a tenant caller")
    class FrameworkTenantIndex {

        private PgDao withEntityTenant(String entityTenant, EntityIndexRule... rules) {
            PgDao dao = new PgDao(db, "claims", new PgSchemaRegistry(), SchemaMode.CREATE, PgIndexMode.STRICT);
            dao.registerDomain(TestDomains.indexed(Claim.class, "tenantId", "uuid", entityTenant, List.of(rules)));
            return dao;
        }

        private String plan(String sql) throws SQLException {
            StringBuilder plan = new StringBuilder();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("SET enable_seqscan = off");
                try (ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
            }
            return plan.toString();
        }

        @Test
        @DisplayName("is indexed without being declared, and a read by tenant uses it")
        void indexedImplicitly() throws SQLException {
            withEntityTenant("tenantId");
            List<String> definitions = indexDefinitions();
            assertEquals(1, definitions.size(), definitions.toString());
            assertTrue(definitions.get(0).startsWith("CREATE INDEX \"claims__gg_tenantId_system_standard_idx\" ON "),
                    definitions.get(0));
            assertTrue(definitions.get(0).endsWith("USING btree (\"tenantId\")"), definitions.get(0));
            String plan = plan("SELECT * FROM claims WHERE \"tenantId\" = 't1'");
            assertTrue(plan.contains("claims__gg_tenantId_system_standard_idx"), plan);
        }

        @Test
        @DisplayName("declared by the entity already: one index, the declared one")
        void declaredOnce() throws SQLException {
            withEntityTenant("tenantId", EntityIndexRule.of(new ObjectAddress("tenantId"), false, UnicityScope.system));
            assertEquals(1, indexDefinitions().size(), indexDefinitions().toString());
        }

        @Test
        @DisplayName("a tenant that is the entity's own uuid needs none: the primary key is on it")
        void tenantIsUuid() throws SQLException {
            withEntityTenant("uuid");
            assertTrue(indexDefinitions().isEmpty(), indexDefinitions().toString());
        }

        @Test
        @DisplayName("an entity without tenant gets no index of its own")
        void noTenant() throws SQLException {
            withEntityTenant(null);
            assertTrue(indexDefinitions().isEmpty(), indexDefinitions().toString());
        }
    }

    @Nested
    @DisplayName("an existing index under the declared name")
    class Existing {

        @Test
        @DisplayName("is left as is when its definition differs")
        void leftAlone() throws SQLException {
            dao(PgIndexMode.NONE);
            execute("CREATE INDEX \"claims__gg_claimKey_tenant_standard_unique\" ON claims (\"claimKey\")");
            dao(PgIndexMode.STRICT, UNIQUE_KEY);
            String definition = indexDefinitions().get(0);
            assertFalse(definition.startsWith("CREATE UNIQUE"), definition);
        }
    }

    @Nested
    @DisplayName("a text index")
    class Text {

        private final EntityIndexRule textOnTitle = new EntityIndexRule(new ObjectAddress("title"), false,
                UnicityScope.system, IndexKind.text, null);

        @Test
        @DisplayName("is a GIN index that $text then uses, searching the declared field only")
        void servesText() throws Exception {
            PgDao dao = dao(PgIndexMode.STRICT, textOnTitle);
            Claim running = claim("a", "T1", "x");
            running.title = "Running the nightly report";
            Claim elsewhere = claim("b", "T1", "running");
            elsewhere.title = "Unrelated";
            dao.save(running);
            dao.save(elsewhere);

            IFilter search = PgQueryFixture.field("anything", "$text", "runs");
            List<Object> found = dao.find(Optional.empty(), Optional.of(search), Optional.empty());
            assertEquals(1, found.size(), "stemmed match on the title, and ONLY the title — as MongoDB's text index");
            assertEquals("a", ((Claim) found.get(0)).uuid);

            String definition = indexDefinitions().get(0);
            assertTrue(definition.contains("USING gin (to_tsvector("), definition);
            assertRecognised(PgIndexPlanner.plan(PgSchemaModel.of("claims", IClass.getClass(Claim.class), "uuid",
                    Map.of()), List.of(textOnTitle), p -> p, "tenantId").get(0).spec().orElseThrow());
            assertTrue(plan(search).contains("claims__gg_title_system_text_idx"),
                    "the search must be served by the index");
        }

        private String plan(IFilter search) throws Exception {
            PgTable table = PgSchemaModel.of("claims", IClass.getClass(Claim.class), "uuid", Map.of());
            PgQuery query = new PgQueryBuilder(table, null, Optional.of(table.column("title").orElseThrow()))
                    .build(Optional.empty(), Optional.of(search), Optional.empty(), Optional.empty());
            StringBuilder plan = new StringBuilder();
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("SET enable_seqscan = off");
                try (var statement = c.prepareStatement("EXPLAIN SELECT 1 FROM claims AS " + PgQuery.ALIAS
                        + " WHERE " + query.where())) {
                    for (int i = 0; i < query.params().size(); i++) {
                        statement.setObject(i + 1, query.params().get(i));
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
            }
            return plan.toString();
        }
    }

    /**
     * The stored index must read back as the declared one — otherwise every restart would WARN that
     * its definition differs. The catalog renders an expression key its own way (casts, parentheses).
     */
    private void assertRecognised(PgIndexSpec spec) throws SQLException {
        try (Connection c = db.getConnection()) {
            List<PgIndexSpec.PgStoredIndex> stored = PgIndexManager.stored(c, java.util.Set.of(spec.table()),
                    java.util.Set.of(spec.name())).stream().filter(i -> i.name().equals(spec.name())).toList();
            assertEquals(1, stored.size(), stored::toString);
            assertTrue(spec.matches(stored.get(0)), () -> "declared " + spec.describe() + " / stored "
                    + stored.get(0).describe());
        }
    }

    /** A data source that always lends the SAME connection — what a pool of one does under load. */
    private static DataSource singleConnection(Connection connection) {
        InvocationHandler keepOpen = (proxy, method, args) -> "close".equals(method.getName()) ? null
                : method.invoke(connection, args);
        Connection lent = (Connection) Proxy.newProxyInstance(PgIndexTest.class.getClassLoader(),
                new Class<?>[] { Connection.class }, keepOpen);
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return lent;
            }

            @Override
            public Connection getConnection(String user, String password) {
                return lent;
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
                // unused
            }

            @Override
            public void setLoginTimeout(int seconds) {
                // unused
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
