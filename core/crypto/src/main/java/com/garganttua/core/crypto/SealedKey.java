package com.garganttua.core.crypto;

import java.util.Base64;
import java.util.Objects;

/**
 * {@link IKey} de STOCKAGE portant une enveloppe scellée (cf. {@link KeyMaterialEnvelope})
 * à la place du matériel en clair. Il expose l'enveloppe encodée Base64 — comme le contrat
 * historique de {@link Key#getRawKey()} — via {@link #getRawKey()}, pour que le round-trip
 * de persistance existant (par exemple le pont BSON Mongo) la stocke et la relise telle
 * quelle, tout en conservant le {@link KeyType} d'origine (PRIVATE / SECRET) afin que le
 * chemin de matérialisation sache qu'il faut la desceller.
 *
 * <p><b>Sûr par construction :</b> il ne détient que du chiffré et REFUSE toute opération
 * cryptographique ({@link #getKey()}, {@link #sign}, {@link #encrypt}, {@link #decrypt},
 * {@link #verifySignature}). Même en cas de mauvais usage, il n'expose jamais le matériel
 * en clair — au pire une exception explicite.
 */
public final class SealedKey implements IKey {

	private final KeyType type;
	private final IKeyAlgorithm algorithm;
	private final EncryptionMode encryptionMode;
	private final EncryptionPaddingMode encryptionPaddingMode;
	private final SignatureAlgorithm signatureAlgorithm;
	// Base64-ASCII de l'enveloppe scellée — même convention que Key.rawKey (contrat getRawKey historique).
	private final byte[] rawKeyBase64;

	private SealedKey(KeyType type, IKeyAlgorithm algorithm, EncryptionMode encryptionMode,
			EncryptionPaddingMode encryptionPaddingMode, SignatureAlgorithm signatureAlgorithm, byte[] envelope) {
		this.type = type;
		this.algorithm = algorithm;
		this.encryptionMode = encryptionMode;
		this.encryptionPaddingMode = encryptionPaddingMode;
		this.signatureAlgorithm = signatureAlgorithm;
		this.rawKeyBase64 = Base64.getEncoder().encode(envelope);
	}

	/**
	 * Construit un {@code SealedKey} portant {@code envelope}, en reprenant les métadonnées
	 * (type, algorithme, mode/padding, algorithme de signature) de la clé d'origine.
	 *
	 * @param original la clé en clair dont on reprend les métadonnées
	 * @param envelope l'enveloppe binaire produite par {@link KeyMaterialEnvelope#seal}
	 * @return un IKey de stockage exposant l'enveloppe et refusant toute opération crypto
	 */
	public static SealedKey from(IKey original, byte[] envelope) {
		Objects.requireNonNull(original, "original");
		Objects.requireNonNull(envelope, "envelope");
		return new SealedKey(original.getType(), original.getAlgorithm(), original.getEncryptionMode(),
				original.getEncryptionPaddingMode(), original.getSignatureAlgorithm(), envelope);
	}

	/** {@return les octets Base64-ASCII de l'enveloppe scellée} — consommés par la persistance. */
	@Override
	public byte[] getRawKey() {
		return this.rawKeyBase64.clone();
	}

	@Override
	public KeyType getType() {
		return this.type;
	}

	@Override
	public IKeyAlgorithm getAlgorithm() {
		return this.algorithm;
	}

	@Override
	public EncryptionMode getEncryptionMode() {
		return this.encryptionMode;
	}

	@Override
	public EncryptionPaddingMode getEncryptionPaddingMode() {
		return this.encryptionPaddingMode;
	}

	@Override
	public SignatureAlgorithm getSignatureAlgorithm() {
		return this.signatureAlgorithm;
	}

	@Override
	public java.security.Key getKey() throws CryptoException {
		throw sealed();
	}

	@Override
	public byte[] sign(byte[] data) throws CryptoException {
		throw sealed();
	}

	@Override
	public boolean verifySignature(byte[] signature, byte[] originalData) throws CryptoException {
		throw sealed();
	}

	@Override
	public byte[] encrypt(byte[] clear) throws CryptoException {
		throw sealed();
	}

	@Override
	public byte[] decrypt(byte[] encoded) throws CryptoException {
		throw sealed();
	}

	private static CryptoException sealed() {
		return new CryptoException(
				"SealedKey: matériel scellé au repos — descelle via KeyMaterialEnvelope.open() d'abord");
	}
}
