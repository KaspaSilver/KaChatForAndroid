package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test pinned to a real mainnet rejection: a 1-input, 2-output (34-byte
 * P2PK scripts each), no-payload transaction was rejected with
 * "requires 203600 for compute mass 2036" — i.e. the network computed mass=2036
 * for this exact shape. If this test ever fails, the mass formula has drifted
 * from what the live network actually enforces.
 */
class KaspaMassTest {

    @Test
    fun `matches the real network-reported mass for a 1-input 2-output no-payload transaction`() {
        val mass = KaspaMass.calculateMass(
            numInputs = 1,
            outputScriptLens = listOf(34, 34),
            payloadSize = 0
        )

        assertEquals(2036L, mass)
        assertEquals(203_600L, KaspaMass.calculateFee(mass, quotedFeeRateSompiPerGram = 100L))
    }

    @Test
    fun `fee never drops below the network minimum even if a quoted rate is lower`() {
        val mass = KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)

        assertEquals(203_600L, KaspaMass.calculateFee(mass, quotedFeeRateSompiPerGram = 1L))
        assertEquals(203_600L, KaspaMass.calculateFee(mass, quotedFeeRateSompiPerGram = null))
    }

    @Test
    fun `mass increases with payload size`() {
        val withoutPayload = KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
        val withPayload = KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 100)

        assertEquals(withoutPayload + 100L, withPayload)
    }

    /**
     * Regression test pinned to a second real mainnet rejection: a 1-input, 1-output
     * (34-byte P2PK), 28729-byte-payload voice message transaction was rejected — the
     * network required mass 57986, exactly 2x the raw transaction byte size (28993),
     * not the (lower) compute-mass-only value this formula previously returned. This is
     * the post-"Toccata" RPC minimum-standard-fee rule: max(computeMass, 2*byteSize).
     */
    @Test
    fun `large payload transactions are priced by 2x byte size, not compute mass alone`() {
        val mass = KaspaMass.calculateMass(
            numInputs = 1,
            outputScriptLens = listOf(34),
            payloadSize = 28_729
        )

        assertEquals(57_986L, mass)
        assertEquals(5_798_600L, KaspaMass.calculateFee(mass, quotedFeeRateSompiPerGram = 100L))
    }

    @Test
    fun `a small transaction is still priced by compute mass, since 2x byte size never dominates there`() {
        val mass = KaspaMass.calculateMass(numInputs = 1, outputScriptLens = listOf(34, 34), payloadSize = 0)
        // byteSize here is 316, so 2*316=632 — well under the compute mass of 2036.
        assertEquals(2036L, mass)
    }
}
