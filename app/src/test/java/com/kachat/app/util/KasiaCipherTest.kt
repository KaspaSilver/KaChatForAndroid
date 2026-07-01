package com.kachat.app.util

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

class KasiaCipherTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun randomScalarBytes(): ByteArray {
        val random = SecureRandom()
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d != BigInteger.ZERO && d < Secp256k1.N) return candidate
        }
    }

    /**
     * Validates that the BouncyCastle version on this classpath produces RFC-5869-correct
     * HKDF-SHA256 output (RFC 5869 Appendix A.1, Test Case 1) — confirms the underlying
     * primitive is wired correctly, independent of Kasia's empty-salt/info usage.
     */
    @Test
    fun `bouncycastle HKDF-SHA256 matches RFC 5869 test case 1`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expectedOkm = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865"
        )

        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val okm = ByteArray(42)
        generator.generateBytes(okm, 0, 42)

        assertArrayEquals(expectedOkm, okm)
    }

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val plaintext = "hello from android"

        val encrypted = KasiaCipher.encrypt(plaintext, recipientPub)

        assertEquals(12, encrypted.nonce.size)
        assertEquals(33, encrypted.ephemeralPublicKey.size)
        assertEquals(plaintext, KasiaCipher.decrypt(encrypted, recipientPriv))
    }

    @Test
    fun `wire bytes round-trip through toBytes and fromBytes`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val encrypted = KasiaCipher.encrypt("round trip", recipientPub)

        val parsed = KasiaCipher.EncryptedMessage.fromBytes(encrypted.toBytes())
        assertEquals(encrypted, parsed)
        assertEquals("round trip", KasiaCipher.decrypt(parsed!!, recipientPriv))
    }

    @Test
    fun `tampering with the ciphertext fails authentication`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val encrypted = KasiaCipher.encrypt("integrity check", recipientPub)

        val tampered = encrypted.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        try {
            KasiaCipher.decrypt(encrypted.copy(ciphertext = tampered), recipientPriv)
            fail("Expected AEAD authentication to fail for tampered ciphertext")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun `wrong recipient key cannot decrypt`() {
        val recipientPriv = randomScalarBytes()
        val recipientPub = Schnorr.publicKeyXOnly(recipientPriv)
        val wrongPriv = randomScalarBytes()

        val encrypted = KasiaCipher.encrypt("secret", recipientPub)

        try {
            KasiaCipher.decrypt(encrypted, wrongPriv)
            fail("Expected decryption to fail with the wrong private key")
        } catch (e: Exception) {
            // expected
        }
    }
}
