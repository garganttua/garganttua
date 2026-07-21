package com.garganttua.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KeyMaterialEnvelope} and {@link SealedKey}: the at-rest sealing
 * of SECRET key material around an AES-256/GCM key-encrypting key (KEK).
 */
@DisplayName("Key material envelope + sealed key")
class KeyMaterialEnvelopeTest {

    private static IKeyRealm kek(String name) {
        return KeyRealmBuilder.builder()
                .name(name)
                .algorithm(KeyAlgorithm.AES_256)
                .encryptionMode(EncryptionMode.GCM)
                .paddingMode(EncryptionPaddingMode.NO_PADDING)
                .initializationVectorSize(12) // GCM: 96-bit nonce, prepended by the core Encryptor
                .build();
    }

    @Nested
    @DisplayName("KeyMaterialEnvelope round-trip")
    class Envelope {

        @Test
        @DisplayName("seal then open restores the exact clear material")
        void roundTrip() {
            IKeyRealm kek = kek("kek-roundtrip");
            byte[] clear = "super-secret-private-key-bytes".getBytes(StandardCharsets.UTF_8);

            byte[] sealed = KeyMaterialEnvelope.seal(kek, clear);
            assertFalse(java.util.Arrays.equals(clear, sealed), "sealed bytes must differ from clear");
            assertTrue(sealed.length > clear.length, "envelope carries version + IV + tag overhead");
            assertEquals((byte) kek.getVersion(), sealed[0], "first byte is the KEK version");

            byte[] opened = KeyMaterialEnvelope.open(kek, sealed);
            assertArrayEquals(clear, opened, "open must restore the exact clear material");
        }

        @Test
        @DisplayName("two seals of the same material differ (fresh IV per seal)")
        void freshIvPerSeal() {
            IKeyRealm kek = kek("kek-iv");
            byte[] clear = "same-input".getBytes(StandardCharsets.UTF_8);
            byte[] a = KeyMaterialEnvelope.seal(kek, clear);
            byte[] b = KeyMaterialEnvelope.seal(kek, clear);
            assertFalse(java.util.Arrays.equals(a, b), "GCM must use a fresh IV, so ciphertexts differ");
            assertArrayEquals(clear, KeyMaterialEnvelope.open(kek, a));
            assertArrayEquals(clear, KeyMaterialEnvelope.open(kek, b));
        }

        @Test
        @DisplayName("a different KEK cannot open the envelope (GCM integrity)")
        void wrongKekRejected() {
            IKeyRealm kek = kek("kek-a");
            IKeyRealm other = kek("kek-b");
            byte[] sealed = KeyMaterialEnvelope.seal(kek, "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(CryptoException.class, () -> KeyMaterialEnvelope.open(other, sealed),
                    "the wrong KEK must fail the authenticated decryption");
        }

        @Test
        @DisplayName("a rotated KEK (new version) refuses an old-version envelope explicitly")
        void versionMismatchRejected() {
            IKeyRealm kek = kek("kek-rot");
            byte[] sealed = KeyMaterialEnvelope.seal(kek, "y".getBytes(StandardCharsets.UTF_8));
            IKeyRealm rotated = kek.rotate();
            assertTrue(rotated.getVersion() != kek.getVersion(), "precondition: rotate changed the version");
            CryptoException ex = assertThrows(CryptoException.class,
                    () -> KeyMaterialEnvelope.open(rotated, sealed));
            assertTrue(ex.getMessage().contains("ré-enveloppement"),
                    "error must name the KEK-version mismatch — got: " + ex.getMessage());
        }

        @Test
        @DisplayName("a truncated envelope is rejected")
        void truncatedRejected() {
            IKeyRealm kek = kek("kek-trunc");
            assertThrows(CryptoException.class, () -> KeyMaterialEnvelope.open(kek, new byte[] { 1 }));
        }
    }

    @Nested
    @DisplayName("SealedKey storage carrier")
    class Sealed {

        @Test
        @DisplayName("carries the envelope Base64-encoded and preserves the original KeyType")
        void carriesEnvelopePreservesType() {
            IKeyRealm kek = kek("kek-sealedkey");
            byte[] clear = "private".getBytes(StandardCharsets.UTF_8);
            byte[] envelope = KeyMaterialEnvelope.seal(kek, clear);

            IKey original = Key.fromSigningMaterial(KeyType.PRIVATE, KeyAlgorithm.EC_256,
                    SignatureAlgorithm.SHA256, "jdk-encoded".getBytes(StandardCharsets.UTF_8));
            SealedKey sealed = SealedKey.from(original, envelope);

            assertEquals(KeyType.PRIVATE, sealed.getType(), "KeyType must survive so materialize knows to open");
            assertNotNull(sealed.getRawKey());
            // getRawKey() is Base64-ASCII of the envelope; decoding it yields the envelope back.
            byte[] decoded = Base64.getDecoder().decode(sealed.getRawKey());
            assertArrayEquals(envelope, decoded, "getRawKey must Base64-carry the exact envelope");
            // ...and that envelope opens to the original clear material.
            assertArrayEquals(clear, KeyMaterialEnvelope.open(kek, decoded));
        }

        @Test
        @DisplayName("refuses every crypto operation — never exposes clear material")
        void refusesCryptoOps() {
            IKey original = Key.fromSigningMaterial(KeyType.PRIVATE, KeyAlgorithm.EC_256,
                    SignatureAlgorithm.SHA256, "x".getBytes(StandardCharsets.UTF_8));
            SealedKey sealed = SealedKey.from(original, new byte[] { 0, 1, 2, 3 });
            assertThrows(CryptoException.class, sealed::getKey);
            assertThrows(CryptoException.class, () -> sealed.sign(new byte[] { 1 }));
            assertThrows(CryptoException.class, () -> sealed.encrypt(new byte[] { 1 }));
            assertThrows(CryptoException.class, () -> sealed.decrypt(new byte[] { 1 }));
            assertThrows(CryptoException.class, () -> sealed.verifySignature(new byte[] { 1 }, new byte[] { 2 }));
        }
    }
}
