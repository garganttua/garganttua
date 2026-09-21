package com.garganttua.dao.postgresql.parity;

import static com.garganttua.dao.postgresql.parity.ParityFilter.field;
import static com.garganttua.dao.postgresql.parity.ParityFilter.listed;
import static com.garganttua.dao.postgresql.parity.ParityFilter.logical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Parity of the collection family: {@code List<String>}, {@code List<Integer>}, {@code Set<String>},
 * {@code String[]}, {@code List<POJO>} (dotted element paths) and {@code Map<String,Integer>} (dotted
 * key paths) — in filters, and in what a find gives back.
 *
 * <p>
 * The seed discriminates on purpose: every scenario has rows that must match, rows that must not, a
 * row whose collections are EMPTY and a row whose collections are NULL (the MongoDB DAO omits a null
 * field, so it is ABSENT from the document; an empty list is stored as {@code []}, a present field).
 * Each test is a specification: when both engines agree, it passes.
 * </p>
 */
@DisplayName("Parity — collections (arrays, sets, lists of POJOs, maps)")
class ParityArrayTest {

    @BeforeAll
    static void r() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ------------------------------------------------------------------ DTOs

    public static class Line {
        String sku;
        Integer qty;
        List<String> notes;
    }

    public static class Item {
        String uuid;
        String name;
        List<String> tags;
        List<Integer> scores;
        List<Line> lines;
        Map<String, Integer> stock;
    }

    public static class Labelled {
        String uuid;
        Set<String> labels;
    }

    public static class Arrayed {
        String uuid;
        String[] words;
    }

    // ------------------------------------------------------------------ seed

    private static final String ITEMS = "items";

    private static Line line(String sku, Integer qty, String... notes) {
        Line l = new Line();
        l.sku = sku;
        l.qty = qty;
        l.notes = notes.length == 0 ? null : new ArrayList<>(List.of(notes));
        return l;
    }

