package com.kachat.app.util

import org.bitcoinj.base.Base58
import org.bitcoinj.crypto.ECKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class KaspaExtendedPublicKeyTest {

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // A real (freshly generated, throwaway) secp256k1 public key — the actual point must be
    // valid on the curve for HD child derivation's EC math to succeed, unlike the chain code
    // which is just an opaque 32-byte diversifier.
    private val samplePubKey: ByteArray = ECKey().pubKey

    private fun buildKpubFixture(
        version: ByteArray = byteArrayOf(0x03, 0x8f.toByte(), 0x33, 0x2e),
        depth: Int = 3,
        parentFingerprint: Int = 0x11223344,
        childNumber: Int = -0x80000000, // hardened index 0, i.e. 0x80000000
        chainCode: ByteArray = ByteArray(32) { it.toByte() },
        pubKey: ByteArray = samplePubKey
    ): String {
        val buffer = ByteBuffer.allocate(78)
        buffer.put(version)
        buffer.put(depth.toByte())
        buffer.putInt(parentFingerprint)
        buffer.putInt(childNumber)
        buffer.put(chainCode)
        buffer.put(pubKey)
        val payload = buffer.array()
        val checksum = sha256(sha256(payload)).copyOfRange(0, 4)
        return Base58.encode(payload + checksum)
    }

    @Test
    fun `parses a well-formed kpub fixture`() {
        val kpub = buildKpubFixture()
        val result = KaspaExtendedPublicKey.parse(kpub)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertEquals(3, parsed.depth)
        assertEquals(32, parsed.chainCode.size)
        assertEquals(33, parsed.compressedPubKey.size)
        assertEquals(0x11223344, parsed.parentFingerprint)
    }

    @Test
    fun `rejects wrong version bytes`() {
        // Standard Bitcoin xpub version, not Kaspa's kpub version — must be rejected.
        val kpub = buildKpubFixture(version = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E))
        assertTrue(KaspaExtendedPublicKey.parse(kpub).isFailure)
    }

    @Test
    fun `rejects a corrupted checksum`() {
        val kpub = buildKpubFixture()
        val corrupted = kpub.dropLast(1) + (if (kpub.last() == '9') "8" else "9")
        assertTrue(KaspaExtendedPublicKey.parse(corrupted).isFailure)
    }

    @Test
    fun `isValidKpub matches parse result`() {
        assertTrue(KaspaExtendedPublicKey.isValidKpub(buildKpubFixture()))
        assertFalse(KaspaExtendedPublicKey.isValidKpub("not-a-kpub"))
    }

    @Test
    fun `derives distinct but deterministic addresses per index`() {
        val parsed = KaspaExtendedPublicKey.parse(buildKpubFixture()).getOrThrow()
        val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)

        val address0 = KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = 0)
        val address1 = KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = 1)
        val address0Again = KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = 0)

        assertTrue(address0.startsWith("kaspa:"))
        assertNotEquals(address0, address1)
        assertEquals(address0, address0Again)
    }

    @Test
    fun `external and internal chains derive different addresses`() {
        val parsed = KaspaExtendedPublicKey.parse(buildKpubFixture()).getOrThrow()
        val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)

        val external = KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = 0)
        val internal = KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 1, index = 0)
        assertNotEquals(external, internal)
    }
}
