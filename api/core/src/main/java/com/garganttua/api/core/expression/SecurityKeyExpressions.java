package com.garganttua.api.core.expression;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainKeyDefinition;
import com.garganttua.core.crypto.CryptoException;
import com.garganttua.core.crypto.IKey;
import com.garganttua.core.crypto.IKeyAlgorithm;
import com.garganttua.core.crypto.IKeyRealm;
import com.garganttua.core.crypto.Key;
import com.garganttua.core.crypto.KeyAlgorithm;
import com.garganttua.core.crypto.KeyRealm;
import com.garganttua.core.crypto.KeyType;
import com.garganttua.core.crypto.SignatureAlgorithm;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Persisted {@code @Key} entity ↔ {@link IKeyRealm} bridge, split out of {@link SecurityExpressions}
 * to keep that expression registry under the file-size gate. Two operations:
 * <ul>
 *   <li>{@link #materializeKeyRealm} — reads the key fields described by an
 *       {@link IDomainKeyDefinition} and rebuilds an {@link IKeyRealm} via core's
 *       {@code KeyRealm.fromSignatureMaterial} (which caches the JDK key).</li>
 *   <li>{@link #generateAndStampKeyEntity} — generates a fresh JDK KeyPair, instantiates the entity
 *       class, and stamps realmName / algorithm / signature / key material / expiration / revoked.</li>
 * </ul>
 * Both are used exclusively by the resolve-persisted-key-realm path; tenancy stamping is performed
 * separately by {@code SecuritySigningExpressions.stampIdentityAndTenancy}. These are plain {@code static}
 * helpers (no {@code @Expression}), so moving them does not affect expression registration.
 */
@SuppressWarnings({"PMD.ReplaceJavaUtilDate", "PMD.AvoidDuplicateLiterals"})
final class SecurityKeyExpressions {

    private SecurityKeyExpressions() {
    }

    static IKeyRealm materializeKeyRealm(Object entity, IDomainKeyDefinition keyDef, IReflection reflection) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(keyDef, "keyDef");
        Objects.requireNonNull(reflection, "reflection");

        String name = readKeyString(entity, keyDef.name(), reflection, "name");
        String algorithmRaw = readKeyString(entity, keyDef.keyAlgorithm(), reflection, "keyAlgorithm");
        String signatureRaw = readKeyString(entity, keyDef.signatureAlgorithm(), reflection, "signatureAlgorithm");
        IKey signingKey = readKeyIKey(entity, keyDef.keyForSigning(), reflection, "keyForSigning");
        IKey verificationKey = readKeyIKey(entity, keyDef.keyForSignatureVerification(), reflection,
                "keyForSignatureVerification");
        Date expiration = readKeyExpiration(entity, keyDef.expiration(), reflection);
        boolean revoked = readKeyBoolean(entity, keyDef.revoked(), reflection);

        IKeyAlgorithm algorithm = parseKeyAlgorithm(algorithmRaw);
        SignatureAlgorithm sigAlgo = parseKeySignature(signatureRaw);

        // Extract JDK-encoded bytes from the IKey objects carried on the entity and rebuild a
        // fully-stitched IKeyRealm via core's factory. We do not pass the IKey instances directly:
        // KeyRealm.fromSignatureMaterial owns the Key construction (caching, type checks, algorithm
        // wiring), so we feed it the bytes and let it reconstruct.
        byte[] privateBytes;
        byte[] publicBytes;
        try {
            privateBytes = signingKey.getKey().getEncoded();
            publicBytes = verificationKey.getKey().getEncoded();
        } catch (CryptoException e) {
            throw new ApiException("materializeKeyRealm: failed to extract JDK-encoded bytes from "
                    + "the entity's IKey fields: " + e.getMessage(), e);
        }

        return KeyRealm.fromSignatureMaterial(name, algorithm, sigAlgo,
                expiration, revoked, privateBytes, publicBytes);
    }

    static Object generateAndStampKeyEntity(IClass<?> entityClass, IDomainKeyDefinition keyDef,
            IKeyAlgorithm algorithm, SignatureAlgorithm signatureAlgorithm,
            String realmName, int duration, TimeUnit unit, IReflection reflection) {
        Objects.requireNonNull(entityClass, "entityClass");
        Objects.requireNonNull(keyDef, "keyDef");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(signatureAlgorithm, "signatureAlgorithm");
        Objects.requireNonNull(realmName, "realmName");
        Objects.requireNonNull(reflection, "reflection");

        if (!(algorithm instanceof KeyAlgorithm concreteAlgo)) {
            throw new ApiException("generateAndStampKeyEntity: algorithm must be a "
                    + KeyAlgorithm.class.getName() + " — got " + algorithm.getClass().getName());
        }

        KeyPair pair = generateKeyPair(concreteAlgo);
        Object entity = instantiate(entityClass);

        writeIfMapped(entity, keyDef.name(), realmName, reflection);
        // Store the algorithm in the canonical 'NAME-SIZE' form KeyAlgorithm.validateKeyAlgorithm
        // consumes during materialize (KeyAlgorithm.toString uses underscores, which would be
        // rejected — serialize explicitly).
        writeIfMapped(entity, keyDef.keyAlgorithm(),
                concreteAlgo.getName() + "-" + concreteAlgo.getKeySize(), reflection);
        writeIfMapped(entity, keyDef.signatureAlgorithm(), signatureAlgorithm.name(), reflection);

        // Build IKey objects up front: the entity's key-material fields are typed IKey, not raw
        // byte[]. Persistence-side translation to byte[] is the DTO mapping's concern. For asymmetric
        // algorithms encryption reuses the signing key and decryption the verification key.
        IKey signingKey = Key.fromSigningMaterial(KeyType.PRIVATE, algorithm, signatureAlgorithm,
                pair.getPrivate().getEncoded());
        IKey verificationKey = Key.fromSigningMaterial(KeyType.PUBLIC, algorithm, signatureAlgorithm,
                pair.getPublic().getEncoded());
        writeIfMapped(entity, keyDef.keyForSigning(), signingKey, reflection);
        writeIfMapped(entity, keyDef.keyForSignatureVerification(), verificationKey, reflection);
        writeIfMapped(entity, keyDef.keyForEncryption(), signingKey, reflection);
        writeIfMapped(entity, keyDef.keyForDecryption(), verificationKey, reflection);

        ObjectAddress expirationAddr = keyDef.expiration();
        if (expirationAddr != null) {
            Instant exp = Instant.now().plusMillis(unit == null || duration <= 0 ? 0L : unit.toMillis(duration));
            reflection.setFieldValue(entity, expirationAddr,
                    adaptKeyExpiration(entityClass, expirationAddr, exp, reflection));
        }

        writeIfMapped(entity, keyDef.revoked(), Boolean.FALSE, reflection);
        // Initial version is 1 — matches IKeyRealm's default (incremented on rotate()). rotate() field
        // (last-rotation timestamp) stays null on a freshly minted key.
        writeIfMapped(entity, keyDef.version(), Integer.valueOf(1), reflection);

        return entity;
    }

    private static KeyPair generateKeyPair(KeyAlgorithm concreteAlgo) {
        try {
            return concreteAlgo.generateAsymmetricKey();
        } catch (Exception e) {
            throw new ApiException("generateAndStampKeyEntity: keypair generation failed for "
                    + concreteAlgo + ": " + e.getMessage(), e);
        }
    }

    private static Object instantiate(IClass<?> entityClass) {
        try {
            return entityClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new ApiException("generateAndStampKeyEntity: cannot instantiate "
                    + entityClass.getName() + " — a no-arg constructor is required: " + e.getMessage(), e);
        }
    }

    private static String readKeyString(Object entity, ObjectAddress addr, IReflection reflection, String label) {
        if (addr == null) {
            throw new ApiException("materializeKeyRealm: '" + label
                    + "' field is not configured on the key entity definition");
        }
        Object value = reflection.getFieldValue(entity, addr.toString());
        if (value == null) {
            throw new ApiException("materializeKeyRealm: '" + label + "' field at " + addr + " is null");
        }
        return value.toString();
    }

    private static IKey readKeyIKey(Object entity, ObjectAddress addr, IReflection reflection, String label) {
        if (addr == null) {
            throw new ApiException("materializeKeyRealm: '" + label
                    + "' field is not configured on the key entity definition");
        }
        Object value = reflection.getFieldValue(entity, addr.toString());
        if (!(value instanceof IKey key)) {
            throw new ApiException("materializeKeyRealm: '" + label + "' at " + addr
                    + " must be an IKey — got " + (value == null ? "null" : value.getClass().getName()));
        }
        return key;
    }

    private static Date readKeyExpiration(Object entity, ObjectAddress addr, IReflection reflection) {
        if (addr == null) {
            return null;
        }
        Object value = reflection.getFieldValue(entity, addr.toString());
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return (Date) date.clone();
        }
        if (value instanceof Instant instant) {
            return Date.from(instant);
        }
        if (value instanceof Long millis) {
            return new Date(millis);
        }
        throw new ApiException("materializeKeyRealm: expiration at " + addr
                + " must be Date / Instant / Long — got " + value.getClass().getName());
    }

    private static boolean readKeyBoolean(Object entity, ObjectAddress addr, IReflection reflection) {
        if (addr == null) {
            return false;
        }
        return Boolean.TRUE.equals(reflection.getFieldValue(entity, addr.toString()));
    }

    private static void writeIfMapped(Object entity, ObjectAddress addr, Object value, IReflection reflection) {
        if (addr != null) {
            reflection.setFieldValue(entity, addr, value);
        }
    }

    private static Object adaptKeyExpiration(IClass<?> entityClass, ObjectAddress addr, Instant exp,
            IReflection reflection) {
        var fieldOpt = reflection.findField(entityClass, addr.toString());
        if (fieldOpt.isEmpty()) {
            return exp;
        }
        java.lang.reflect.Type rawType = fieldOpt.get().getType().getType();
        if (!(rawType instanceof Class<?> targetType)) {
            return exp;
        }
        if (Instant.class.isAssignableFrom(targetType)) {
            return exp;
        }
        if (Date.class.isAssignableFrom(targetType)) {
            return Date.from(exp);
        }
        if (Long.class.isAssignableFrom(targetType) || targetType == long.class) {
            return exp.toEpochMilli();
        }
        throw new ApiException("generateAndStampKeyEntity: cannot adapt expiration to "
                + targetType.getName() + " — supported: Date, Instant, Long");
    }

    private static IKeyAlgorithm parseKeyAlgorithm(String raw) {
        try {
            return KeyAlgorithm.validateKeyAlgorithm(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException("materializeKeyRealm: invalid algorithm '" + raw
                    + "' — expected format 'NAME-SIZE' (e.g. RSA-2048, EC-256): " + e.getMessage(), e);
        }
    }

    private static SignatureAlgorithm parseKeySignature(String raw) {
        try {
            return SignatureAlgorithm.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException("materializeKeyRealm: invalid signatureAlgorithm '" + raw
                    + "' — must be a SignatureAlgorithm enum name (e.g. SHA256, SHA512): " + e.getMessage(), e);
        }
    }
}