    private static Map<String, Integer> stock(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    private static Item item(String uuid, List<String> tags, List<Integer> scores, List<Line> lines,
            Map<String, Integer> stock) {
        Item i = new Item();
        i.uuid = uuid;
        i.name = "n-" + uuid;
        i.tags = tags == null ? null : new ArrayList<>(tags);
        i.scores = scores == null ? null : new ArrayList<>(scores);
        i.lines = lines == null ? null : new ArrayList<>(lines);
        i.stock = stock;
        return i;
    }

    /**
     * The standard seed.
     * <ul>
     * <li>a: tags [x,y], scores [10,20], lines [A/1 (notes n1), B/5], stock {x:5,y:1}</li>
     * <li>b: tags [y], scores [5], lines [C/2, (no sku)/3], stock {y:7}</li>
     * <li>c: every collection EMPTY</li>
     * <li>d: every collection NULL (absent in MongoDB)</li>
     * <li>e: tags [x], scores [30], lines [A/(no qty)], stock {x:0}</li>
     * <li>f: tags [z,x,"5"], scores [20,10], lines [(no sku)/(no qty)], stock {z:9}</li>
     * </ul>
     */
    private static ParityHarness seeded() {
        ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
        h.save(ITEMS,
                item("a", List.of("x", "y"), List.of(10, 20), List.of(line("A", 1, "n1"), line("B", 5)),
                        stock("x", 5, "y", 1)),
                item("b", List.of("y"), List.of(5), List.of(line("C", 2), line(null, 3)), stock("y", 7)),
                item("c", List.of(), List.of(), List.of(), stock()),
                item("d", null, null, null, null),
                item("e", List.of("x"), List.of(30), List.of(line("A", null)), stock("x", 0)),
                item("f", List.of("z", "x", "5"), List.of(20, 10), List.of(line(null, null)), stock("z", 9)));
        return h;
    }

    /**
     * Runs a filter on the standard seed and compares WHICH rows matched (their uuids), not the rows'
     * content: how a null collection reads back is a separate divergence ({@link RoundTrip}), and must
     * not mask what the filter itself selects.
     */
    private static void check(String what, IFilter filter) {
        ParityHarness h = seeded();
        ParityHarness.assertSame(what, uuids(h.find(ITEMS, filter)), false);
    }

    /** The same outcome, each returned Item reduced to its uuid. */
    private static Outcome uuids(Outcome o) {
        return new Outcome(ids(o.mongo()), ids(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object ids(Object result) {
        if (!(result instanceof List<?> list)) {
            return result;
        }
        List<Object> out = new ArrayList<>();
        for (Object row : list) {
            out.add(row instanceof Item i ? i.uuid : row);
        }
        return out;
    }

    // ------------------------------------------------------------------ List<String>

    @Nested
    @DisplayName("List<String> — element semantics")
    class StringList {

        @Test
        @DisplayName("$eq x matches when ANY element is x")
        void eqAnyElement() {
            check("tags $eq x", field("tags", "$eq", "x"));
        }

        @Test
        @DisplayName("$ne x matches when NO element is x (absent and empty included)")
        void neNoElement() {
            check("tags $ne x", field("tags", "$ne", "x"));
        }

        @Test
        @DisplayName("$in [x,z] matches when any element is listed")
        void inAnyElement() {
            check("tags $in [x,z]", listed("tags", "$in", "x", "z"));
        }

        @Test
        @DisplayName("$nin [x] matches when no element is listed (absent and empty included)")
        void ninNoElement() {
            check("tags $nin [x]", listed("tags", "$nin", "x"));
        }

        @Test
        @DisplayName("$gt x matches when any element sorts after x")
        void gtAnyElement() {
            check("tags $gt x", field("tags", "$gt", "x"));
        }

        @Test
        @DisplayName("$regex ^z matches when any element matches")
        void regexAnyElement() {
            check("tags $regex ^z", field("tags", "$regex", "^z"));
        }

        @Test
        @DisplayName("$eq with an Integer on List<String> (client sent 5, element is \"5\")")
        void eqMistypedInteger() {
            check("tags $eq 5 (Integer)", field("tags", "$eq", 5));
        }

        @Test
        @DisplayName("$and of two element conditions may be satisfied by different elements")
        void andAcrossElements() {
            check("tags = x AND tags = z",
                    logical("$and", field("tags", "$eq", "x"), field("tags", "$eq", "z")));
        }

        @Test
        @DisplayName("$nor [tags = x, tags = y]")
        void norOnArray() {
            check("$nor tags = x / tags = y",
                    logical("$nor", field("tags", "$eq", "x"), field("tags", "$eq", "y")));
        }
    }

    @Nested
    @DisplayName("List<String> — whole-array equality")
    class WholeArray {

        @Test
        @DisplayName("$eq [x,y] (a List value) is an exact array match on MongoDB")
        void eqExactArray() {
            check("tags $eq [x,y]", field("tags", "$eq", List.of("x", "y")));
        }

        @Test
        @DisplayName("$eq [y,x] — element order matters for an exact array match")
        void eqExactArrayOtherOrder() {
            check("tags $eq [y,x]", field("tags", "$eq", List.of("y", "x")));
        }

        @Test
        @DisplayName("$eq [] matches the empty array only")
        void eqEmptyArray() {
            check("tags $eq []", field("tags", "$eq", List.of()));
        }
    }

    @Nested
    @DisplayName("List<String> — null, absent, empty")
    class NullVsEmpty {

        @Test
        @DisplayName("$empty: MongoDB = field absent (null list), NOT the empty list")
        void emptyOperator() {
            check("tags $empty", field("tags", "$empty", null));
        }

        @Test
        @DisplayName("$eq null: MongoDB = absent (or a null element), NOT the empty list")
        void eqNull() {
            check("tags $eq null", field("tags", "$eq", null));
        }

        @Test
        @DisplayName("$ne null: MongoDB = present, the empty list included")
        void neNull() {
            check("tags $ne null", field("tags", "$ne", null));
        }

        @Test
        @DisplayName("$in [null, y]")
        void inWithNull() {
            check("tags $in [null,y]", listed("tags", "$in", null, "y"));
        }

        @Test
        @DisplayName("$nin [null]")
        void ninNull() {
            check("tags $nin [null]", listed("tags", "$nin", (Object) null));
        }

        @Test
        @DisplayName("$gte null behaves like $eq null")
        void gteNull() {
            check("tags $gte null", field("tags", "$gte", null));
        }

        @Test
        @DisplayName("count with $eq null agrees with find")
        void countEqNull() {
            ParityHarness h = seeded();
            ParityHarness.assertSame("count tags $eq null", h.count(ITEMS, field("tags", "$eq", null)), false);
        }
    }

    // ------------------------------------------------------------------ List<Integer>

    @Nested
    @DisplayName("List<Integer>")
    class IntegerList {

        @Test
        @DisplayName("$gt 15 matches when any element is greater")
        void gtTyped() {
            check("scores $gt 15", field("scores", "$gt", 15));
        }

        @Test
        @DisplayName("$gt \"15\" (String sent by the client) against integers")
        void gtMistyped() {
            check("scores $gt \"15\"", field("scores", "$gt", "15"));
        }

        @Test
        @DisplayName("$eq \"20\" (String) against integers")
        void eqMistyped() {
            check("scores $eq \"20\"", field("scores", "$eq", "20"));
        }

        @Test
        @DisplayName("$in [\"10\", 30] mixing a String and an Integer")
        void inMixedTypes() {
            check("scores $in [\"10\",30]", listed("scores", "$in", "10", 30));
        }

        @Test
        @DisplayName("$gt 15 AND $lt 15 — satisfied by two different elements")
        void rangeAcrossElements() {
            check("scores > 15 AND scores < 15",
                    logical("$and", field("scores", "$gt", 15), field("scores", "$lt", 15)));
        }

        @Test
        @DisplayName("$regex on integers")
        void regexOnIntegers() {
            check("scores $regex ^1", field("scores", "$regex", "^1"));
        }

        @Test
        @DisplayName("$eq with a non-numeric String against integers")
        void eqNonNumericString() {
            check("scores $eq \"abc\"", field("scores", "$eq", "abc"));
        }
    }

    // ------------------------------------------------------------------ List<POJO>

    @Nested
    @DisplayName("List<POJO> — dotted element paths")
    class PojoList {

        @Test
        @DisplayName("lines.sku $eq A")
        void skuEq() {
            check("lines.sku $eq A", field("lines.sku", "$eq", "A"));
        }

        @Test
        @DisplayName("lines.sku $ne A")
        void skuNe() {
            check("lines.sku $ne A", field("lines.sku", "$ne", "A"));
        }

        @Test
        @DisplayName("lines.sku $eq null — elements lacking sku")
        void skuEqNull() {
            check("lines.sku $eq null", field("lines.sku", "$eq", null));
        }

        @Test
        @DisplayName("lines.sku $ne null")
        void skuNeNull() {
            check("lines.sku $ne null", field("lines.sku", "$ne", null));
        }

        @Test
        @DisplayName("lines.sku $empty")
        void skuEmpty() {
            check("lines.sku $empty", field("lines.sku", "$empty", null));
        }

        @Test
        @DisplayName("lines.qty $gt 4")
        void qtyGt() {
            check("lines.qty $gt 4", field("lines.qty", "$gt", 4));
        }

        @Test
        @DisplayName("lines.qty $gt \"4\" (String)")
        void qtyGtMistyped() {
            check("lines.qty $gt \"4\"", field("lines.qty", "$gt", "4"));
        }

        @Test
        @DisplayName("lines.sku = A AND lines.qty = 5 — no $elemMatch: different elements may satisfy each")
        void andAcrossPojoElements() {
            check("lines.sku=A AND lines.qty=5",
                    logical("$and", field("lines.sku", "$eq", "A"), field("lines.qty", "$eq", 5)));
        }

        @Test
        @DisplayName("lines $empty (the whole list)")
        void linesEmpty() {
            check("lines $empty", field("lines", "$empty", null));
        }

        @Test
        @DisplayName("lines $eq null (the whole list)")
        void linesEqNull() {
            check("lines $eq null", field("lines", "$eq", null));
        }

        @Test
        @DisplayName("lines $ne null (the whole list)")
        void linesNeNull() {
            check("lines $ne null", field("lines", "$ne", null));
        }

        @Test
        @DisplayName("lines.notes $eq n1 — a list inside a list element")
        void nestedListInElement() {
            check("lines.notes $eq n1", field("lines.notes", "$eq", "n1"));
        }
    }

    // ------------------------------------------------------------------ Map<String,Integer>

    @Nested
    @DisplayName("Map<String,Integer> — dotted key paths")
    class IntMap {

        @Test
        @DisplayName("stock.x $eq 5")
        void keyEq() {
            check("stock.x $eq 5", field("stock.x", "$eq", 5));
        }

        @Test
        @DisplayName("stock.x $gt 0")
        void keyGt() {
            check("stock.x $gt 0", field("stock.x", "$gt", 0));
        }

        @Test
        @DisplayName("stock.x $gt \"0\" (String)")
        void keyGtMistyped() {
            check("stock.x $gt \"0\"", field("stock.x", "$gt", "0"));
        }

        @Test
        @DisplayName("stock.x $ne 5 — rows without the key match")
        void keyNe() {
            check("stock.x $ne 5", field("stock.x", "$ne", 5));
        }

        @Test
        @DisplayName("stock.x $empty — key absent")
        void keyEmpty() {
            check("stock.x $empty", field("stock.x", "$empty", null));
        }

        @Test
        @DisplayName("stock.x $eq null — key absent")
        void keyEqNull() {
            check("stock.x $eq null", field("stock.x", "$eq", null));
        }

        @Test
        @DisplayName("stock $empty — MongoDB: map absent, NOT the empty map")
        void mapEmpty() {
            check("stock $empty", field("stock", "$empty", null));
        }

        @Test
        @DisplayName("stock $ne null — MongoDB: map present, empty map included")
        void mapNeNull() {
            check("stock $ne null", field("stock", "$ne", null));
        }
    }

    // ------------------------------------------------------------------ what find() returns

    @Nested
    @DisplayName("Round trip — what find() returns")
    class RoundTrip {

        @Test
        @DisplayName("every collection of every seeded row reads back the same (null vs [] vs {})")
        void findAll() {
            ParityHarness h = seeded();
            ParityHarness.assertSame("find all", h.find(ITEMS, null), false);
        }

        @Test
        @DisplayName("a null list reads back as null, an empty list as []")
        void nullVsEmptyList() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            h.save(ITEMS, item("nul", null, null, null, null), item("emp", List.of(), List.of(), List.of(), stock()));
            ParityHarness.assertSame("null vs empty", h.find(ITEMS, null), false);
        }

        @Test
        @DisplayName("element order survives the round trip")
        void orderPreserved() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            h.save(ITEMS, item("o", List.of("z", "a", "m", "a"), List.of(3, 1, 2, 1),
                    List.of(line("Z", 9), line("A", 1)), stock("z", 1, "a", 2, "m", 3)));
            ParityHarness.assertSame("order", h.find(ITEMS, null), true);
        }

        @Test
        @DisplayName("a POJO element whose fields are all null reads back as an empty object, not null")
        void allNullPojoElement() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            h.save(ITEMS, item("p", null, null, List.of(line(null, null), line("A", 1)), null));
            ParityHarness.assertSame("all-null POJO element", h.find(ITEMS, field("uuid", "$eq", "p")), true);
        }

        @Test
        @DisplayName("filtering on a field of an all-null POJO element")
        void allNullPojoElementFilter() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            h.save(ITEMS, item("p", List.of("t"), null, List.of(line(null, null)), null),
                    item("q", List.of("t"), null, List.of(line("A", null)), null),
                    item("r", List.of("t"), null, List.of(line("B", 2)), null));
            ParityHarness.assertSame("lines.qty $eq null",
                    uuids(h.find(ITEMS, field("lines.qty", "$eq", null))), false);
        }

        @Test
        @DisplayName("a list holding a null element")
        void nullElement() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            Item i = item("n", null, null, null, null);
            i.tags = new ArrayList<>(Arrays.asList("x", null, "y"));
            h.save(ITEMS, i);
            ParityHarness.assertSame("null element", h.find(ITEMS, null), true);
        }

