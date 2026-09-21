package com.garganttua.dao.postgresql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garganttua.api.commons.ApiException;
import com.garganttua.core.crypto.EncryptionMode;
import com.garganttua.core.crypto.EncryptionPaddingMode;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeySerializer;
import com.garganttua.core.crypto.KeyType;
import com.garganttua.core.crypto.SignatureAlgorithm;

/**
 * Crypto key material ({@code IKey}) to and from a self-describing JSON descriptor, for
 * {@code JSONB} columns — the PostgreSQL twin of the MongoDB DAO's BSON bridge, with the same shape
 * so a key reads the same whichever store wrote it.
 *
 * <p>
 * There is no SQL type for an {@code IKey}: it wraps a lazy {@code java.security.Key} behind a
 * package-private constructor. The material round-trips through
 * {@link KeySerializer#exportRawKey(IKey)} / {@link KeySerializer#importRawKey}, and the metadata
 * needed to rebuild the key is stored beside it:
 * </p>
 * <pre>{@code
 * { "__ikey": true, "type": "PRIVATE", "algorithm": "EC-256",
 *   "signatureAlgorithm": "SHA256", "rawKey": "<base64>" }
 * }</pre>
 *
 * <p>
 * <b>Limitation, inherited:</b> {@code IKey} does not expose its IV size, so encryption keys
 * round-trip with {@code ivSize = 0}. Signing keys do not use it.
 * </p>
 */
public final class PgKeyCodec {

    static final String MARKER = "__ikey";

    private static final String TYPE = "type";
    private static final String ALGORITHM = "algorithm";
    private static final String SIGNATURE_ALGORITHM = "signatureAlgorithm";
    private static final String ENCRYPTION_MODE = "encryptionMode";
    private static final String ENCRYPTION_PADDING_MODE = "encryptionPaddingMode";
    private static final String IV_SIZE = "ivSize";
    private static final String RAW_KEY = "rawKey";

    private PgKeyCodec() {
        // Static helpers
    }

    /**
     * The JSON descriptor of a key.
     *
     * @param key the key
     * @return its descriptor
     */
    public static ObjectNode toJson(IKey key) {
        IKeyAlgorithm algorithm = key.getAlgorithm();
        ObjectNode node = PgJson.MAPPER.createObjectNode();
        node.put(MARKER, true);
        node.put(TYPE, key.getType().name());
        node.put(ALGORITHM, algorithm.getName() + "-" + algorithm.getKeySize());
        node.put(RAW_KEY, KeySerializer.exportRawKey(key));
        if (key.getSignatureAlgorithm() != null) {
            node.put(SIGNATURE_ALGORITHM, key.getSignatureAlgorithm().name());
        }
        if (key.getEncryptionMode() != null) {
            node.put(ENCRYPTION_MODE, key.getEncryptionMode().name());
        }
        if (key.getEncryptionPaddingMode() != null) {
            node.put(ENCRYPTION_PADDING_MODE, key.getEncryptionPaddingMode().name());
        }
        return node;
    }

    /**
     * Rebuilds a key from its descriptor.
     *
     * @param node the descriptor
     * @return the key
     * @throws ApiException when the descriptor cannot be turned back into a key
     */
    public static IKey fromJson(JsonNode node) throws ApiException {
        try {
            KeyType type = KeyType.valueOf(node.get(TYPE).asText());
            IKeyAlgorithm algorithm = KeyAlgorithm.validateKeyAlgorithm(node.get(ALGORITHM).asText());
            SignatureAlgorithm signature = node.hasNonNull(SIGNATURE_ALGORITHM)
                    ? SignatureAlgorithm.valueOf(node.get(SIGNATURE_ALGORITHM).asText()) : null;
            EncryptionMode mode = node.hasNonNull(ENCRYPTION_MODE)
                    ? EncryptionMode.valueOf(node.get(ENCRYPTION_MODE).asText()) : null;
            EncryptionPaddingMode padding = node.hasNonNull(ENCRYPTION_PADDING_MODE)
                    ? EncryptionPaddingMode.valueOf(node.get(ENCRYPTION_PADDING_MODE).asText()) : null;
            int ivSize = node.hasNonNull(IV_SIZE) ? node.get(IV_SIZE).asInt() : 0;
            return KeySerializer.importRawKey(node.get(RAW_KEY).asText(), type, algorithm, ivSize, mode,
                    padding, signature);
        } catch (RuntimeException e) {
            throw new ApiException("Failed to rebuild an IKey from its persisted PostgreSQL descriptor "
                    + "(algorithm=" + node.path(ALGORITHM).asText() + ", type=" + node.path(TYPE).asText() + ")", e);
        }
    }
}
