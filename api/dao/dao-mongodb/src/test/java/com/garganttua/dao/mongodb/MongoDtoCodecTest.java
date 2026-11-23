package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bson.BsonBinary;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.bson.types.Binary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.definition.DtoComposition;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Reads decoded straight from BSON into the DTO must give the DTO the {@code Document} path gives — field for field,
 * on every shape a stored document takes: complete, partial, explicit nulls, {@code _id} only, unknown keys, keys
 * out of order, lossy types to coerce.
 */
@DisplayName("MongoDtoCodec — the same DTO as the Document path, without the Document")
class MongoDtoCodecTest {

    public enum Kind {
        EHPAD, USP
    }

    public static class Embedded {
        private String city;
        private Integer zip;
    }

    public static class BaseDto {
        private String uuid;
        private String label;       // re-declared by the subclass: both are filled, as on the Document path
    }

    public static class RichDto extends BaseDto {
        private static final String CONSTANT = "c";
        private transient String scratch;
        private String label;
        private Kind kind;
        private Integer beds;
        private Long count;
        private Double latitude;
        private Boolean active;
        private Instant createdAt;
        private Date updatedAt;
        private Object external;       // a binary subtype 4, read raw: a Binary without a UUID representation
        private byte[] blob;
        private List<String> tags;
        private Map<String, Integer> scores;
        private Embedded address;
        private List<Embedded> sites;
        private int primitive;
    }

    @BeforeAll
    static void installReflection() {
        com.garganttua.core.bootstrap.dsl.Bootstrap.builder();
    }

    private MongoCollection<Document> collection;
    private MongoDao dao;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @BeforeEach
    void setUp() {
        MongoDatabase database = MongoTestServer.freshDatabase();
        this.collection = database.getCollection("rich");
        IDtoDefinition<Object> dto = mock(IDtoDefinition.class);
        when(dto.dtoClass()).thenReturn((IClass) IClass.getClass(RichDto.class));
        when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
        when(dto.compositions()).thenReturn(List.of());
        IDomainDefinition domain = mock(IDomainDefinition.class);
        when(domain.dtoDefinitions()).thenReturn(List.of(dto));
        this.dao = new MongoDao(database, "rich");
        this.dao.registerDomain(domain);
    }

    private static List<Document> shapes() {
        UUID external = UUID.fromString("3f1c2b8e-7a6d-4e5f-9a0b-1c2d3e4f5a6b");
        Document complete = new Document("_id", "u1").append("uuid", "u1").append("label", "Centre A")
                .append("kind", "EHPAD").append("beds", 40).append("count", 7).append("latitude", 45.75)
                .append("active", true).append("createdAt", new Date(1_700_000_000_000L))
                .append("updatedAt", new Date(1_700_000_100_000L))
                .append("external", new BsonBinary(external, UuidRepresentation.STANDARD))
                .append("blob", new Binary(new byte[] { 1, 2, 3 })).append("tags", List.of("a", "b"))
                .append("scores", new Document("x", 1).append("y", 2))
                .append("address", new Document("city", "Lyon").append("zip", 69001))
                .append("sites", List.of(new Document("city", "Dole").append("zip", 39100)))
                .append("primitive", 3);
        Document partial = new Document("_id", "u2").append("uuid", "u2").append("label", "Centre B");
        Document nulls = new Document("_id", "u3").append("uuid", "u3").append("label", null).append("beds", null)
                .append("tags", null).append("address", null);
        Document idOnly = new Document("_id", "u4").append("kind", "USP");
        Document unknownKeys = new Document("_id", "u5").append("uuid", "u5").append("legacy", "x")
                .append("nested", new Document("deep", List.of(1, 2))).append("beds", 12);
        Document outOfOrder = new Document("_id", "u6").append("primitive", 9).append("beds", 5).append("uuid", "u6")
                .append("label", "Z").append("kind", "USP");
        Document lossy = new Document("_id", "u7").append("uuid", "u7").append("count", 5).append("beds", 6L)
                .append("primitive", "42").append("latitude", 44);
        return List.of(complete, partial, nulls, idOnly, unknownKeys, outOfOrder, lossy);
    }

    @Test
    @DisplayName("on every document shape, the decoded DTO is the Document path's DTO")
    void sameAsDocumentPath() throws Exception {
        assertTrue(this.dao.decodesDirectly(), "a real driver registry must enable the direct decoder");
        this.collection.insertMany(shapes());
        List<Object> direct = this.dao.find(Optional.empty(), Optional.empty(), Optional.empty());
        List<Document> stored = this.collection.find().into(new ArrayList<>());
        assertEquals(stored.size(), direct.size());
        for (int i = 0; i < stored.size(); i++) {
            Object expected = this.dao.documentToDto(stored.get(i));
            assertEquals(snapshot(expected), snapshot(direct.get(i)), "document " + stored.get(i).get("_id"));
        }
    }

    @Test
    @DisplayName("a document with only _id takes it as its uuid; a re-declared field is filled on both classes")
    void aliasesAndShadowing() throws Exception {
        this.collection.insertMany(shapes());
        List<Object> read = this.dao.find(Optional.empty(), Optional.empty(), Optional.empty());
        RichDto idOnly = (RichDto) read.stream().filter(d -> "u4".equals(((BaseDto) d).uuid)).findFirst().orElseThrow();
        assertEquals(Kind.USP, idOnly.kind);
        RichDto complete = (RichDto) read.stream().filter(d -> "u1".equals(((BaseDto) d).uuid)).findFirst().orElseThrow();
        assertEquals("Centre A", complete.label);
        assertEquals("Centre A", ((BaseDto) complete).label);
        assertEquals(40, complete.beds);
        assertEquals("Lyon", complete.address.city);
    }

    @Test
    @DisplayName("a DTO with compositions keeps the Document path — references are not resolved while decoding")
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void compositionsKeepTheDocumentPath() {
        MongoDatabase database = MongoTestServer.freshDatabase();
        IDtoDefinition<Object> dto = mock(IDtoDefinition.class);
        when(dto.dtoClass()).thenReturn((IClass) IClass.getClass(RichDto.class));
        when(dto.uuid()).thenReturn(new ObjectAddress("uuid"));
        when(dto.compositions()).thenReturn(List.of(new DtoComposition(new ObjectAddress("address"), "addresses")));
        IDomainDefinition domain = mock(IDomainDefinition.class);
        when(domain.dtoDefinitions()).thenReturn(List.of(dto));
        MongoDao composed = new MongoDao(database, "composed");
        composed.registerDomain(domain);
        assertFalse(composed.decodesDirectly());
    }

    /** The DTO's fields as plain values: embedded objects as maps, arrays as lists — comparable with equals. */
    private static Object snapshot(Object value) throws IllegalAccessException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof Instant || value instanceof Date
                || value instanceof UUID) {
            return value;
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                items.add(snapshot(Array.get(value, i)));
            }
            return items;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(snapshot(item));
            }
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), snapshot(e.getValue()));
            }
            return copy;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("@class", value.getClass().getName());
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                fields.put(c.getSimpleName() + "." + f.getName(), snapshot(f.get(value)));
            }
        }
        return fields;
    }
}
