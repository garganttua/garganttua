package com.garganttua.dao.mongodb;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bson.BsonBinary;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinarySubType;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonJavaScript;
import org.bson.BsonMaxKey;
import org.bson.BsonMinKey;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonReader;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonSymbol;
import org.bson.BsonTimestamp;
import org.bson.BsonType;
import org.bson.BsonArray;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.UuidRepresentation;
import org.bson.codecs.BsonTypeClassMap;
import org.bson.codecs.BsonTypeCodecMap;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.UuidCodec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IField;

/**
 * Decodes a found document straight into its DTO, without the {@link Document} the driver would build first.
 *
 * <p>
 * A list read used to pay, per element, a {@code LinkedHashMap} filled by the driver ({@code Document.put} for each
 * field) and emptied by the reader ({@code Document.get} for each field). This reads each value off the BSON stream
 * and hands it to the reader's own mapping — {@link MongoDocumentReader#applyValue}, the very code the
 * {@code Document} path runs.
 * </p>
 *
 * <p>
 * <b>The values are the {@code Document}'s, by construction and by check.</b> Each one is decoded as the driver's
 * {@code DocumentCodec} decodes a value: {@code null}, a 16-byte binary as a {@link UUID} by subtype and the
 * configured representation, anything else by the codec the registry maps to its BSON type. And before it is used,
 * the decoder decodes a probe document — one field of every BSON type — both ways: a registry that would decode one
 * of them differently (a custom type map, a value transformer) keeps the {@code Document} path. So does a DTO with
 * compositions: resolving a reference queries the store, which must not happen while a batch is being decoded.
 * </p>
 */
// LooseCoupling: RawBsonDocument, BsonDocument and Document are the driver's own concrete types, which its codec API
// produces and consumes; there is no interface to declare instead.
@SuppressWarnings("PMD.LooseCoupling")
final class MongoDtoCodec implements Codec<MongoDtoCodec.Decoded> {

    private static final Logger log = Logger.getLogger(MongoDtoCodec.class);

    /** One decoded DTO — the document class the collection is read as. */
    record Decoded(Object dto) {
    }

    /** A mapping failure, carried through the driver's decoding to {@link MongoDao}, which unwraps it. */
    static final class DecodingFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ApiException failure;

        DecodingFailure(ApiException cause) {
            super(cause);
            this.failure = cause;
        }

