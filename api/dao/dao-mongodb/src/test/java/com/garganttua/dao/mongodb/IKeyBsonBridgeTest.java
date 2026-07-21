package com.garganttua.dao.mongodb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import com.garganttua.core.crypto.EncryptionMode;
import com.garganttua.core.crypto.EncryptionPaddingMode;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyMaterialEnvelope;
import com.garganttua.core.crypto.KeyRealmBuilder;
import com.garganttua.core.crypto.KeyType;
import com.garganttua.core.crypto.SealedKey;
import com.garganttua.core.crypto.SignatureAlgorithm;

/**
 * The {@link IKeyBsonBridge} serialises an {@link IKey} to a self-describing BSON sub-document and
 * reconstructs it losslessly — the fix for "Can't find a codec for …crypto.Key" when a key store is
 * backed by a real MongoDB DAO. The key material and the rebuilt JDK key must be byte-identical.
 */
@DisplayName("IKeyBsonBridge — IKey ↔ self-describing BSON sub-document")
class IKeyBsonBridgeTest {

	private static IKey signingKey(KeyType type) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair pair = generator.generateKeyPair();
		IKeyAlgorithm algorithm = KeyAlgorithm.validateKeyAlgorithm("EC-256");
		byte[] material = type == KeyType.PRIVATE ? pair.getPrivate().getEncoded() : pair.getPublic().getEncoded();
		return com.garganttua.core.crypto.Key.fromSigningMaterial(type, algorithm, SignatureAlgorithm.SHA256, material);
	}

	@Test
	@DisplayName("toDocument stores the metadata + base64 material, never the raw IKey")
	void toDocumentStoresDescribedMaterial() throws Exception {
		IKey key = signingKey(KeyType.PRIVATE);

		Document document = IKeyBsonBridge.toDocument(key);

		assertFalse(document instanceof IKey, "the persisted form must NOT be the IKey itself");
		assertEquals(Boolean.TRUE, document.get(IKeyBsonBridge.MARKER), "the marker must be present");
		assertEquals("PRIVATE", document.getString("type"));
		assertEquals("EC-256", document.getString("algorithm"), "algorithm is stored as NAME-SIZE");
		assertEquals("SHA256", document.getString("signatureAlgorithm"));
		assertNotNull(document.getString("rawKey"), "the base64 material must be stored");
		assertFalse(document.getString("rawKey").isBlank());
	}

	@Test
	@DisplayName("isKeyDocument recognises only a marked IKey document")
	void recognisesKeyDocument() throws Exception {
		assertTrue(IKeyBsonBridge.isKeyDocument(IKeyBsonBridge.toDocument(signingKey(KeyType.PUBLIC))));
		assertFalse(IKeyBsonBridge.isKeyDocument(new Document("name", "alice")), "a plain document is not an IKey");
		assertFalse(IKeyBsonBridge.isKeyDocument("just a string"));
		assertFalse(IKeyBsonBridge.isKeyDocument(null));
	}

	@Test
	@DisplayName("fromDocument reconstructs a byte-identical key (material, JDK encoding, metadata)")
	void fromDocumentReconstructsExactly() throws Exception {
		IKey original = signingKey(KeyType.PRIVATE);

		IKey restored = IKeyBsonBridge.fromDocument(IKeyBsonBridge.toDocument(original));

		assertInstanceOf(IKey.class, restored);
		assertArrayEquals(original.getRawKey(), restored.getRawKey(), "the stored raw material must round-trip");
		assertArrayEquals(original.getKey().getEncoded(), restored.getKey().getEncoded(),
				"the reconstructed JDK key must be byte-identical to the original");
		assertEquals(KeyType.PRIVATE, restored.getType());
		assertEquals("EC", restored.getAlgorithm().getName());
		assertEquals(256, restored.getAlgorithm().getKeySize());
		assertEquals(SignatureAlgorithm.SHA256, restored.getSignatureAlgorithm());
	}

	@Test
	@DisplayName("a SealedKey survives the BSON round-trip: KeyType preserved, envelope intact, opens to clear")
	void sealedKeySurvivesBsonRoundTrip() throws Exception {
		// This is the representation trap: getRawKey() is Base64-ASCII, the bridge stores it as a
		// string and Base64-decodes on read. A sealed private key must round-trip so materialize can
		// open it — otherwise a healthcare app persists an undecryptable (lost) private key.
		IKeyRealm kek = KeyRealmBuilder.builder()
				.name("bson-kek")
				.algorithm(KeyAlgorithm.AES_256)
				.encryptionMode(EncryptionMode.GCM)
				.paddingMode(EncryptionPaddingMode.NO_PADDING)
				.initializationVectorSize(12)
				.build();

		IKey original = signingKey(KeyType.PRIVATE);
		byte[] clear = original.getKey().getEncoded();
		byte[] envelope = KeyMaterialEnvelope.seal(kek, clear);
		SealedKey sealed = SealedKey.from(original, envelope);

		Document doc = IKeyBsonBridge.toDocument(sealed);
		assertEquals(KeyType.PRIVATE.name(), doc.getString("type"), "KeyType must be stored so read knows to open");

		IKey restored = IKeyBsonBridge.fromDocument(doc);
		assertEquals(KeyType.PRIVATE, restored.getType(), "KeyType must survive the round-trip");

		byte[] envelopeAfter = Base64.getDecoder().decode(restored.getRawKey());
		assertArrayEquals(envelope, envelopeAfter, "the sealed envelope must survive the Base64/BSON round-trip");
		assertArrayEquals(clear, KeyMaterialEnvelope.open(kek, envelopeAfter),
				"the round-tripped envelope must open back to the exact clear material");
	}
}
