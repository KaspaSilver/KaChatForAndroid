package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Offline, no-network tests guarding the consensus-critical sighash/signature-script
 * fix. These are self-consistency checks (sign -> verify with our own [Schnorr.verify]);
 * they cannot substitute for confirming a real transaction on mainnet, but they catch
 * the class of bug that was here before (wrong field order/hash construction, missing
 * script framing) without spending real fees.
 */
class KaspaTransactionSignerTest {

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun fixture(amount: Long = 100_000_000L, gas: Long = 0): Triple<RawTransaction, List<UtxoEntry>, ByteArray> {
        val privateKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pubKeyXOnly = Schnorr.publicKeyXOnly(privateKey)
        val scriptPubKeyHex = "20" + pubKeyXOnly.joinToString("") { "%02x".format(it) } + "ac"

        val outpoint = Outpoint(transactionId = randomHex(32), index = 0)
        val utxo = UtxoEntry(
            address = "kaspa:test",
            outpoint = outpoint,
            utxoEntry = UtxoData(
                amount = amount,
                scriptPublicKey = ScriptPublicKey(scriptPubKeyHex),
                blockDaaScore = 0L,
                isCoinbase = false
            )
        )

        val rawTx = RawTransaction(
            inputs = listOf(RawInput(previousOutpoint = outpoint, signatureScript = "")),
            outputs = listOf(
                RawOutputWithVersion(
                    amount = amount - 10_000L,
                    scriptPublicKey = ScriptPublicKeyWithVersion(scriptPubKeyHex, 0)
                )
            ),
            gas = gas,
            payload = null
        )

        return Triple(rawTx, listOf(utxo), privateKey)
    }

    @Test
    fun `signature script has the required push-opcode and SIGHASH_ALL framing`() {
        val (rawTx, utxos, privateKey) = fixture()

        val signed = KaspaTransactionSigner.signTransaction(rawTx, utxos, privateKey)
        val sigScriptHex = signed.inputs[0].signatureScript

        // 66 raw bytes = 132 hex chars: 0x41 (push 65) || 64-byte sig || 0x01 (SIGHASH_ALL)
        assertEquals(132, sigScriptHex.length)
        assertEquals("41", sigScriptHex.substring(0, 2))
        assertEquals("01", sigScriptHex.substring(130, 132))
    }

    @Test
    fun `signed transaction verifies against its own sighash`() {
        val (rawTx, utxos, privateKey) = fixture()
        val pubKeyXOnly = Schnorr.publicKeyXOnly(privateKey)

        val signed = KaspaTransactionSigner.signTransaction(rawTx, utxos, privateKey)
        val sigScriptHex = signed.inputs[0].signatureScript
        val signatureBytes = sigScriptHex.substring(2, 130).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val sighash = KaspaTransactionSigner.calculateSighash(
            rawTx, 0, utxos[0].utxoEntry.amount, utxos[0].utxoEntry.scriptPublicKey.scriptPublicKey
        )

        assertTrue(Schnorr.verify(sighash, signatureBytes, pubKeyXOnly))
    }

    @Test
    fun `sighash changes when the amount changes`() {
        val (rawTx, utxos, _) = fixture(amount = 100_000_000L)
        val scriptPubKeyHex = utxos[0].utxoEntry.scriptPublicKey.scriptPublicKey

        val hashA = KaspaTransactionSigner.calculateSighash(rawTx, 0, 100_000_000L, scriptPubKeyHex)
        val hashB = KaspaTransactionSigner.calculateSighash(rawTx, 0, 100_000_001L, scriptPubKeyHex)

        assertFalse(hashA.contentEquals(hashB))
    }

    @Test
    fun `sighash changes when gas changes`() {
        val (rawTx, utxos, _) = fixture(gas = 0)
        val scriptPubKeyHex = utxos[0].utxoEntry.scriptPublicKey.scriptPublicKey

        val hashA = KaspaTransactionSigner.calculateSighash(rawTx, 0, utxos[0].utxoEntry.amount, scriptPubKeyHex)
        val hashB = KaspaTransactionSigner.calculateSighash(
            rawTx.copy(gas = 1), 0, utxos[0].utxoEntry.amount, scriptPubKeyHex
        )

        assertFalse(hashA.contentEquals(hashB))
    }

    // --- signRevealInput (KNS commit/reveal) -----------------------------------------

    private fun revealFixture(): Triple<RawTransaction, UtxoEntry, ByteArray> {
        val (rawTx, utxos, privateKey) = fixture()
        return Triple(rawTx, utxos[0], privateKey)
    }

    @Test
    fun `reveal signature script carries the push, sig, sighash type, and pushed redeem script`() {
        val (rawTx, commitUtxo, privateKey) = revealFixture()
        val redeemScript = ByteArray(40) { it.toByte() }

        val signed = KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, redeemScript, privateKey)
        val sigScriptHex = signed.inputs[0].signatureScript
        val sigScript = sigScriptHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertEquals(0x41, sigScript[0].toInt() and 0xff)
        assertEquals(0x01, sigScript[65].toInt() and 0xff)
        // redeem script is 40 bytes -> canonicalPush is [0x28, ...40 bytes] (0x28 = 40)
        assertEquals(0x28, sigScript[66].toInt() and 0xff)
        assertArrayEquals(redeemScript, sigScript.copyOfRange(67, 67 + 40))
        assertEquals(66 + 1 + 40, sigScript.size)
    }

    @Test
    fun `reveal signature verifies against the sighash of the commit output's real scriptPublicKey`() {
        val (rawTx, commitUtxo, privateKey) = revealFixture()
        val pubKeyXOnly = Schnorr.publicKeyXOnly(privateKey)
        val redeemScript = ByteArray(200) { it.toByte() } // exercises the OP_PUSHDATA1 branch (>75 bytes)

        val signed = KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, redeemScript, privateKey)
        val sigScript = signed.inputs[0].signatureScript.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signatureBytes = sigScript.copyOfRange(1, 65)

        // The sighash must use the COMMIT output's actual scriptPublicKey, NOT the redeem script.
        val sighash = KaspaTransactionSigner.calculateSighash(
            rawTx, 0, commitUtxo.utxoEntry.amount, commitUtxo.utxoEntry.scriptPublicKey.scriptPublicKey
        )
        assertTrue(Schnorr.verify(sighash, signatureBytes, pubKeyXOnly))
    }

    @Test
    fun `reveal signature script grows correctly for a large redeem script via OP_PUSHDATA2`() {
        val (rawTx, commitUtxo, privateKey) = revealFixture()
        val redeemScript = ByteArray(300) { it.toByte() }

        val signed = KaspaTransactionSigner.signRevealInput(rawTx, commitUtxo, redeemScript, privateKey)
        val sigScript = signed.inputs[0].signatureScript.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertEquals(0x4d, sigScript[66].toInt() and 0xff) // OP_PUSHDATA2
        assertEquals(66 + 3 + 300, sigScript.size)
    }
}