        ApiException apiException() {
            return this.failure;
        }
    }

    private static final int UUID_SIZE = 16;

    private final MongoDocumentReader reader;
    private final CodecRegistry registry;
    private final BsonTypeCodecMap codecs;
    private final UuidRepresentation uuidRepresentation;
    private final IField[] fields;
    /**
     * Every field a document key fills. Usually one; more when a subclass re-declares a field of its superclass —
     * the {@code Document} path fills each of them with the same value, and so does this.
     */
    private final Map<String, int[]> indicesByName;
    /** Whether a name fills exactly one field — the case the in-order fast path takes. */
    private final boolean[] unique;
    private final int[] uuidIndices;
    private final String uuidName;

    private MongoDtoCodec(MongoDocumentReader reader, MongoDaoConfig config, CodecRegistry registry) {
        this.reader = reader;
        this.registry = registry;
        this.codecs = new BsonTypeCodecMap(new BsonTypeClassMap(), registry);
        this.uuidRepresentation = registry.get(UUID.class) instanceof UuidCodec uuid
                ? uuid.getUuidRepresentation()
                : UuidRepresentation.UNSPECIFIED;
        List<IField> list = reader.fieldsOf(config.dtoClass());
        this.fields = list.toArray(new IField[0]);
        Map<String, List<Integer>> names = new HashMap<>();
        for (int i = 0; i < this.fields.length; i++) {
            names.computeIfAbsent(this.fields[i].getName(), k -> new ArrayList<>()).add(i);
        }
        Map<String, int[]> indices = new HashMap<>();
        names.forEach((name, positions) -> indices.put(name, positions.stream().mapToInt(Integer::intValue).toArray()));
        this.indicesByName = Map.copyOf(indices);
        this.unique = new boolean[this.fields.length];
        for (int i = 0; i < this.fields.length; i++) {
            this.unique[i] = this.indicesByName.get(this.fields[i].getName()).length == 1;
        }
        this.uuidName = config.uuidFieldName();
        this.uuidIndices = this.indicesByName.getOrDefault(this.uuidName, new int[0]);
    }

    /**
     * {@return the direct decoder of {@code config}'s DTO over {@code registry}} — empty when the DTO has compositions,
     * or when the registry decodes some value differently from what this decoder would.
     */
    static Optional<MongoDtoCodec> of(MongoDocumentReader reader, MongoDaoConfig config, CodecRegistry registry) {
        if (!config.compositions().isEmpty()) {
            return Optional.empty();
        }
        MongoDtoCodec codec;
        try {
            codec = new MongoDtoCodec(reader, config, registry);
        } catch (RuntimeException e) {
            log.warn("MongoDB DTO {}: no direct decoder ({}) — read through Document", config.dtoClass().getName(),
                    e.getMessage());
            return Optional.empty();
        }
        if (!codec.agreesWithDocumentCodec()) {
            log.info("MongoDB DTO {}: the codec registry decodes some value differently from the default — read "
                    + "through Document", config.dtoClass().getName());
            return Optional.empty();
        }
        return Optional.of(codec);
    }

    @Override
    public Decoded decode(BsonReader bson, DecoderContext context) {
        try {
            return new Decoded(decodeDto(bson, context));
        } catch (ApiException e) {
            throw new DecodingFailure(e);
        }
    }

    /** Where a document's decoding stands: the next field expected, whether the uuid and {@code _id} were seen. */
    private static final class Progress {
        private int expected;
        private boolean uuidSeen;
        private boolean idSeen;
        private Object id;
    }

    private Object decodeDto(BsonReader bson, DecoderContext context) throws ApiException {
        Object dto = this.reader.newDto();
        Progress progress = new Progress();
        bson.readStartDocument();
        while (bson.readBsonType() != BsonType.END_OF_DOCUMENT) {
            decodeKey(bson, context, dto, progress);
        }
        bson.readEndDocument();
        if (!progress.uuidSeen && progress.idSeen) {
            // A document carrying only _id (written by another tool, or an _id-only projection): its _id is the uuid.
            for (int index : this.uuidIndices) {
                this.reader.applyValue(dto, this.fields[index], progress.id);
            }
        }
        return dto;
    }

    /** Reads one key and its value onto every field the key fills, or skips it when it fills none. */
    private void decodeKey(BsonReader bson, DecoderContext context, Object dto, Progress progress) throws ApiException {
        String name = bson.readName();
        boolean isId = MongoDaoConfig.MONGO_ID.equals(name);
        int expected = progress.expected;
        // Documents are written in the DTO's field order: the next key is nearly always the expected field.
        boolean inOrder = expected < this.fields.length && this.unique[expected]
                && this.fields[expected].getName().equals(name);
        int[] targets = inOrder ? null : this.indicesByName.get(name);
        if (!inOrder && targets == null && !isId) {
            bson.skipValue();
            return;
        }
        Object value = readValue(bson, context);
        if (isId) {
            progress.id = value;
            progress.idSeen = true;
        }
        if (inOrder) {
            this.reader.applyValue(dto, this.fields[expected], value);
            progress.expected = expected + 1;
        } else if (targets != null) {
            for (int index : targets) {
                this.reader.applyValue(dto, this.fields[index], value);
            }
            progress.expected = targets[targets.length - 1] + 1;
        } else {
            return;
        }
        progress.uuidSeen |= name.equals(this.uuidName);
    }

    /** One value, decoded as the driver's {@code DocumentCodec} decodes a value of a {@code Document}. */
    private Object readValue(BsonReader bson, DecoderContext context) {
        BsonType type = bson.getCurrentBsonType();
        if (type == BsonType.NULL) {
            bson.readNull();
            return null;
        }
        Codec<?> codec = this.codecs.get(type);
        if (type == BsonType.BINARY && bson.peekBinarySize() == UUID_SIZE) {
            byte subtype = bson.peekBinarySubType();
            if (subtype == BsonBinarySubType.UUID_LEGACY.getValue()
                    && (this.uuidRepresentation == UuidRepresentation.JAVA_LEGACY
                            || this.uuidRepresentation == UuidRepresentation.C_SHARP_LEGACY
                            || this.uuidRepresentation == UuidRepresentation.PYTHON_LEGACY)
                    || subtype == BsonBinarySubType.UUID_STANDARD.getValue()
                            && this.uuidRepresentation == UuidRepresentation.STANDARD) {
                codec = this.registry.get(UUID.class);
            }
        }
        return codec.decode(bson, context);
    }

    /**
     * Whether every value of a probe document — one of each BSON type a stored document may hold — decodes here
     * exactly as the registry's own {@code Document} codec decodes it.
     */
    private boolean agreesWithDocumentCodec() {
        try {
            RawBsonDocument probe = new RawBsonDocument(probeDocument(), this.registry.get(BsonDocument.class));
            Document expected = this.registry.get(Document.class).decode(binaryReader(probe), DecoderContext.builder().build());
            Map<String, Object> actual = new HashMap<>();
            List<String> order = new ArrayList<>();
            try (BsonBinaryReader bson = binaryReader(probe)) {
                bson.readStartDocument();
                while (bson.readBsonType() != BsonType.END_OF_DOCUMENT) {
                    String name = bson.readName();
                    order.add(name);
                    actual.put(name, readValue(bson, DecoderContext.builder().build()));
                }
                bson.readEndDocument();
            }
            for (String name : order) {
                Object mine = actual.get(name);
                Object theirs = expected.get(name);
                if (!Objects.equals(mine, theirs)
                        || mine != null && theirs != null && !mine.getClass().equals(theirs.getClass())) {
                    return false;
                }
            }
            return order.equals(new ArrayList<>(expected.keySet()));
        } catch (RuntimeException e) {
            log.warn("MongoDB DTO decoding probe failed ({}) — read through Document", e.getMessage());
            return false;
        }
    }

    private static BsonBinaryReader binaryReader(RawBsonDocument document) {
        ByteBuffer bytes = document.getByteBuffer().asNIO();
        return new BsonBinaryReader(bytes);
    }

    private static BsonDocument probeDocument() {
        UUID uuid = UUID.fromString("0b4d6c8e-1f2a-4b3c-9d4e-5f6a7b8c9d0e");
        return new BsonDocument()
                .append("string", new BsonString("s"))
                .append("int32", new BsonInt32(1))
                .append("int64", new BsonInt64(2L))
                .append("double", new BsonDouble(3.5))
                .append("boolean", BsonBoolean.TRUE)
                .append("date", new BsonDateTime(1_700_000_000_000L))
                .append("objectId", new BsonObjectId(new ObjectId("65f0a1b2c3d4e5f607182930")))
                .append("decimal", new BsonDecimal128(Decimal128.parse("4.25")))
                .append("null", BsonNull.VALUE)
                .append("binary", new BsonBinary(new byte[] { 1, 2, 3 }))
                .append("uuidStandard", new BsonBinary(uuid, UuidRepresentation.STANDARD))
                .append("uuidLegacy", new BsonBinary(uuid, UuidRepresentation.JAVA_LEGACY))
                .append("document", new BsonDocument("a", new BsonInt32(1)).append("b", new BsonString("x")))
                .append("array", new BsonArray(List.of(new BsonInt32(1), new BsonString("y"),
                        new BsonDocument("c", BsonBoolean.FALSE))))
                .append("timestamp", new BsonTimestamp(1, 2))
                .append("regex", new BsonRegularExpression("^a", "i"))
                .append("javascript", new BsonJavaScript("1"))
                .append("symbol", new BsonSymbol("sym"))
                .append("minKey", new BsonMinKey())
                .append("maxKey", new BsonMaxKey());
    }

    @Override
    public void encode(BsonWriter writer, Decoded value, EncoderContext context) {
        throw new UnsupportedOperationException("MongoDtoCodec only reads — writes go through MongoDocumentWriter");
    }

    @Override
    public Class<Decoded> getEncoderClass() {
        return Decoded.class;
    }
}
