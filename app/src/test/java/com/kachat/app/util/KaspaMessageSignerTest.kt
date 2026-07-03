package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class KaspaMessageSignerTest {

    private fun randomPrivateKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `signature is a 128-char lowercase hex string for every mode`() {
        val privateKey = randomPrivateKey()
        val message = """{"assetId":"abc123","uploadType":"avatar"}"""
        for (mode in KaspaMessageSigner.SigningMode.entries) {
            val sig = KaspaMessageSigner.sign(message, privateKey, mode)
            assertEquals(128, sig.length)
            assertTrue(sig.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test
    fun `kaspaPersonalMessage mode verifies against its own keyed-blake2b digest`() {
        val privateKey = randomPrivateKey()
        val pubKey = Schnorr.publicKeyXOnly(privateKey)
        val message = """{"assetId":"abc123","uploadType":"avatar"}"""

        val sigHex = KaspaMessageSigner.sign(message, privateKey, KaspaMessageSigner.SigningMode.KASPA_PERSONAL_MESSAGE)
        val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val digest = org.bouncycastle.crypto.digests.Blake2bDigest("PersonalMessageSigningHash".toByteArray(Charsets.US_ASCII), 32, null, null)
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        digest.update(messageBytes, 0, messageBytes.size)
        val expectedDigest = ByteArray(32)
        digest.doFinal(expectedDigest, 0)

        assertTrue(Schnorr.verify(expectedDigest, sigBytes, pubKey))
    }

    @Test
    fun `rawUTF8 mode verifies against the raw message bytes directly`() {
        val privateKey = randomPrivateKey()
        val pubKey = Schnorr.publicKeyXOnly(privateKey)
        val message = """{"assetId":"abc123","uploadType":"banner"}"""

        val sigHex = KaspaMessageSigner.sign(message, privateKey, KaspaMessageSigner.SigningMode.RAW_UTF8)
        val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertTrue(Schnorr.verify(message.toByteArray(Charsets.UTF_8), sigBytes, pubKey))
    }

    @Test
    fun `sha256Digest mode verifies against the SHA-256 hash of the message`() {
        val privateKey = randomPrivateKey()
        val pubKey = Schnorr.publicKeyXOnly(privateKey)
        val message = """{"assetId":"abc123","uploadType":"avatar"}"""

        val sigHex = KaspaMessageSigner.sign(message, privateKey, KaspaMessageSigner.SigningMode.SHA256_DIGEST)
        val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256").digest(message.toByteArray(Charsets.UTF_8))
        assertTrue(Schnorr.verify(expectedDigest, sigBytes, pubKey))
    }

    @Test
    fun `different modes produce different signatures for the same message`() {
        val privateKey = randomPrivateKey()
        val message = """{"assetId":"abc123","uploadType":"avatar"}"""
        val sigs = KaspaMessageSigner.SigningMode.entries.map { KaspaMessageSigner.sign(message, privateKey, it) }
        assertEquals(sigs.size, sigs.toSet().size) // all distinct
    }

    @Test
    fun `a different message produces a different signature`() {
        val privateKey = randomPrivateKey()
        val sigA = KaspaMessageSigner.sign("""{"assetId":"a","uploadType":"avatar"}""", privateKey, KaspaMessageSigner.SigningMode.RAW_UTF8)
        val sigB = KaspaMessageSigner.sign("""{"assetId":"b","uploadType":"avatar"}""", privateKey, KaspaMessageSigner.SigningMode.RAW_UTF8)
        assertNotEquals(sigA, sigB)
    }
}
