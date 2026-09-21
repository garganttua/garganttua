package com.garganttua.dao.postgresql.parity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.sort.SortDirection;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.postgresql.TestDomains;
import com.garganttua.dao.postgresql.parity.ParityHarness.Domain;
import com.garganttua.dao.postgresql.parity.ParityHarness.Outcome;

/**
 * Class SHAPES nested inside a DTO: inheritance, polymorphism, abstract and interface types, inner
 * classes, records, enums with fields, classes without a no-arg constructor, the same class used
 * several times, flattened-name collisions, names beyond PostgreSQL's 63 bytes, generic embedded
 * types, transient and static fields at nested levels.
 *
 * <p>
 * Every scenario does the same thing on MongoDB and on PostgreSQL and demands the same answer. The
 * answer is compared as a TYPED deep description ({@link ParityTypesCompositionTest#describe}) so the
 * runtime class of each embedded object is part of it — a {@code Dog} read back as an {@code Animal}
 * must be read back as an {@code Animal} on both engines. Failing on both engines is agreement.
 * </p>
 *
 * <p>
 * This is discovery: each scenario also prints where PostgreSQL stored the shape
 * ({@code [STORAGE]} lines, from {@code information_schema}) and what each engine answered
 * ({@code [OUTCOME]} lines), so the report can say per nesting level "flattened column", "child
 * table" or "JSONB".
 * </p>
 */
@DisplayName("Parity — class shapes nested in a DTO")
class ParityNestedShapesTest {

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    // ================================================================== DTOs

    /** A base DTO carrying the uuid — the persisted DTO extends it. */
    public static class BaseEntity {
        String uuid;
        String tenant;
        Integer version;
    }

    public static class Article extends BaseEntity {
        String title;
    }

    public static class Location {
        String country;
    }

    public static class Coords {
        Double lat;
        Double lng;
    }

    /** An embedded POJO extending another class, with transient and static fields. */
    public static class Address extends Location {
        static String shared = "never-stored";
        String city;
        Integer zip;
        Coords coords;
        transient String cache;
    }

    /** The same class three times: two embedded fields and a list. */
    public static class Person {
        String uuid;
        String name;
        Address home;
        Address work;
        List<Address> previous;
    }

    public static class Animal {
        String name;
        Integer legs;
    }

    public static class Dog extends Animal {
        String breed;
    }

    public static class Cat extends Animal {
        Boolean indoor;
    }

    /** A field declared as a base class, and a list of the base class, holding subclasses. */
    public static class Owner {
        String uuid;
        String label;
        Animal pet;
        List<Animal> pets;
    }

    public abstract static class Shape {
        String label;
    }

    public static class Circle extends Shape {
        Double radius;
    }

    public static class Drawing {
        String uuid;
        String title;
        Shape shape;
    }

    public interface Named {
        String name();
    }

    public static class NamedImpl implements Named {
        String name;

        @Override
        public String name() {
            return name;
        }
    }

    public static class Tagged {
        String uuid;
        String title;
        Named named;
    }

    public static class StaticPart {
        String code;
        Integer size;
    }

    public static class Machine {
        String uuid;
        StaticPart part;
    }

    /** Holds a NON-STATIC inner class: an instance needs an enclosing {@code Holder}. */
    public static class Holder {
        String owner = "holder";

        public class InnerPart {
            String code;
            Integer size;
        }
    }

    public static class Gadget {
        String uuid;
        String label;
        Holder.InnerPart part;
    }

    public record Point(Integer x, Integer y) {
    }

    public static class Place {
        String uuid;
        String name;
        Point at;
    }

    public enum Planet {
        MERCURY(3.3e23, "grey"), EARTH(5.97e24, "blue"), MARS(6.42e23, "red");

        final double mass;
        final String color;

        Planet(double mass, String color) {
            this.mass = mass;
            this.color = color;
        }
    }

    public static class Probe {
        String uuid;
        Planet target;
        List<Planet> visited;
    }

    /** No no-arg constructor. */
    public static class Money {
        String currency;
        Long cents;

        public Money(String currency, Long cents) {
            this.currency = currency;
            this.cents = cents;
        }
    }

    public static class Invoice {
        String uuid;
        String ref;
        Money total;
    }

    public static class CityOnly {
        String city;
    }

    /** {@code home.city} flattens to {@code home__city} — the name of the other field. */
    public static class Clash {
        String uuid;
        CityOnly home;
        String home__city;
    }

    public static class ThirdLevelWithAVeryLongClassName {
        String leafValueAlphaWithAnUnreasonablyLongName;
        String leafValueBetaWithAnUnreasonablyLongName;
        Integer leafRankingNumberWithAnUnreasonablyLongName;
    }

    public static class SecondLevel {
        ThirdLevelWithAVeryLongClassName thirdLevelContainerAlsoRatherLongName;
    }

    public static class FirstLevel {
        SecondLevel secondLevelContainerWithAnotherLongName;
    }

