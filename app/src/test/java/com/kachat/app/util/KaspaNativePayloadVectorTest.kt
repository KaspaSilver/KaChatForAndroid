package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKeyWithVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reproduces rusty-kaspa's "native-all-0-modify-payload" known-answer sighash test
 * vector (consensus/core/src/hashing/sighash.rs test_signature_hash) — the correct
 * apples-to-apples vector for a NATIVE-subnetwork transaction carrying a non-empty
 * payload (the earlier "subnetwork-all-0" vector I checked uses a non-native
 * subnetwork, which doesn't exercise the exact same case as a real ciph_msg send).
 */
class KaspaNativePayloadVectorTest {

    @Test
    fun `matches rusty-kaspa native-all-0-modify-payload vector`() {
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
            subnetworkId = "0000000000000000000000000000000000000000", // SUBNETWORK_ID_NATIVE
            gas = 0L,
            payload = "06060604020001030307" // ModifyAction::Payload -> vec![6,6,6,4,2,0,1,3,3,7]
        )

        val sighash = KaspaTransactionSigner.calculateSighash(
            tx = tx,
            inputIndex = 0,
            amount = 100L,
            scriptPublicKey = spk1
        )

        val expected = "72ea6c2871e0f44499f1c2b556f265d9424bfea67cca9cb343b4b040ead65525"
        assertEquals(expected, sighash.joinToString("") { "%02x".format(it) })
    }
}
