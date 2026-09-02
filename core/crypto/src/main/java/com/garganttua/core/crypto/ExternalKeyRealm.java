package com.garganttua.core.crypto;

import java.util.Date;
import java.util.Objects;

import com.garganttua.core.observability.Logger;

/**
 * A realm whose key material lives OUTSIDE the process — a PKCS#11 token, an HSM, a KMS.
 *
 * <p><b>Why it exists.</b> {@link KeyRealm} is built from bytes: its factories take PKCS#8 and
 * X.509 material and rebuild {@link Key} instances around it. That is the right shape for material
 * this process owns, and the wrong one — in fact an impossible one — for material it must never
 * hold. A key sealed in a token has no bytes to give: {@code getEncoded()} returns {@code null},
 * by design. Without this realm, «&nbsp;the private key never leaves its holder&nbsp;» cannot be
 * expressed at all, whatever the storage underneath.
 *
 * <p><b>What it does.</b> Nothing but carry the two {@link IKey}s it was handed, plus the metadata
 * a realm answers for. Signing and verification are delegated to those keys, which is where the
 * external holder is reached. The realm never touches material, never copies it, and cannot leak
 * what it does not have.
 *
 * <p><b>Rotation.</b> {@link #rotate()} REFUSES. A realm generates its successor by creating a key
 * pair — and a pair that must be born inside a token cannot be born here. Rotating an external
 * realm is an operation of its holder (generate in the token, then point the realm at the new
 * handle), and pretending otherwise would silently produce an in-memory pair, that is, exactly the
 * key this realm exists to avoid.
 *
 * @since 3.0.0-ALPHA16
 */
@SuppressWarnings("PMD.ReplaceJavaUtilDate")
public final class ExternalKeyRealm implements IKeyRealm {

	private static final Logger log = Logger.getLogger(ExternalKeyRealm.class);

	private final String name;

	private final IKeyAlgorithm keyAlgorithm;

	private final IKey signingKey;

	private final IKey verificationKey;

	private final Date expiration;

	private final int version;

	private boolean revoked;

	private ExternalKeyRealm(String name, IKeyAlgorithm keyAlgorithm, IKey signingKey,
			IKey verificationKey, Date expiration, boolean revoked, int version) {
		this.name = name;
		this.keyAlgorithm = keyAlgorithm;
		this.signingKey = signingKey;
		this.verificationKey = verificationKey;
		this.expiration = copy(expiration);
		this.revoked = revoked;
		this.version = version;
	}

	/**
	 * Wraps two keys held elsewhere into a realm.
	 *
	 * @param name            realm identifier, e.g. {@code "users:global"}
	 * @param algorithm       algorithm the external pair was generated for
	 * @param signingKey      the key that signs — its material may be unreachable
	 * @param verificationKey the key that verifies — public material, usually readable
	 * @param expiration      absolute expiration date, or {@code null}
	 * @param revoked         revocation flag at load time
	 * @return a realm delegating every cryptographic operation to the supplied keys
	 */
	public static ExternalKeyRealm of(String name, IKeyAlgorithm algorithm, IKey signingKey,
			IKey verificationKey, Date expiration, boolean revoked) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(algorithm, "algorithm");
		Objects.requireNonNull(signingKey, "signingKey");
		Objects.requireNonNull(verificationKey, "verificationKey");
		log.debug("Wrapping external key realm name={}, algorithm={}, revoked={}", name, algorithm, revoked);
		return new ExternalKeyRealm(name, algorithm, signingKey, verificationKey, expiration, revoked, 1);
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public IKeyAlgorithm getKeyAlgorithm() {
		return this.keyAlgorithm;
	}

	@Override
	public IKey getKeyForSigning() throws CryptoException {
		this.assertUsable();
		return this.signingKey;
	}

	@Override
	public IKey getKeyForSignatureVerification() throws CryptoException {
		this.assertUsable();
		return this.verificationKey;
	}

	/** {@return the signing key — an asymmetric realm encrypts with the same key it signs with} */
	@Override
	public IKey getKeyForEncryption() throws CryptoException {
		return this.getKeyForSigning();
	}

	/** {@return the verification key — the counterpart of {@link #getKeyForEncryption()}} */
	@Override
	public IKey getKeyForDecryption() throws CryptoException {
		return this.getKeyForSignatureVerification();
	}

	@Override
	public void revoke() {
		this.revoked = true;
		log.warn("External key realm {} has been revoked", this.name);
	}

	@Override
	public boolean isRevoked() {
		return this.revoked;
	}

	@Override
	public Date getExpiration() {
		return copy(this.expiration);
	}

	/**
	 * Copie defensive d'une date — {@code null} traverse tel quel.
	 *
	 * <p>Un realm rend sa date d'echeance a qui la demande : rendre l'instance interne laisserait
	 * l'appelant la modifier, et l'expiration d'une cle de signature n'est pas une donnee qu'on
	 * laisse muter depuis l'exterieur.
	 */
	private static Date copy(Date date) {
		return date == null ? null : new Date(date.getTime());
	}

	@Override
	public boolean isExpired() {
		return this.expiration != null && new Date().after(this.expiration);
	}

	@Override
	public int getVersion() {
		return this.version;
	}

	/**
	 * REFUSES: a realm whose material lives elsewhere rotates in its holder, not here.
	 *
	 * @return never returns
	 * @throws CryptoException always
	 */
	@Override
	public IKeyRealm rotate() {
		throw new CryptoException("The key realm " + this.name + " holds external material: rotate it "
				+ "in its holder (generate the new pair inside the token, then point the realm at the "
				+ "new handle). Rotating here would generate an in-memory pair, which is exactly what "
				+ "an external realm exists to avoid.");
	}

	private void assertUsable() throws CryptoException {
		if (this.revoked) {
			throw new CryptoException("The key for realm " + this.name + " is revoked");
		}
		if (this.isExpired()) {
			throw new CryptoException("The key for realm " + this.name + " has expired");
		}
	}
}
