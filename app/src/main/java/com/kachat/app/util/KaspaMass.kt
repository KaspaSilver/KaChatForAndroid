package com.kachat.app.util

/**
 * Kaspa transaction "compute mass" and fee calculation.
 *
 * Verified three ways: against rusty-kaspa's consensus constants
 * (`consensus/core/src/config/params.rs`: mass_per_tx_byte=1,
 * mass_per_script_pub_key_byte=10, mass_per_sig_op=1000) via the Kasia reference
 * implementation; against a real mainnet rejection this app hit for a small,
 * no-payload transaction — `calculateMass(numInputs=1, outputScriptLens=[34,34],
 * payloadSize=0)` reproduces the network's reported mass of exactly 2036; and against
 * a second real mainnet rejection for a large-payload (voice message) transaction —
 * see the `2 * byteSize` note below.
 *
 * The byte-size accounting below is Kaspa's fixed-width mass-calculation layout
 * (fixed 8-byte length/count fields throughout), NOT the real P2P wire varint
 * format — this is what kaspad's own mass calculator uses internally to price
 * transactions, not the serialized-for-broadcast format.
 */
object KaspaMass {
    private const val MASS_PER_TX_BYTE = 1L
    private const val MASS_PER_SCRIPT_PUB_KEY_BYTE = 10L
    private const val MASS_PER_SIG_OP = 1000L

    /** Network-enforced minimum relay fee: 100 sompi per mass-gram (rusty-kaspa `MINIMUM_RELAY_TRANSACTION_FEE`). */
    const val MINIMUM_FEE_RATE_SOMPI_PER_GRAM = 100L

    /** Fixed size of a Schnorr signature script: 0x41 push-opcode + 64-byte signature + 0x01 SIGHASH_ALL. */
    private const val SCHNORR_SIG_SCRIPT_LEN = 66L

    /**
     * @param numInputs number of transaction inputs
     * @param outputScriptLens byte length of each output's scriptPublicKey (34 for standard P2PK)
     * @param payloadSize byte length of the transaction payload (0 if none)
     * @param sigOpCountPerInput sigOpCount per input (1 for our single-sig P2PK spends)
     * @param sigScriptLens actual per-input signature-script byte length, if it differs from the
     *   standard 66-byte Schnorr push (e.g. a KNS reveal input, whose sigScript also carries the
     *   redeem script). Defaults to 66 bytes per input when omitted — every existing call site's
     *   already-verified mass/fee numbers are unaffected.
     */
    fun calculateMass(
        numInputs: Int,
        outputScriptLens: List<Int>,
        payloadSize: Int,
        sigOpCountPerInput: Int = 1,
        sigScriptLens: List<Long>? = null
    ): Long {
        val totalSigScriptBytes = sigScriptLens?.sum() ?: (numInputs * SCHNORR_SIG_SCRIPT_LEN)

        var byteSize = 2L // version (u16)
        byteSize += 8L // input count
        byteSize += numInputs * (36L + 8L + 8L) + totalSigScriptBytes // outpoint + sigscript-len field + sequence, + actual sigscript bytes
        byteSize += 8L // output count

        var scriptPubKeyMass = 0L
        for (scriptLen in outputScriptLens) {
            byteSize += 8L + 2L + 8L + scriptLen // value + scriptVersion + scriptLen + script
            scriptPubKeyMass += (2L + scriptLen) * MASS_PER_SCRIPT_PUB_KEY_BYTE
        }

        byteSize += 8L  // lockTime
        byteSize += 20L // subnetworkId
        byteSize += 8L  // gas
        byteSize += 32L // payload hash (fixed 32 bytes regardless of payload length)
        byteSize += 8L  // payload length
        byteSize += payloadSize

        val sigOpMass = numInputs.toLong() * sigOpCountPerInput * MASS_PER_SIG_OP
        val computeMass = byteSize * MASS_PER_TX_BYTE + scriptPubKeyMass + sigOpMass

        // Post-"Toccata" RPC minimum-standard-fee policy: the real fee floor is
        // 100 sompi * max(computeMass, 2 * transactionByteSize), not compute mass alone.
        // For small transactions compute mass (which adds ~10x-weighted scriptPubKey bytes
        // and 1000-weighted sigOps on top of raw byte size) always wins, so this never
        // mattered before. For a payload-heavy transaction — anything with a payload large
        // enough that byteSize dominates, like a voice message — 2*byteSize overtakes
        // compute mass and becomes the binding constraint. Verified against a real mainnet
        // rejection: numInputs=1, one 34-byte output, payloadSize=28729 produced
        // byteSize=28993 and the network required exactly 28993*2=57986 mass, not the
        // (lower) compute mass this formula alone would have produced.
        return maxOf(computeMass, byteSize * 2L)
    }

    /** fee = mass * max(networkMinimum, quotedFeeRate) — matches rusty-kaspa's fee-floor behavior. */
    fun calculateFee(mass: Long, quotedFeeRateSompiPerGram: Long?): Long {
        val effectiveRate = maxOf(MINIMUM_FEE_RATE_SOMPI_PER_GRAM, quotedFeeRateSompiPerGram ?: MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
        return mass * effectiveRate
    }
}
