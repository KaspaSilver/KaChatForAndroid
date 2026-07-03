package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduces rusty-kaspa's "subnetwork-all-0" known-answer sighash test vector
 * (consensus/core/src/hashing/sighash.rs test_signature_hash), which is the only
 * test vector with a NON-EMPTY payload — used to isolate a real-world signature
 * verification failure that only happens when a payload is attached.
 */
class KaspaSighashVectorTest {

    @Test
    fun `matches rusty-kaspa subnetwork-all-0 vector (non-empty payload)`() {
        val prevTxId = "880eb9819a31821d9d2399e2f35e2433b72637e393d71ecc9b8d0250f49153c3"
        val spk1 = "208325613d2eeaf7176ac6c670b13c0043156c427438ed72d74b7800862ad884e8ac"
        val spk2 = "20fcef4c106cf11135bbd70f02a726a92162d2fb8b22f0469126f800862ad884e8ac"

        val tx = RawTransaction(
            version = 0,
            inputs = listOf(
                RawInput(previousOutpoint = Outpoint(prevTxId, 0), signatureScript = "", sequence = 0, sigOpCount = 0),
                RawInput(previousOutpoint = Outpoint(prevTxId, 1), signatureScript = "", sequence = 1, sigOpCount = 0),
                RawInput(previousOutpoint = Outpoint(prevTxId, 2), signatureScript = "", sequence = 2, sigOpCount = 0)
            ),
            outputs = listOf(
                RawOutputWithVersion(amount = 300, scriptPublicKey = ScriptPublicKeyWithVersion(spk2, 0)),
                RawOutputWithVersion(amount = 300, scriptPublicKey = ScriptPublicKeyWithVersion(spk1, 0))
            ),
            lockTime = 1615462089000L,
            subnetworkId = "0102030405060708090a00000000000000000000",
            gas = 250L,
            payload = "0a0b0c0d0e0f1011121314"
        )

        val sighash = KaspaTransactionSigner.calculateSighash(
            tx = tx,
            inputIndex = 0,
            amount = 100L,
            scriptPublicKey = spk1
        )

        val expected = "b2f421c933eb7e1a91f1d9e1efa3f120fe419326c0dbac487752189522550e0c"
        assertEquals(expected, sighash.joinToString("") { "%02x".format(it) })
    }
}