        @Test
        @DisplayName("a map holding a null value")
        void nullMapValue() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            Item i = item("m", null, null, null, null);
            i.stock = stock("x", null, "y", 1);
            h.save(ITEMS, i);
            ParityHarness.assertSame("null map value", h.find(ITEMS, null), true);
        }

        @Test
        @DisplayName("a Set<String> field reads back")
        void setRoundTrip() {
            ParityHarness h = ParityHarness.of(Domain.of("labelled", Labelled.class));
            Labelled l = new Labelled();
            l.uuid = "s";
            l.labels = new LinkedHashSet<>(List.of("b", "a", "c"));
            Labelled empty = new Labelled();
            empty.uuid = "e";
            empty.labels = new LinkedHashSet<>();
            h.save("labelled", l, empty);
            ParityHarness.assertSame("set round trip", h.find("labelled", null), false);
        }

        @Test
        @DisplayName("a Set<String> field can be filtered by element")
        void setFilter() {
            ParityHarness h = ParityHarness.of(Domain.of("labelled", Labelled.class));
            Labelled l = new Labelled();
            l.uuid = "s";
            l.labels = new LinkedHashSet<>(List.of("b", "a"));
            Labelled other = new Labelled();
            other.uuid = "o";
            other.labels = new LinkedHashSet<>(List.of("c"));
            h.save("labelled", l, other);
            ParityHarness.assertSame("set count labels=a", h.count("labelled", field("labels", "$eq", "a")), false);
        }

        @Test
        @DisplayName("a String[] field reads back")
        void arrayRoundTrip() {
            ParityHarness h = ParityHarness.of(Domain.of("arrayed", Arrayed.class));
            Arrayed a = new Arrayed();
            a.uuid = "a";
            a.words = new String[] { "q", "p" };
            h.save("arrayed", a);
            ParityHarness.assertSame("String[] round trip", h.find("arrayed", null), false);
        }

        @Test
        @DisplayName("a projection that leaves the collections out")
        void projectionWithoutCollections() {
            ParityHarness h = seeded();
            Outcome o = h.find(ITEMS, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of("uuid", "name")));
            ParityHarness.assertSame("projection uuid,name", o, false);
        }

        @Test
        @DisplayName("a projection on one collection only")
        void projectionOneCollection() {
            ParityHarness h = seeded();
            Outcome o = h.find(ITEMS, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(List.of("uuid", "tags")));
            ParityHarness.assertSame("projection uuid,tags", o, false);
        }

        @Test
        @DisplayName("sorting on an array field")
        void sortOnArray() {
            ParityHarness h = seeded();
            Outcome o = h.find(ITEMS, Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("scores", SortDirection.asc)), Optional.empty());
            ParityHarness.assertSame("sort scores asc", uuids(o), true);
        }

        @Test
        @DisplayName("re-saving with a shorter list replaces the elements")
        void resaveShorterList() {
            ParityHarness h = ParityHarness.of(Domain.of(ITEMS, Item.class));
            h.save(ITEMS, item("r", List.of("x", "y", "z"), List.of(1, 2), null, null));
            h.save(ITEMS, item("r", List.of("y"), List.of(), null, null));
            ParityHarness.assertSame("resave", uuids(h.find(ITEMS, field("tags", "$eq", "x"))), false);
            ParityHarness.assertSame("resave read", h.find(ITEMS, null), false);
        }
    }
}
