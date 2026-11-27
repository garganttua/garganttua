package com.garganttua.dao.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.dao.postgresql.TestDomains;
import com.garganttua.dao.postgresql.parity.ParityFilter;
import com.garganttua.dao.sql.SqlSchemaRegistry;
import com.garganttua.dao.sqlite.schema.SqliteIndexMode;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/**
 * What the SQLite DAO does beyond the parity scenarios: its schema and index management, its text search, its
 * regular expressions, its storage, its behaviour under concurrent writers.
 */
@DisplayName("SQLite DAO")
class SqliteDaoTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    /** An article. */
    public static class Article {
        String uuid;
        String tenantId;
        String title;
        String body;
        String code;
        BigDecimal price;
        List<String> tags;
    }

    private static Article article(String uuid, String title, String body) {
        Article a = new Article();
        a.uuid = uuid;
        a.tenantId = "T1";
        a.title = title;
        a.body = body;
        return a;
    }

    private static SqliteDao dao(DataSource db, List<EntityIndexRule> indexes, SqliteSchemaMode mode,
            SqliteIndexMode indexMode) throws ApiException {
        SqliteDao dao = new SqliteDao(db, "articles", new SqlSchemaRegistry(), mode, indexMode);
        dao.registerDomain(TestDomains.indexed(Article.class, "tenantId", indexes));
        return dao;
    }

    private static List<String> uuids(SqliteDao dao, IFilter filter) throws ApiException {
        return dao.find(Optional.empty(), Optional.ofNullable(filter), Optional.empty()).stream()
                .map(a -> ((Article) a).uuid).sorted().toList();
    }

    private static EntityIndexRule rule(String field, boolean unique, IndexKind kind) {
        return new EntityIndexRule(new ObjectAddress(field), unique, UnicityScope.system, kind, null);
    }

    @Nested
    @DisplayName("$text")
    class Text {

        @Test
        @DisplayName("matches English stems, ignores stop words, honours negations and phrases")
        void stems() throws ApiException {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(rule("body", false, IndexKind.text)),
                    SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
            dao.save(article("a1", "t", "The runners were running in the park"));
            dao.save(article("a2", "t", "A quiet café by the river"));
            dao.save(article("a3", "t", "Running shoes and a river walk"));
            assertEquals(List.of("a1", "a3"), uuids(dao, ParityFilter.field("body", "$text", "run")));
            assertEquals(List.of("a2", "a3"), uuids(dao, ParityFilter.field("body", "$text", "rivers")));
            assertEquals(List.of("a2"), uuids(dao, ParityFilter.field("body", "$text", "cafe")));
            assertEquals(List.of("a1"), uuids(dao, ParityFilter.field("body", "$text", "running -river")));
            assertEquals(List.of("a3"), uuids(dao, ParityFilter.field("body", "$text", "\"river walk\"")));
            assertEquals(List.of(), uuids(dao, ParityFilter.field("body", "$text", "the")));
        }

        @Test
        @DisplayName("without a text index, searches every string of the entity, collections included")
        void wholeDocument() throws ApiException {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(), SqliteSchemaMode.CREATE,
                    SqliteIndexMode.STRICT);
            Article a = article("a1", "Hello", "world");
            a.tags = List.of("gardening");
            dao.save(a);
            dao.save(article("a2", "Other", "thing"));
            assertEquals(List.of("a1"), uuids(dao, ParityFilter.field("body", "$text", "garden")));
        }

        @Test
        @DisplayName("the Snowball English stems MongoDB and PostgreSQL produce")
        void stemmer() {
            Map<String, String> expected = Map.ofEntries(Map.entry("consigned", "consign"),
                    Map.entry("consignment", "consign"), Map.entry("consistency", "consist"),
                    Map.entry("consistently", "consist"), Map.entry("running", "run"), Map.entry("caresses", "caress"),
                    Map.entry("ponies", "poni"), Map.entry("ties", "tie"), Map.entry("cats", "cat"),
                    Map.entry("happy", "happi"), Map.entry("generously", "generous"),
                    Map.entry("communication", "communic"), Map.entry("skies", "sky"), Map.entry("dying", "die"),
                    Map.entry("agreed", "agre"), Map.entry("relational", "relat"));
            expected.forEach((word, stem) -> assertEquals(stem, SqliteEnglishStemmer.stem(word), word));
        }
    }

    @Nested
    @DisplayName("$regex")
    class Regex {

        @Test
        @DisplayName("PCRE's meaning: $ before a final newline only, (?i) scoped, Unicode case folding")
        void pcre() throws ApiException {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(), SqliteSchemaMode.CREATE,
                    SqliteIndexMode.STRICT);
            Article a = article("a1", "Élan", "x");
            Article b = article("a2", "alpha\n", "x");
            Article c = article("a3", "ALPHA\r", "x");
            dao.save(a);
            dao.save(b);
            dao.save(c);
            assertEquals(List.of("a2"), uuids(dao, ParityFilter.field("title", "$regex", "^alpha$")));
            assertEquals(List.of("a1"), uuids(dao, ParityFilter.field("title", "$regex", "(?i)^élan$")));
            assertEquals(List.of("a2", "a3"), uuids(dao, ParityFilter.field("title", "$regex", "(?i)^alpha")));
            assertEquals(List.of("a2"), uuids(dao, ParityFilter.field("title", "$regex", "^a(?i)LPHA")));
        }

        @Test
        @DisplayName("a construct Java cannot run is refused with the field named, not matched differently")
        void refused() throws ApiException {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(), SqliteSchemaMode.CREATE,
                    SqliteIndexMode.STRICT);
            ApiException e = assertThrows(ApiException.class,
                    () -> uuids(dao, ParityFilter.field("title", "$regex", "(a)(?(1)b|c)")));
            assertTrue(e.getMessage().contains("'title'"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("indexes")
    class Indexes {

        @Test
        @DisplayName("a unique index refuses a second entity with the same value, and tolerates many unset ones")
        void unique() throws Exception {
            DataSource db = SqliteTestDatabase.freshDatabase();
            SqliteDao dao = dao(db, List.of(rule("code", true, IndexKind.standard)), SqliteSchemaMode.CREATE,
                    SqliteIndexMode.STRICT);
            Article a = article("a1", "t", "b");
            a.code = "X";
            Article b = article("a2", "t", "b");
            b.code = "X";
            dao.save(a);
            dao.save(article("a3", "t", "b"));
            dao.save(article("a4", "t", "b"));
            ApiException e = assertThrows(ApiException.class, () -> dao.save(b));
            assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unique"), e.getMessage());
            assertTrue(indexSql(db).stream().anyMatch(sql -> sql.contains("UNIQUE") && sql.contains("IS NOT NULL")),
                    () -> indexSql(db).toString());
        }

        @Test
        @DisplayName("a unique decimal compares by value: 1.50 refuses 1.5, as MongoDB's Decimal128 does")
        void uniqueDecimal() throws Exception {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(rule("price", true, IndexKind.standard)),
                    SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
            Article a = article("a1", "t", "b");
            a.price = new BigDecimal("1.50");
            Article b = article("a2", "t", "b");
            b.price = new BigDecimal("1.5");
            dao.save(a);
            assertThrows(ApiException.class, () -> dao.save(b));
            Article read = (Article) dao.find(Optional.empty(), Optional.empty(), Optional.empty()).get(0);
            assertEquals("1.50", read.price.toString(), "the scale is stored exactly");
        }

        @Test
        @DisplayName("STRICT refuses to start when a unique index cannot be built over existing duplicates")
        void duplicates() throws Exception {
            DataSource db = SqliteTestDatabase.freshDatabase();
            SqliteDao loose = dao(db, List.of(), SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
            Article a = article("a1", "t", "b");
            a.code = "X";
            Article b = article("a2", "t", "b");
            b.code = "X";
            loose.save(a);
            loose.save(b);
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> dao(db,
                    List.of(rule("code", true, IndexKind.standard)), SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT));
            assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
        }

        private List<String> indexSql(DataSource db) {
            List<String> sql = new ArrayList<>();
            try (Connection c = db.getConnection(); Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL")) {
                while (rs.next()) {
                    sql.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return sql;
        }
    }

    @Nested
    @DisplayName("schema")
    class Schema {

        @Test
        @DisplayName("VALIDATE issues no DDL and refuses a missing table, naming the statement that creates it")
        void validate() {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> dao(
                    SqliteTestDatabase.freshDatabase(), List.of(), SqliteSchemaMode.VALIDATE, SqliteIndexMode.STRICT));
            assertTrue(e.getMessage().contains("CREATE TABLE"), e.getMessage());
        }

        @Test
        @DisplayName("CREATE adds the column a model gained to an existing table, then VALIDATE accepts it")
        void migrate() throws Exception {
            DataSource db = SqliteTestDatabase.freshDatabase();
            dao(db, List.of(), SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("ALTER TABLE articles DROP COLUMN code");
            }
            IllegalStateException missing = assertThrows(IllegalStateException.class, () -> dao(db, List.of(),
                    SqliteSchemaMode.VALIDATE, SqliteIndexMode.STRICT));
            assertTrue(missing.getMessage().contains("column 'code' is missing"), missing.getMessage());
            dao(db, List.of(), SqliteSchemaMode.CREATE, SqliteIndexMode.STRICT);
            SqliteDao validated = dao(db, List.of(), SqliteSchemaMode.VALIDATE, SqliteIndexMode.STRICT);
            Article a = article("a1", "t", "b");
            a.code = "C";
            validated.save(a);
            assertEquals(List.of("a1"), uuids(validated, ParityFilter.field("code", "$eq", "C")));
        }
    }

    @Nested
    @DisplayName("storage")
    class Storage {

        @Test
        @DisplayName("the order key orders as BSON: NaN below numbers, integers and doubles by value, text by bytes")
        void orderKey() {
            assertTrue(SqliteOrderKey.number(Double.NaN).compareTo(SqliteOrderKey.number(Double.NEGATIVE_INFINITY)) < 0);
            assertTrue(SqliteOrderKey.number(2.5).compareTo(SqliteOrderKey.number(3L)) < 0);
            assertEquals(SqliteOrderKey.number(-0.0), SqliteOrderKey.number(0L));
            assertTrue(SqliteOrderKey.number(9_007_199_254_740_992.0)
                    .compareTo(SqliteOrderKey.number(9_007_199_254_740_993L)) < 0, "2^53 < 2^53+1");
            assertTrue(SqliteOrderKey.text("a").compareTo(SqliteOrderKey.text("a\u0000")) < 0, "a prefix first");
            assertTrue(SqliteOrderKey.text("Z").compareTo(SqliteOrderKey.text("a")) < 0, "bytes, not collation");
            assertTrue(SqliteOrderKey.text("é").compareTo(SqliteOrderKey.text("z")) > 0, "UTF-8 bytes");
            assertTrue(SqliteOrderKey.blob(new byte[] { 9 }).compareTo(SqliteOrderKey.blob(new byte[] { 0, 0 })) < 0,
                    "a shorter binary first, whatever its bytes");
        }

        @Test
        @DisplayName("each connection is readied once: foreign keys on, functions registered")
        void readied() throws Exception {
            DataSource db = SqliteTestDatabase.freshDatabase();
            try (Connection c = db.getConnection()) {
                SqliteFunctions.ready(c);
                SqliteFunctions.ready(c);
                try (Statement st = c.createStatement();
                        ResultSet rs = st.executeQuery("SELECT gg_regexp('abc', 'b'), gg_okey(1) < gg_okey(2)")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertEquals(1, rs.getInt(2));
                }
            }
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("eight writers on one database file: every write lands, none fails on a busy lock")
        void writers() throws Exception {
            SqliteDao dao = dao(SqliteTestDatabase.freshDatabase(), List.of(), SqliteSchemaMode.CREATE,
                    SqliteIndexMode.STRICT);
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> done = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    int thread = t;
                    done.add(pool.submit(() -> {
                        for (int i = 0; i < 25; i++) {
                            Article a = article("t" + thread + "-" + i, "title", "body");
                            a.tags = List.of("x", "y");
                            dao.save(a);
                            dao.find(Optional.empty(), Optional.empty(), Optional.empty());
                        }
                        return null;
                    }));
                }
                for (Future<?> f : done) {
                    f.get(2, TimeUnit.MINUTES);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(200L, dao.count(null));
            assertFalse(uuids(dao, ParityFilter.field("tags", "$eq", "y")).isEmpty());
        }
    }
}
