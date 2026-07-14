package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KsptCodecTest {

    private val schnorrSpk = "20" + "aa".repeat(32) + "ac" // standard P2PK scriptPublicKey, 34 bytes
    private val txId = "bb".repeat(32)

    private fun sampleInput(amount: Long = 100_000_000L) = KsptCodec.UnsignedInput(
        prevTxId = txId,
        prevIndex = 0,
        amountSompi = amount,
        sequence = 0,
        sigOpCount = 1,
        spkVersion = 0,
        spkScriptHex = schnorrSpk
    )

    private fun sampleOutput(value: Long = 50_000_000L) = KsptCodec.UnsignedOutput(
        valueSompi = value,
        spkVersion = 0,
        spkScriptHex = schnorrSpk
    )

    @Test
    fun `encodes and decodes an unsigned single-input single-output tx round trip`() {
        val bytes = KsptCodec.encodeUnsigned(
            txVersion = 0,
            lockTime = 0,
            subnetworkIdHex = "00".repeat(20),
            gas = 0,
            payloadHex = null,
            inputs = listOf(sampleInput()),
            outputs = listOf(sampleOutput())
        )

        assertTrue(KsptCodec.looksLikeKspt(bytes))
        val decoded = KsptCodec.decode(bytes).getOrThrow()

        assertFalse(decoded.signed)
        assertEquals(1, decoded.inputs.size)
        assertEquals(1, decoded.outputs.size)
        assertEquals(txId, decoded.inputs[0].prevTxId)
        assertEquals(100_000_000L, decoded.inputs[0].amountSompi)
        assertEquals(schnorrSpk, decoded.inputs[0].spkScriptHex)
        assertNull(decoded.inputs[0].signatureHex)
        assertEquals(50_000_000L, decoded.outputs[0].valueSompi)
        assertEquals(schnorrSpk, decoded.outputs[0].spkScriptHex)
    }

    @Test
    fun `decodes a signed response with per-input signature and sighash type`() {
        val unsigned = KsptCodec.encodeUnsigned(
            txVersion = 0, lockTime = 0, subnetworkIdHex = "00".repeat(20), gas = 0, payloadHex = null,
            inputs = listOf(sampleInput()), outputs = listOf(sampleOutput())
        )
        val sigHex = "cc".repeat(64)
        val signed = fakeSign(unsigned, sigHex, sighashType = 0x01)

        val decoded = KsptCodec.decode(signed).getOrThrow()
        assertTrue(decoded.signed)
        assertEquals(sigHex, decoded.inputs[0].signatureHex)
        assertEquals(0x01, decoded.inputs[0].sighashType)
    }

    @Test
    fun `round trips maximum-size inputs and outputs`() {
        val inputs = (0 until KsptCodec.MAX_INPUTS).map { sampleInput(amount = (it + 1) * 1_000_000L) }
        val outputs = (0 until KsptCodec.MAX_OUTPUTS).map { sampleOutput(value = (it + 1) * 500_000L) }
        val bytes = KsptCodec.encodeUnsigned(
            txVersion = 0, lockTime = 12345L, subnetworkIdHex = "00".repeat(20), gas = 0, payloadHex = null,
            inputs = inputs, outputs = outputs
        )
        val decoded = KsptCodec.decode(bytes).getOrThrow()
        assertEquals(KsptCodec.MAX_INPUTS, decoded.inputs.size)
        assertEquals(KsptCodec.MAX_OUTPUTS, decoded.outputs.size)
        assertEquals(12345L, decoded.lockTime)
    }

    @Test
    fun `rejects more than the maximum input count`() {
        val inputs = (0..KsptCodec.MAX_INPUTS).map { sampleInput() } // one over the limit
        try {
            KsptCodec.encodeUnsigned(
                txVersion = 0, lockTime = 0, subnetworkIdHex = "00".repeat(20), gas = 0, payloadHex = null,
                inputs = inputs, outputs = listOf(sampleOutput())
            )
            org.junit.Assert.fail("expected an exception for too many inputs")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `decode fails on garbage that doesn't start with the KSPT magic`() {
        val result = KsptCodec.decode(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(result.isFailure)
    }

    @Test
    fun `looksLikeKspt is false for short or non-matching byte arrays`() {
        assertFalse(KsptCodec.looksLikeKspt(byteArrayOf(1, 2)))
        assertFalse(KsptCodec.looksLikeKspt("NOPE".toByteArray()))
        assertTrue(KsptCodec.looksLikeKspt("KSPT-anything-after".toByteArray()))
    }

    /**
     * Mimics what a signing device does: decode the unsigned bytes structurally, then
     * re-serialize with flags="signed" and a sig/sighashType appended per input — using the same
     * byte layout [KsptCodec.decode] expects, independent of [KsptCodec.encodeUnsigned] itself.
     */
    private fun fakeSign(unsigned: ByteArray, sigHex: String, sighashType: Int): ByteArray {
        val decoded = KsptCodec.decode(unsigned).getOrThrow()
        val out = java.io.ByteArrayOutputStream()
        out.write(KsptCodec.MAGIC)
        out.write(0x01)
        out.write(0x01) // signed
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        u16(decoded.txVersion)
        out.write(decoded.inputs.size)
        out.write(decoded.outputs.size)
        u64(decoded.lockTime)
        out.write(decoded.subnetworkIdHex.hexToBytesTest())
        u64(decoded.gas)
        val payloadBytes = decoded.payloadHex.hexToBytesTest()
        u16(payloadBytes.size)
        out.write(payloadBytes)
        decoded.inputs.forEach { input ->
            out.write(input.prevTxId.hexToBytesTest())
            for (i in 0 until 4) out.write(((input.prevIndex shr (8 * i)) and 0xFF))
            u64(input.amountSompi)
            u64(input.sequence)
            out.write(input.sigOpCount)
            u16(input.spkVersion)
            val spk = input.spkScriptHex.hexToBytesTest()
            out.write(spk.size)
            out.write(spk)
            val sigBytes = sigHex.hexToBytesTest()
            out.write(sigBytes.size)
            out.write(sigBytes)
            out.write(sighashType)
        }
        decoded.outputs.forEach { output ->
            u64(output.valueSompi)
            u16(output.spkVersion)
            val spk = output.spkScriptHex.hexToBytesTest()
            out.write(spk.size)
            out.write(spk)
        }
        return out.toByteArray()
    }

    private fun String.hexToBytesTest(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
