package com.garganttua.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Key;
import java.util.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A realm whose material lives elsewhere: it delegates, it never holds, and it refuses to rotate.
 */
@DisplayName("ExternalKeyRealm — material held outside the process")
class ExternalKeyRealmTest {

	/** A key that signs but refuses to hand its material out — what a token handle behaves like. */
	private static final class TokenKey implements IKey {

		private final KeyType type;

		private TokenKey(KeyType type) {
			this.type = type;
		}

		@Override
		public boolean isExportable() {
			return false;
		}

		@Override
		public byte[] sign(byte[] data) {
			return new byte[] { 1, 2, 3 };
		}

		@Override
		public boolean verifySignature(byte[] signature, byte[] originalData) {
			return signature.length == 3;
		}

		@Override
		public byte[] encrypt(byte[] clear) {
			return clear;
		}

		@Override
		public byte[] decrypt(byte[] encoded) {
			return encoded;
		}

		@Override
		public byte[] getRawKey() {
			return "token:slot0:autonom-signing".getBytes();
		}

		@Override
		public Key getKey() {
			throw new CryptoException("the material never leaves the token");
		}

		@Override
		public KeyType getType() {
			return this.type;
		}

		@Override
		public IKeyAlgorithm getAlgorithm() {
			return KeyAlgorithm.EC_256;
		}

		@Override
		public EncryptionMode getEncryptionMode() {
			return null;
		}

		@Override
		public EncryptionPaddingMode getEncryptionPaddingMode() {
			return null;
		}

		@Override
		public SignatureAlgorithm getSignatureAlgorithm() {
			return SignatureAlgorithm.SHA256;
		}
	}

	private static ExternalKeyRealm realm(Date expiration, boolean revoked) {
		return ExternalKeyRealm.of("users:global", KeyAlgorithm.EC_256, new TokenKey(KeyType.PRIVATE),
				new TokenKey(KeyType.PUBLIC), expiration, revoked);
	}

	@Test
	@DisplayName("delegates signing to the key it was handed, and never touches its material")
	void delegates() {
		ExternalKeyRealm realm = realm(null, false);

		IKey signing = realm.getKeyForSigning();

		assertFalse(signing.isExportable());
		assertNotNull(signing.sign("payload".getBytes()));
		// Le materiel n'est jamais lu : la seule tentative leve, et le realm n'en fait aucune.
		assertThrows(CryptoException.class, signing::getKey);
		assertSame(signing, realm.getKeyForEncryption());
	}

	@Test
	@DisplayName("refuses every key once revoked or expired — same contract as KeyRealm")
	void refusesWhenUnusable() {
		ExternalKeyRealm revoked = realm(null, true);
		assertThrows(CryptoException.class, revoked::getKeyForSigning);

		ExternalKeyRealm expired = realm(new Date(System.currentTimeMillis() - 1_000L), false);
		assertTrue(expired.isExpired());
		assertThrows(CryptoException.class, expired::getKeyForSignatureVerification);
	}

	@Test
	@DisplayName("REFUSE la rotation : une paire qui doit naitre dans le jeton ne nait pas ici")
	void refusesRotation() {
		CryptoException refusal = assertThrows(CryptoException.class, () -> realm(null, false).rotate());

		// Le refus NOMME le geste de remplacement : sans cela on le contourne, et la paire suivante
		// naitrait en memoire — exactement ce que ce realm existe pour eviter.
		assertTrue(refusal.getMessage().contains("in its holder"), refusal.getMessage());
	}

	@Test
	@DisplayName("porte les faits d'un realm : nom, algorithme, echeance, version")
	void carriesMetadata() {
		Date expiration = new Date(System.currentTimeMillis() + 86_400_000L);
		ExternalKeyRealm realm = realm(expiration, false);

		assertEquals("users:global", realm.getName());
		assertEquals(KeyAlgorithm.EC_256, realm.getKeyAlgorithm());
		assertEquals(expiration, realm.getExpiration());
		assertEquals(1, realm.getVersion());
		assertFalse(realm.isRevoked());
	}
}