    /** Four levels of long names: every flattened column is far beyond 63 bytes. */
    public static class Deep {
        String uuid;
        FirstLevel firstLevelContainerWithAVeryLongDescriptiveName;
        List<ThirdLevelWithAVeryLongClassName> collectionOfThirdLevelItemsWithAnExcessivelyLongName;
    }

    public static class Wrapper<T> {
        String label;
        T value;
    }

    public static class Boxes {
        String uuid;
        Wrapper<String> text;
        Wrapper<Integer> number;
        Wrapper<StaticPart> part;
    }

    // ================================================================== helpers

    private static <T> T with(T dto, Consumer<T> init) {
        init.accept(dto);
        return dto;
    }

    /** Both answers replaced by their typed deep description; errors kept. */
    private static Outcome typed(Outcome o) {
        return new Outcome(describeResult(o.mongo()), describeResult(o.pg()), o.mongoError(), o.pgError());
    }

    private static Object describeResult(Object result) {
        if (result instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(ParityTypesCompositionTest.describe(item));
            }
            return out;
        }
        return result == null ? null : ParityTypesCompositionTest.describe(result);
    }

    /**
     * A sorted answer as runs of equal sort keys: the order of the runs is the answer, the order
     * WITHIN a run (ties, including several nulls) is not.
     */
    private static Outcome grouped(Outcome o, Function<Object, Object> key) {
        return new Outcome(groups(o.mongo(), key), groups(o.pg(), key), o.mongoError(), o.pgError());
    }

    private static Object groups(Object result, Function<Object, Object> key) {
        if (!(result instanceof List<?> list)) {
            return result;
        }
        List<List<String>> runs = new ArrayList<>();
        Object previous = new Object();
        for (Object item : list) {
            Object k = key.apply(item);
            if (runs.isEmpty() || !java.util.Objects.equals(k, previous)) {
                runs.add(new ArrayList<>());
            }
            previous = k;
            runs.get(runs.size() - 1).add(ParityTypesCompositionTest.describe(item));
        }
        runs.forEach(r -> r.sort(String::compareTo));
        return runs;
    }

    private static Outcome all(ParityHarness h, String domain) {
        return typed(h.find(domain, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static Outcome where(ParityHarness h, String domain, IFilter filter) {
        return typed(h.find(domain, filter));
    }

    private static Outcome sorted(ParityHarness h, String domain, String field, SortDirection dir,
            Function<Object, Object> key) {
        return grouped(h.find(domain, Optional.empty(), Optional.empty(),
                Optional.of(ParityFilter.sort(field, dir)), Optional.empty()), key);
    }

    /** Prints both answers, then asserts they are the same. */
    private static void check(String what, Outcome o, boolean ordered) {
        System.out.println("[OUTCOME] " + what + "\n    MongoDB:    " + show(o.mongo(), o.mongoError())
                + "\n    PostgreSQL: " + show(o.pg(), o.pgError()));
        ParityHarness.assertSame(what, o, ordered);
    }

    private static String show(Object value, Throwable error) {
        if (error == null) {
            return String.valueOf(value);
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return "THREW " + error.getClass().getSimpleName() + ": " + error.getMessage()
                + (root == error ? "" : " / root " + root.getClass().getSimpleName() + ": " + root.getMessage());
    }

    /** Prints every table and column PostgreSQL created for the scenario, in the current schema. */
    private static void storage(String shape, ParityHarness h) {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        String sql = "SELECT table_name, column_name, data_type, udt_name FROM information_schema.columns"
                + " WHERE table_schema = current_schema() ORDER BY table_name, ordinal_position";
        try (Connection c = h.pgDatabase().getConnection(); PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = "USER-DEFINED".equals(rs.getString(3)) ? rs.getString(4) : rs.getString(3);
                tables.computeIfAbsent(rs.getString(1), t -> new ArrayList<>())
                        .add(rs.getString(2) + " " + type);
            }
        } catch (SQLException e) {
            System.out.println("[STORAGE] " + shape + " — cannot inspect: " + e.getMessage());
            return;
        }
        StringBuilder out = new StringBuilder("[STORAGE] " + shape);
        tables.forEach((t, cols) -> out.append("\n    ").append(t).append(": ").append(String.join(", ", cols)));
        System.out.println(out);
    }

    // ================================================================== fixtures

    private static Address address(String country, String city, Integer zip, Double lat) {
        return with(new Address(), a -> {
            a.country = country;
            a.city = city;
            a.zip = zip;
            a.cache = "transient-" + city;
            if (lat != null) {
                a.coords = with(new Coords(), c -> {
                    c.lat = lat;
                    c.lng = -lat;
                });
            }
        });
    }

    private static ParityHarness people() {
        ParityHarness h = ParityHarness.of(Domain.of("persons", Person.class));
        h.save("persons",
                with(new Person(), p -> {
                    p.uuid = "p1";
                    p.name = "Ann";
                    p.home = address("FR", "Lyon", 69000, 45.7);
                    p.work = address("FR", "Paris", 75000, null);
                    p.previous = new ArrayList<>(List.of(address("BE", "Liege", 4000, 50.6),
                            address(null, "Nice", null, null)));
                }),
                with(new Person(), p -> {
                    p.uuid = "p2";
                    p.name = "Bob";
                    p.home = address("DE", "Berlin", 10115, 52.5);
                    p.work = new Address(); // present, every field null
                    p.previous = new ArrayList<>();
                }),
                with(new Person(), p -> {
                    p.uuid = "p3";
                    p.name = "Cid";
                    p.home = null;
                    p.work = address("FR", "Lyon", 69001, null);
                    p.previous = null;
                }),
                with(new Person(), p -> {
                    p.uuid = "p4";
                    p.name = "Dan";
                    p.home = address("IT", null, 100, null);
                    p.work = null;
                    p.previous = new ArrayList<>(java.util.Arrays.asList(address("FR", "Lyon", 69002, 45.8), null));
                }));
        return h;
    }

    private static Object homeCity(Object p) {
        Person person = (Person) p;
        return person.home == null ? null : person.home.city;
    }

    private static ParityHarness owners() {
        ParityHarness h = ParityHarness.of(Domain.of("owners", Owner.class));
        Dog rex = with(new Dog(), d -> {
            d.name = "Rex";
            d.legs = 4;
            d.breed = "collie";
        });
        Cat tom = with(new Cat(), c -> {
            c.name = "Tom";
            c.legs = 3;
            c.indoor = true;
        });
        Animal plain = with(new Animal(), a -> {
            a.name = "Blob";
            a.legs = 0;
        });
        h.save("owners",
                with(new Owner(), o -> {
                    o.uuid = "o1";
                    o.label = "dog owner";
                    o.pet = rex;
                    o.pets = new ArrayList<>(List.of(rex, tom));
                }),
                with(new Owner(), o -> {
                    o.uuid = "o2";
                    o.label = "cat owner";
                    o.pet = tom;
                    o.pets = new ArrayList<>(List.of(plain));
                }),
                with(new Owner(), o -> {
                    o.uuid = "o3";
                    o.label = "second dog owner";
                    o.pet = with(new Dog(), d -> {
                        d.name = "Fido";
                        d.legs = 2;
                        d.breed = "akita";
                    });
                    o.pets = new ArrayList<>();
                }),
                with(new Owner(), o -> {
                    o.uuid = "o4";
                    o.label = "nobody";
                    o.pet = null;
                    o.pets = null;
                }));
        return h;
    }

    private static ThirdLevelWithAVeryLongClassName third(String alpha, String beta, Integer rank) {
        return with(new ThirdLevelWithAVeryLongClassName(), t -> {
            t.leafValueAlphaWithAnUnreasonablyLongName = alpha;
            t.leafValueBetaWithAnUnreasonablyLongName = beta;
            t.leafRankingNumberWithAnUnreasonablyLongName = rank;
        });
    }

    private static Deep deep(String uuid, ThirdLevelWithAVeryLongClassName leaf, boolean withFirst,
            boolean withSecond, List<ThirdLevelWithAVeryLongClassName> items) {
        return with(new Deep(), d -> {
            d.uuid = uuid;
            if (withFirst) {
                d.firstLevelContainerWithAVeryLongDescriptiveName = with(new FirstLevel(), f -> {
                    if (withSecond) {
                        f.secondLevelContainerWithAnotherLongName = with(new SecondLevel(),
                                s -> s.thirdLevelContainerAlsoRatherLongName = leaf);
                    }
                });
            }
            d.collectionOfThirdLevelItemsWithAnExcessivelyLongName = items;
        });
    }

    private static final String LEAF = "firstLevelContainerWithAVeryLongDescriptiveName"
            + ".secondLevelContainerWithAnotherLongName.thirdLevelContainerAlsoRatherLongName.";

    private static ParityHarness deeps() {
        ParityHarness h = ParityHarness.of(Domain.of("deeps", Deep.class));
        h.save("deeps",
                deep("d1", third("same", "one", 3), true, true, new ArrayList<>(List.of(third("x", "y", 1)))),
                deep("d2", third("one", "same", 1), true, true, new ArrayList<>()),
                deep("d3", third(null, null, null), true, true, null),
                deep("d4", null, true, true, null),
                deep("d5", null, true, false, new ArrayList<>(List.of(third("same", "z", 9), third("q", "same", 2)))),
                deep("d6", null, false, false, null),
                deep("d7", third("two", "two", 2), true, true, null));
        return h;
    }

    private static Object deepRank(Object d) {
        Deep deep = (Deep) d;
        if (deep.firstLevelContainerWithAVeryLongDescriptiveName == null
                || deep.firstLevelContainerWithAVeryLongDescriptiveName.secondLevelContainerWithAnotherLongName == null
                || deep.firstLevelContainerWithAVeryLongDescriptiveName.secondLevelContainerWithAnotherLongName
                        .thirdLevelContainerAlsoRatherLongName == null) {
            return null;
        }
        return deep.firstLevelContainerWithAVeryLongDescriptiveName.secondLevelContainerWithAnotherLongName
                .thirdLevelContainerAlsoRatherLongName.leafRankingNumberWithAnUnreasonablyLongName;
    }

    // ================================================================== scenarios

    @Nested
    @DisplayName("a DTO extending a base DTO")
    class DtoInheritance {

        private ParityHarness articles() {
            ParityHarness h = ParityHarness.of(Domain.of("articles", Article.class));
            h.save("articles",
                    with(new Article(), a -> {
                        a.uuid = "a1";
                        a.tenant = "t1";
                        a.version = 3;
                        a.title = "first";
                    }),
                    with(new Article(), a -> {
                        a.uuid = "a2";
                        a.tenant = "t2";
                        a.version = 1;
                        a.title = "second";
                    }),
                    with(new Article(), a -> {
                        a.uuid = "a3";
                        a.tenant = null;
                        a.version = 2;
                        a.title = null;
                    }));
            storage("Article extends BaseEntity", h);
            return h;
        }

        @Test
        @DisplayName("round trip keeps the superclass fields")
        void roundTrip() {
            check("Article round trip", all(articles(), "articles"), false);
        }

        @Test
        @DisplayName("filter on a superclass field")
        void filterSuperField() {
            ParityHarness h = articles();
            check("tenant = t1", where(h, "articles", ParityFilter.field("tenant", "$eq", "t1")), false);
            check("tenant = null", where(h, "articles", ParityFilter.field("tenant", "$eq", null)), false);
        }

        @Test
        @DisplayName("sort on a superclass field")
        void sortSuperField() {
            check("sort version desc", sorted(articles(), "articles", "version", SortDirection.desc,
                    a -> ((Article) a).version), true);
        }
    }

    @Nested
    @DisplayName("the same embedded class used twice and in a list (Address extends Location)")
    class SameClassTwice {

        @Test
        @DisplayName("round trip: home, work (present but empty), previous (list with a null element)")
        void roundTrip() {
            ParityHarness h = people();
            storage("Person{Address home, Address work, List<Address> previous}", h);
            check("Person round trip", all(h, "persons"), false);
        }

        @Test
        @DisplayName("filter home.city and work.city do not collide")
        void filterEachUse() {
            ParityHarness h = people();
            check("home.city = Lyon", where(h, "persons", ParityFilter.field("home.city", "$eq", "Lyon")), false);
            check("work.city = Lyon", where(h, "persons", ParityFilter.field("work.city", "$eq", "Lyon")), false);
            check("previous.city = Lyon", where(h, "persons", ParityFilter.field("previous.city", "$eq", "Lyon")),
                    false);
        }

        @Test
        @DisplayName("filter on the embedded class's SUPERCLASS field (home.country, previous.country)")
        void filterInheritedEmbeddedField() {
            ParityHarness h = people();
            check("home.country = FR", where(h, "persons", ParityFilter.field("home.country", "$eq", "FR")), false);
            check("work.country = FR", where(h, "persons", ParityFilter.field("work.country", "$eq", "FR")), false);
            check("previous.country = BE",
                    where(h, "persons", ParityFilter.field("previous.country", "$eq", "BE")), false);
        }

        @Test
        @DisplayName("nulls at each level: home.city = null, work.city = null, home = null")
        void filterNulls() {
            ParityHarness h = people();
            check("home.city = null", where(h, "persons", ParityFilter.field("home.city", "$eq", null)), false);
            check("work.city = null", where(h, "persons", ParityFilter.field("work.city", "$eq", null)), false);
            check("home = null", where(h, "persons", ParityFilter.field("home", "$eq", null)), false);
            check("work.coords.lat = null",
                    where(h, "persons", ParityFilter.field("work.coords.lat", "$eq", null)), false);
        }

        @Test
        @DisplayName("third level: home.coords.lat and previous.coords.lat")
        void filterThirdLevel() {
            ParityHarness h = people();
            check("home.coords.lat > 50",
                    where(h, "persons", ParityFilter.field("home.coords.lat", "$gt", 50.0)), false);
            check("previous.coords.lat > 45",
                    where(h, "persons", ParityFilter.field("previous.coords.lat", "$gt", 45.0)), false);
        }

        @Test
        @DisplayName("empty collection vs null collection vs populated: previous $empty")
        void emptyList() {
            ParityHarness h = people();
            check("previous $empty true", where(h, "persons", ParityFilter.field("previous", "$empty", true)), false);
            check("previous $empty false",
                    where(h, "persons", ParityFilter.field("previous", "$empty", false)), false);
        }

        @Test
        @DisplayName("sort on home.city and on work.zip")
        void sort() {
            ParityHarness h = people();
            check("sort home.city asc", sorted(h, "persons", "home.city", SortDirection.asc,
                    ParityNestedShapesTest::homeCity), true);
            check("sort work.zip desc", sorted(h, "persons", "work.zip", SortDirection.desc, p -> {
                Person person = (Person) p;
                return person.work == null ? null : person.work.zip;
            }), true);
        }
    }

    @Nested
    @DisplayName("transient and static fields at nested levels")
    class TransientStatic {

        @Test
        @DisplayName("a transient field of an embedded POJO is never stored nor filterable")
        void transientNested() {
            ParityHarness h = people();
            check("home.cache = transient-Lyon",
                    where(h, "persons", ParityFilter.field("home.cache", "$eq", "transient-Lyon")), false);
            check("previous.cache = transient-Nice",
                    where(h, "persons", ParityFilter.field("previous.cache", "$eq", "transient-Nice")), false);
        }

        @Test
        @DisplayName("a static field of an embedded POJO is never stored nor filterable")
        void staticNested() {
            ParityHarness h = people();
            check("home.shared = never-stored",
                    where(h, "persons", ParityFilter.field("home.shared", "$eq", "never-stored")), false);
        }
    }

    @Nested
    @DisplayName("polymorphism: a base-class field holding a subclass")
    class Polymorphism {

        @Test
        @DisplayName("round trip: Dog/Cat in an Animal field and in a List<Animal>")
        void roundTrip() {
            ParityHarness h = owners();
            storage("Owner{Animal pet, List<Animal> pets}", h);
            check("Owner round trip (runtime class and extra fields)", all(h, "owners"), false);
        }

        @Test
        @DisplayName("filter on a base-class field: pet.name, pets.name")
        void filterBaseField() {
            ParityHarness h = owners();
            check("pet.name = Tom", where(h, "owners", ParityFilter.field("pet.name", "$eq", "Tom")), false);
            check("pets.name = Blob", where(h, "owners", ParityFilter.field("pets.name", "$eq", "Blob")), false);
        }

        @Test
        @DisplayName("filter on a SUBCLASS-only field: pet.breed")
        void filterSubclassField() {
            check("pet.breed = collie",
                    where(owners(), "owners", ParityFilter.field("pet.breed", "$eq", "collie")), false);
        }

        @Test
        @DisplayName("filter on a SUBCLASS-only field of a list element: pets.indoor")
        void filterSubclassFieldInList() {
            check("pets.indoor = true",
                    where(owners(), "owners", ParityFilter.field("pets.indoor", "$eq", true)), false);
        }

        @Test
        @DisplayName("sort on a base-class field: pet.legs")
        void sortBaseField() {
            check("sort pet.legs asc", sorted(owners(), "owners", "pet.legs", SortDirection.asc, o -> {
                Owner owner = (Owner) o;
                return owner.pet == null ? null : owner.pet.legs;
            }), true);
        }

        @Test
        @DisplayName("sort on a SUBCLASS-only field: pet.breed")
        void sortSubclassField() {
            // The breed is not readable back (the declared Animal has no such field): key by uuid.
            Map<String, String> breeds = Map.of("o1", "collie", "o3", "akita");
            check("sort pet.breed desc", sorted(owners(), "owners", "pet.breed", SortDirection.desc,
                    o -> breeds.get(((Owner) o).uuid)), true);
        }
    }

    @Nested
    @DisplayName("abstract and interface-typed fields")
    class AbstractTypes {

        private ParityHarness drawings(boolean withShape) {
            ParityHarness h = ParityHarness.of(Domain.of("drawings", Drawing.class));
            h.save("drawings", with(new Drawing(), d -> {
                d.uuid = "w1";
                d.title = "no shape";
            }));
            if (withShape) {
                h.save("drawings", with(new Drawing(), d -> {
                    d.uuid = "w2";
                    d.title = "circle";
                    d.shape = with(new Circle(), c -> {
                        c.label = "round";
                        c.radius = 2.5;
                    });
                }), with(new Drawing(), d -> {
                    d.uuid = "w3";
                    d.title = "big circle";
                    d.shape = with(new Circle(), c -> {
                        c.label = "huge";
                        c.radius = 10.0;
                    });
                }));
            }
            storage("Drawing{abstract Shape shape}", h);
            return h;
        }

        @Test
        @DisplayName("round trip of an abstract-typed field holding a Circle")
        void roundTrip() {
            check("Drawing round trip", all(drawings(true), "drawings"), false);
        }

        @Test
        @DisplayName("the rows WITHOUT a shape stay readable")
        void nullShapeReadable() {
            check("Drawing with null shape", all(drawings(false), "drawings"), false);
        }

        @Test
        @DisplayName("filter on the abstract field's inherited and concrete fields")
        void filter() {
            ParityHarness h = drawings(true);
            check("shape.radius > 5 (subclass field)",
                    where(h, "drawings", ParityFilter.field("shape.radius", "$gt", 5.0)), false);
            check("title = no shape (filter excludes the shape rows)",
                    where(h, "drawings", ParityFilter.field("title", "$eq", "no shape")), false);
            check("count shape.label = round", h.count("drawings", ParityFilter.field("shape.label", "$eq", "round")),
                    false);
        }

        @Test
        @DisplayName("sort on the abstract field's label (count via page 0 of titles)")
        void sort() {
            ParityHarness h = drawings(true);
            check("sort shape.label desc", typed(h.find("drawings", Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("shape.label", SortDirection.desc)), Optional.empty())), true);
        }

        @Test
        @DisplayName("round trip of an interface-typed field holding an implementation")
        void interfaceField() {
            ParityHarness h = ParityHarness.of(Domain.of("taggeds", Tagged.class));
            h.save("taggeds", with(new Tagged(), t -> {
                t.uuid = "g1";
                t.title = "impl";
                t.named = with(new NamedImpl(), n -> n.name = "alpha");
            }), with(new Tagged(), t -> {
                t.uuid = "g2";
                t.title = "none";
            }));
            storage("Tagged{interface Named named}", h);
            check("Tagged round trip", all(h, "taggeds"), false);
            check("named.name = alpha", h.count("taggeds", ParityFilter.field("named.name", "$eq", "alpha")), false);
        }
    }

    @Nested
    @DisplayName("static nested vs non-static inner class as a field type")
    class InnerClasses {

        @Test
        @DisplayName("a static nested class: round trip, filter, sort")
        void staticNested() {
            ParityHarness h = ParityHarness.of(Domain.of("machines", Machine.class));
            h.save("machines", with(new Machine(), m -> {
                m.uuid = "m1";
                m.part = with(new StaticPart(), p -> {
                    p.code = "B";
                    p.size = 2;
                });
            }), with(new Machine(), m -> {
                m.uuid = "m2";
                m.part = with(new StaticPart(), p -> {
                    p.code = "A";
                    p.size = 5;
                });
            }), with(new Machine(), m -> m.uuid = "m3"));
            storage("Machine{static nested StaticPart part}", h);
            check("Machine round trip", all(h, "machines"), false);
            check("part.size >= 2", where(h, "machines", ParityFilter.field("part.size", "$gte", 2)), false);
            check("sort part.code asc", sorted(h, "machines", "part.code", SortDirection.asc,
                    m -> ((Machine) m).part == null ? null : ((Machine) m).part.code), true);
        }

        private ParityHarness gadgets() {
            ParityHarness h = ParityHarness.of(Domain.of("gadgets", Gadget.class));
            Holder holder = new Holder();
            h.save("gadgets", with(new Gadget(), g -> {
                g.uuid = "x1";
                g.label = "with inner";
                g.part = holder.new InnerPart();
                g.part.code = "I";
                g.part.size = 7;
            }), with(new Gadget(), g -> {
                g.uuid = "x2";
                g.label = "without inner";
            }));
            storage("Gadget{non-static inner Holder.InnerPart part}", h);
            return h;
        }

        @Test
        @DisplayName("a NON-static inner class: round trip")
        void innerRoundTrip() {
            check("Gadget round trip", all(gadgets(), "gadgets"), false);
        }

        @Test
        @DisplayName("a NON-static inner class: filter on it, and a filter excluding it")
        void innerFilter() {
            ParityHarness h = gadgets();
            check("part.code = I", where(h, "gadgets", ParityFilter.field("part.code", "$eq", "I")), false);
            check("label = without inner",
                    where(h, "gadgets", ParityFilter.field("label", "$eq", "without inner")), false);
            check("count part.size = 7", h.count("gadgets", ParityFilter.field("part.size", "$eq", 7)), false);
        }

        @Test
        @DisplayName("a NON-static inner class: the synthetic outer reference is not a field (this$0)")
        void innerSyntheticOuter() {
            check("count this$0.owner = holder",
                    gadgets().count("gadgets", ParityFilter.field("part.this$0.owner", "$eq", "holder")), false);
        }

        @Test
        @DisplayName("a NON-static inner class: sort on it")
        void innerSort() {
            check("sort part.size", typed(gadgets().find("gadgets", Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("part.size", SortDirection.asc)), Optional.empty())), true);
        }
    }

    @Nested
    @DisplayName("records, enums with fields, classes without a no-arg constructor")
    class ValueTypes {

        private ParityHarness places() {
            ParityHarness h = ParityHarness.of(Domain.of("places", Place.class));
            h.save("places", with(new Place(), p -> {
                p.uuid = "l1";
                p.name = "origin";
                p.at = new Point(0, 0);
            }), with(new Place(), p -> {
                p.uuid = "l2";
                p.name = "far";
                p.at = new Point(10, -3);
            }), with(new Place(), p -> {
                p.uuid = "l3";
                p.name = "nowhere";
            }));
            storage("Place{record Point at}", h);
            return h;
        }

        @Test
        @DisplayName("a record as an embedded type: round trip")
        void recordRoundTrip() {
            check("Place round trip", all(places(), "places"), false);
        }

        @Test
        @DisplayName("a record: filter (matching the record rows, and matching only the null row)")
        void recordFilter() {
            ParityHarness h = places();
            check("count at.x = 10", h.count("places", ParityFilter.field("at.x", "$eq", 10)), false);
            check("find at.x = 10", where(h, "places", ParityFilter.field("at.x", "$eq", 10)), false);
            check("find name = nowhere", where(h, "places", ParityFilter.field("name", "$eq", "nowhere")), false);
        }

        @Test
        @DisplayName("a record: sort on a component")
        void recordSort() {
            check("sort at.y", typed(places().find("places", Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("at.y", SortDirection.asc)), Optional.empty())), true);
        }

        @Test
        @DisplayName("an enum with fields: stored by name, filter and sort by name")
        void enumWithFields() {
            ParityHarness h = ParityHarness.of(Domain.of("probes", Probe.class));
            h.save("probes", with(new Probe(), p -> {
                p.uuid = "r1";
                p.target = Planet.MARS;
                p.visited = new ArrayList<>(List.of(Planet.EARTH, Planet.MERCURY));
            }), with(new Probe(), p -> {
                p.uuid = "r2";
                p.target = Planet.EARTH;
                p.visited = new ArrayList<>();
            }), with(new Probe(), p -> {
                p.uuid = "r3";
                p.target = null;
                p.visited = null;
            }));
            storage("Probe{enum-with-fields Planet target, List<Planet> visited}", h);
            check("Probe round trip", all(h, "probes"), false);
            check("target = EARTH", where(h, "probes", ParityFilter.field("target", "$eq", "EARTH")), false);
            check("visited = MERCURY", where(h, "probes", ParityFilter.field("visited", "$eq", "MERCURY")), false);
            check("sort target desc", sorted(h, "probes", "target", SortDirection.desc,
                    p -> ((Probe) p).target), true);
        }

        private ParityHarness invoices() {
            ParityHarness h = ParityHarness.of(Domain.of("invoices", Invoice.class));
            h.save("invoices", with(new Invoice(), i -> {
                i.uuid = "v1";
                i.ref = "eur";
                i.total = new Money("EUR", 1250L);
            }), with(new Invoice(), i -> {
                i.uuid = "v2";
                i.ref = "usd";
                i.total = new Money("USD", 99L);
            }), with(new Invoice(), i -> {
                i.uuid = "v3";
                i.ref = "none";
            }));
            storage("Invoice{Money total (no no-arg constructor)}", h);
            return h;
        }

        @Test
        @DisplayName("a class without a no-arg constructor: round trip")
        void noArgLessRoundTrip() {
            check("Invoice round trip", all(invoices(), "invoices"), false);
        }

        @Test
        @DisplayName("a class without a no-arg constructor: filter and sort")
        void noArgLessFilterSort() {
            ParityHarness h = invoices();
            check("count total.currency = USD", h.count("invoices", ParityFilter.field("total.currency", "$eq", "USD")),
                    false);
            check("find ref = none", where(h, "invoices", ParityFilter.field("ref", "$eq", "none")), false);
            check("sort total.cents", typed(h.find("invoices", Optional.empty(), Optional.empty(),
                    Optional.of(ParityFilter.sort("total.cents", SortDirection.asc)), Optional.empty())), true);
        }
    }

    @Nested
    @DisplayName("flattened names: collisions and PostgreSQL's 63-byte limit")
    class Naming {

        @Test
        @DisplayName("a field named home__city next to home.city")
        void collision() {
            Clash clash = with(new Clash(), c -> {
                c.uuid = "c1";
                c.home = with(new CityOnly(), h -> h.city = "nested");
                c.home__city = "flat";
            });
            Outcome outcome;
            try {
                ParityHarness h = ParityHarness.of(Domain.of("clashes", Clash.class));
                storage("Clash{CityOnly home, String home__city}", h);
                h.save("clashes", clash);
                outcome = all(h, "clashes");
            } catch (RuntimeException | AssertionError e) {
                // The PostgreSQL model refused the DTO: ask MongoDB alone what it does with it.
                Object mongoAnswer;
                Throwable mongoError = null;
                try {
                    MongoDao mongo = new MongoDao(MongoTestServer.freshDatabase(), "clashes");
                    mongo.registerDomain(TestDomains.definition(Clash.class, Map.of()));
                    mongo.save(clash);
                    mongoAnswer = describeResult(mongo.find(Optional.empty(), Optional.empty(), Optional.empty()));
                } catch (Exception me) {
                    mongoAnswer = null;
                    mongoError = me;
                }
                outcome = new Outcome(mongoAnswer, null, mongoError, e);
            }
            check("home__city next to home.city", outcome, false);
        }

        @Test
        @DisplayName("paths beyond 63 bytes, four levels deep: round trip with nulls at every level")
        void longRoundTrip() {
            ParityHarness h = deeps();
            storage("Deep{4 levels of long names, List<Third> with a long name}", h);
            check("Deep round trip", all(h, "deeps"), false);
        }

        @Test
        @DisplayName("two long paths sharing their prefix stay distinct (alpha vs beta)")
        void longDistinct() {
            ParityHarness h = deeps();
            check("alpha = same",
                    where(h, "deeps", ParityFilter.field(LEAF + "leafValueAlphaWithAnUnreasonablyLongName", "$eq", "same")),
                    false);
            check("beta = same",
                    where(h, "deeps", ParityFilter.field(LEAF + "leafValueBetaWithAnUnreasonablyLongName", "$eq", "same")),
                    false);
        }

        @Test
        @DisplayName("filter null on a long leaf (absent at level 1, 2, 3, or leaf null)")
        void longNull() {
            check("alpha = null", where(deeps(), "deeps",
                    ParityFilter.field(LEAF + "leafValueAlphaWithAnUnreasonablyLongName", "$eq", null)), false);
        }

        @Test
        @DisplayName("sort on a long path")
        void longSort() {
            check("sort rank desc", sorted(deeps(), "deeps", LEAF + "leafRankingNumberWithAnUnreasonablyLongName",
                    SortDirection.desc, ParityNestedShapesTest::deepRank), true);
        }

        @Test
        @DisplayName("filter inside a child table whose name exceeds 63 bytes")
        void longChildTable() {
            ParityHarness h = deeps();
            check("items.alpha = same", where(h, "deeps", ParityFilter.field(
                    "collectionOfThirdLevelItemsWithAnExcessivelyLongName.leafValueAlphaWithAnUnreasonablyLongName",
                    "$eq", "same")), false);
            check("items $empty true", where(h, "deeps", ParityFilter.field(
                    "collectionOfThirdLevelItemsWithAnExcessivelyLongName", "$empty", true)), false);
        }
    }

    @Nested
    @DisplayName("generic embedded types (Wrapper<T>)")
    class Generics {

        private ParityHarness boxes() {
            ParityHarness h = ParityHarness.of(Domain.of("boxes", Boxes.class));
            h.save("boxes", with(new Boxes(), b -> {
                b.uuid = "b1";
                b.text = with(new Wrapper<String>(), w -> {
                    w.label = "t";
                    w.value = "apple";
                });
                b.number = with(new Wrapper<Integer>(), w -> {
                    w.label = "n";
                    w.value = 3;
                });
                b.part = with(new Wrapper<StaticPart>(), w -> {
                    w.label = "p";
                    w.value = with(new StaticPart(), s -> {
                        s.code = "Z";
                        s.size = 1;
                    });
                });
            }), with(new Boxes(), b -> {
                b.uuid = "b2";
                b.text = with(new Wrapper<String>(), w -> w.value = "banana");
                b.number = with(new Wrapper<Integer>(), w -> w.value = 12);
            }), with(new Boxes(), b -> {
                b.uuid = "b3";
                b.text = new Wrapper<>();
            }));
            storage("Boxes{Wrapper<String> text, Wrapper<Integer> number, Wrapper<StaticPart> part}", h);
            return h;
        }

        @Test
        @DisplayName("round trip: the T value comes back with the same runtime type")
        void roundTrip() {
            check("Boxes round trip", all(boxes(), "boxes"), false);
        }

        @Test
        @DisplayName("filter on a T value (String, Integer) and inside a T POJO")
        void filter() {
            ParityHarness h = boxes();
            check("text.value = apple", where(h, "boxes", ParityFilter.field("text.value", "$eq", "apple")), false);
            check("number.value > 5", where(h, "boxes", ParityFilter.field("number.value", "$gt", 5)), false);
            check("part.value.code = Z", where(h, "boxes", ParityFilter.field("part.value.code", "$eq", "Z")), false);
            check("text.value = null", where(h, "boxes", ParityFilter.field("text.value", "$eq", null)), false);
        }

        @Test
        @DisplayName("sort on a T value declared Integer")
        void sortInteger() {
            check("sort number.value desc", sorted(boxes(), "boxes", "number.value", SortDirection.desc, b -> {
                Boxes box = (Boxes) b;
                return box.number == null ? null : box.number.value;
            }), true);
        }

        @Test
        @DisplayName("sort on a T value declared String")
        void sortString() {
            ParityHarness h = boxes();
            check("sort text.value asc", sorted(h, "boxes", "text.value", SortDirection.asc, b -> {
                Boxes box = (Boxes) b;
                return box.text == null ? null : box.text.value;
            }), true);
        }
    }
}
