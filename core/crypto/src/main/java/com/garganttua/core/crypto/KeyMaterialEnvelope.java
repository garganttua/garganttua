package com.garganttua.core.crypto;

import java.util.Arrays;

/**
 * Scelle et descelle du matériel de clé SECRET au repos autour d'une clé de
 * chiffrement (KEK) modélisée par un {@link IKeyRealm} — AES-GCM recommandé,
 * fourni hors base par l'application. Cet utilitaire est posé <b>au-dessus</b> du
 * chiffrement du cœur : {@code IKey.encrypt} génère et préfixe déjà le vecteur
 * d'initialisation (et, en GCM, le tag d'authentification), donc on n'ajoute
 * qu'un octet de version de KEK devant le bloc.
 *
 * <p>Format de l'enveloppe :
 * <pre>{@code [1 octet : version KEK][ sortie de IKey.encrypt = IV || chiffré || tag ]}</pre>
 *
 * <p>L'octet de version identifie la KEK ayant scellé le bloc. En v1 une seule KEK
 * est active : un octet de version étranger fait échouer le déscellage de façon
 * explicite (jamais un déchiffrement erroné). La rotation multi-KEK (résolution
 * version → KEK + ré-enveloppement du stock) viendra plus tard.
 */
public final class KeyMaterialEnvelope {

	private static final int MAX_KEK_VERSION = 255;

	private KeyMaterialEnvelope() {
	}

	/**
	 * Scelle des octets binaires en clair avec la KEK.
	 *
	 * @param kek          le realm servant de clé de chiffrement de clés
	 * @param clearMaterial le matériel binaire en clair à protéger
	 * @return l'enveloppe {@code [version][IV || chiffré || tag]}
	 * @throws CryptoException si la version de KEK ne tient pas sur un octet ou si le chiffrement échoue
	 */
	public static byte[] seal(IKeyRealm kek, byte[] clearMaterial) throws CryptoException {
		int version = kek.getVersion();
		if (version < 0 || version > MAX_KEK_VERSION) {
			throw new CryptoException("KeyMaterialEnvelope: version de KEK " + version
					+ " hors de l'octet de version [0.." + MAX_KEK_VERSION + "]");
		}
		byte[] body = kek.getKeyForEncryption().encrypt(clearMaterial); // IV (+ tag GCM) déjà inclus par le cœur
		byte[] out = new byte[1 + body.length];
		out[0] = (byte) version;
		System.arraycopy(body, 0, out, 1, body.length);
		return out;
	}

	/**
	 * Restitue les octets binaires en clair depuis une enveloppe produite par {@link #seal}.
	 *
	 * @param kek    le realm servant de clé de déchiffrement de clés (même KEK qu'au scellage)
	 * @param sealed l'enveloppe {@code [version][IV || chiffré || tag]}
	 * @return le matériel binaire en clair
	 * @throws CryptoException si l'enveloppe est tronquée, scellée par une autre version de KEK,
	 *                         ou si le déchiffrement / l'intégrité GCM échoue
	 */
	public static byte[] open(IKeyRealm kek, byte[] sealed) throws CryptoException {
		if (sealed == null || sealed.length < 2) {
			throw new CryptoException("KeyMaterialEnvelope: enveloppe absente ou tronquée");
		}
		int storedVersion = sealed[0] & 0xFF;
		int currentVersion = kek.getVersion();
		if (storedVersion != currentVersion) {
			throw new CryptoException("KeyMaterialEnvelope: matériel scellé avec la KEK v" + storedVersion
					+ " mais la KEK active est v" + currentVersion
					+ " — ré-enveloppement requis (rotation de KEK non gérée en v1)");
		}
		byte[] body = Arrays.copyOfRange(sealed, 1, sealed.length);
		return kek.getKeyForDecryption().decrypt(body);
	}
}
