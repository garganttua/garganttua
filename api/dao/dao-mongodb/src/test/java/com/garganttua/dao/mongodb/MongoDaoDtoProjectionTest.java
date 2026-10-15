package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

/**
 * A light DTO over a rich collection, on a REAL MongoDB, observed through the server's profiler —
 * the measurement a consumer made: their read-only map twin, twelve fields over 1.2 KB documents,
 * read every document whole, because nothing but an explicit {@code fields=} ever reached the query.
 *
 * <p>
 * The DTO is now the default projection. What this proves is the claim that makes it safe: the
 * response is the SAME, field for field, while the server sends a fraction of the bytes — and a
 * filter on a field the DTO does not map still matches, because a projection narrows what comes back,
 * never what is matched.
 * </p>
 */
@DisplayName("MongoDao — the DTO is the default projection (real MongoDB, profiler)")
class MongoDaoDtoProjectionTest {

    private static final String COLLECTION = "organizations";
    private static final int DOCUMENTS = 66;

    /** The map twin: three fields of a much richer document. */
    public static class SummaryDto {
        private String uuid;
        private String name;
        private String city;

        public String getUuid() { return uuid; }
        public String getName() { return name; }
        public String getCity() { return city; }
    }

    private MongoDatabase database;

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    @BeforeEach
    void seed() {
        this.database = MongoTestServer.freshDatabase();
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < DOCUMENTS; i++) {
            List<Document> services = new ArrayList<>();
            for (int s = 0; s < 8; s++) {
                services.add(new Document("label", "service " + s + " of organisation " + i)
                        .append("description", "x".repeat(80)));
            }
            documents.add(new Document("_id", "uuid-" + i)
                    .append("uuid", "uuid-" + i)
                    .append("name", "Organisation " + i)
                    .append("city", i % 2 == 0 ? "Dole" : "Besançon")
                    .append("categoryLabel", i % 3 == 0 ? "palliative" : "other")
                    .append("services", services));
        }
        this.database.getCollection(COLLECTION).insertMany(documents);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private MongoDao dao() {
        IDtoDefinition<Object> dtoDefinition = mock(IDtoDefinition.class);
        when(dtoDefinition.dtoClass()).thenReturn((IClass) IClass.getClass(SummaryDto.class));
        when(dtoDefinition.uuid()).thenReturn(new ObjectAddress("uuid"));
        when(dtoDefinition.compositions()).thenReturn(List.of());
        IDomainDefinition domainDefinition = mock(IDomainDefinition.class);
        when(domainDefinition.dtoDefinitions()).thenReturn(List.of(dtoDefinition));

        MongoDao dao = new MongoDao(this.database, COLLECTION);
        dao.registerDomain(domainDefinition);
        return dao;
    }

    /** The comparison the consumer filters its map on — a field the light DTO does not carry. */
    private static IFilter categoryIs(String value) {
        IFilter comparison = mock(IFilter.class);
        when(comparison.getName()).thenReturn("$eq");
        when(comparison.getValue()).thenReturn(value);
        IFilter node = mock(IFilter.class);
        when(node.getName()).thenReturn("$field");
        when(node.getValue()).thenReturn("categoryLabel");
        when(node.getFilters()).thenReturn(List.of(comparison));
        return node;
    }

    private static List<String> rendered(List<Object> dtos) {
        return dtos.stream().map(SummaryDto.class::cast)
                .map(d -> d.getUuid() + "|" + d.getName() + "|" + d.getCity())
                .sorted().toList();
    }

    /** Runs {@code read} with the profiler on, and returns the {@code find} entry it produced. */
    private Document profiled(Runnable read) {
        this.database.runCommand(new Document("profile", 2));
        try {
            read.run();
        } finally {
            this.database.runCommand(new Document("profile", 0));
        }
        Document entry = this.database.getCollection("system.profile")
                .find(Filters.and(Filters.eq("op", "query"),
                        Filters.eq("ns", this.database.getName() + "." + COLLECTION)))
                .sort(Sorts.descending("ts")).first();
        assertNotNull(entry, "the profiler recorded no find on " + COLLECTION);
        return entry;
    }

    private static void readAll(MongoDao dao, List<Object> into) {
        try {
            into.addAll(dao.find(Optional.empty(), Optional.empty(), Optional.empty()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the server receives the DTO's fields as projection, plus the uuid")
    void projectionIsTheDto() {
        Document entry = profiled(() -> readAll(dao(), new ArrayList<>()));

        Document projection = entry.get("command", Document.class).get("projection", Document.class);
        assertNotNull(projection, "a read without fields= must still project; got " + entry.toJson());
        assertEquals(Set.of("uuid", "name", "city"), projection.keySet());
    }

    @Test
    @DisplayName("the response is identical to a whole-document read, for a fraction of the bytes")
    void sameResponseFewerBytes() {
        List<Object> projected = new ArrayList<>();
        List<Object> whole = new ArrayList<>();

        long projectedBytes = profiled(() -> readAll(dao(), projected)).getInteger("responseLength");
        long wholeBytes = profiled(() -> readAll(dao().readWholeDocuments(), whole)).getInteger("responseLength");

        assertEquals(DOCUMENTS, projected.size());
        assertEquals(rendered(whole), rendered(projected), "the DTO copied the same values either way");
        assertTrue(projectedBytes * 10 < wholeBytes * 3,
                "at least 70% fewer bytes expected; projected " + projectedBytes + " vs whole " + wholeBytes);
    }

    @Test
    @DisplayName("a filter on a field the DTO does not map still matches")
    void filterOnUnmappedFieldStillMatches() throws Exception {
        List<Object> palliative = dao().find(Optional.empty(), Optional.of(categoryIs("palliative")), Optional.empty());

        assertEquals(DOCUMENTS / 3, palliative.size());
    }
}
