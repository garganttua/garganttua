package com.garganttua.api.core.security.key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Key;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.definition.IDomainKeyDefinition;
import com.garganttua.api.core.expression.SecurityExpressions;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.crypto.CryptoException;
import com.garganttua.core.crypto.EncryptionMode;
import com.garganttua.core.crypto.EncryptionPaddingMode;
import com.garganttua.core.crypto.ExternalKeyRealm;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyType;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * Une clé dont le matériel ne sort PAS de son détenteur doit pouvoir signer.
 *
 * <p>La matérialisation reconstruisait le realm à partir des OCTETS de la clé privée
 * ({@code key.getKey().getEncoded()}). Un jeton PKCS#11, un HSM, un KMS n'en rendent aucun — c'est
 * leur raison d'être. « La clé privée ne quitte jamais le coffre » était donc inexprimable, quel
 * que soit le stockage choisi en dessous : le blocage n'était pas dans le dépôt mais ici.
 *
 * <p>Ce test le prouve par la seule méthode qui ne se discute pas : la clé de test LÈVE si l'on
 * tente de lire son matériel. Si la matérialisation y touchait, le test échouerait.
 */
@DisplayName("Matérialisation d'une clé non exportable (jeton / HSM / KMS)")
class ExternalKeyMaterializationTest {

	/** Ce que fait un handle de jeton : il signe, et il refuse de rendre sa matière. */
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
			return new byte[] { 4, 2 };
		}

		@Override
		public boolean verifySignature(byte[] signature, byte[] originalData) {
			return signature.length == 2;
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
			return "pkcs11:token=autonom;object=signing".getBytes();
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

	/** L'entité de clé, dans la forme que le DSL attend : des champs, et rien d'autre. */
	@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true, queryAllDeclaredConstructors = true)
	public static class TokenBackedKeyEntity {

		private String realmName = "users:global";

		private String algorithm = "EC-256";

		private String signatureAlgorithm = "SHA256";

		private IKey privateMaterial = new TokenKey(KeyType.PRIVATE);

		private IKey publicMaterial = new TokenKey(KeyType.PUBLIC);

		private Instant expiration;

		private boolean revoked;

		public String getRealmName() {
			return this.realmName;
		}

		public String getAlgorithm() {
			return this.algorithm;
		}

		public String getSignatureAlgorithm() {
			return this.signatureAlgorithm;
		}

		public IKey getPrivateMaterial() {
			return this.privateMaterial;
		}

		public IKey getPublicMaterial() {
			return this.publicMaterial;
		}

		public Instant getExpiration() {
			return this.expiration;
		}

		public boolean isRevoked() {
			return this.revoked;
		}

		public void setRevoked(boolean revoked) {
			this.revoked = revoked;
		}

		public void setExpiration(Instant expiration) {
			this.expiration = expiration;
		}
	}

	/** La projection des champs, telle que le DSL la produit — ici écrite à la main. */
	private static IDomainKeyDefinition keyDefinition() {
		return new IDomainKeyDefinition() {

			private ObjectAddress at(String field) {
				return new ObjectAddress(field);
			}

			@Override
			public ObjectAddress name() {
				return this.at("realmName");
			}

			@Override
			public ObjectAddress keyAlgorithm() {
				return this.at("algorithm");
			}

			@Override
			public ObjectAddress signatureAlgorithm() {
				return this.at("signatureAlgorithm");
			}

			@Override
			public ObjectAddress keyForSigning() {
				return this.at("privateMaterial");
			}

			@Override
			public ObjectAddress keyForSignatureVerification() {
				return this.at("publicMaterial");
			}

			@Override
			public ObjectAddress keyForEncryption() {
				return null;
			}

			@Override
			public ObjectAddress keyForDecryption() {
				return null;
			}

			@Override
			public ObjectAddress expiration() {
				return this.at("expiration");
			}

			@Override
			public ObjectAddress revoked() {
				return this.at("revoked");
			}

			@Override
			public ObjectAddress version() {
				return null;
			}

			@Override
			public ObjectAddress rotate() {
				return null;
			}
		};
	}

	@Test
	@DisplayName("le realm délègue au jeton — et ne lit JAMAIS le matériel")
	void materializesWithoutReadingMaterial() {
		IKeyRealm realm = SecurityExpressions.materializeKeyRealm(new TokenBackedKeyEntity(),
				keyDefinition(), DefaultMapper.reflection());

		assertInstanceOf(ExternalKeyRealm.class, realm);
		assertEquals("users:global", realm.getName());

		IKey signing = realm.getKeyForSigning();
		assertFalse(signing.isExportable());
		// La signature passe par le detenteur, sans que le materiel soit jamais demande : si la
		// materialisation l'avait lu, `getKey()` aurait leve et ce test n'existerait pas.
		byte[] signature = signing.sign("payload".getBytes());
		assertNotNull(signature);
		assertTrue(realm.getKeyForSignatureVerification().verifySignature(signature, "payload".getBytes()));
		assertThrows(CryptoException.class, signing::getKey);
	}

	@Test
	@DisplayName("une clé EXPORTABLE emprunte toujours le chemin par octets")
	void exportableKeysKeepTheByteBasedPath() {
		// Non-regression : le chemin historique reste celui de toute cle qui porte sa matiere.
		IKeyRealm generated = com.garganttua.core.crypto.KeyRealmBuilder
				.forSignature(KeyAlgorithm.EC_256, SignatureAlgorithm.SHA256)
				.name("users:global")
				.build();
		assertTrue(generated.getKeyForSigning().isExportable());
		assertFalse(generated instanceof ExternalKeyRealm);
	}
}
