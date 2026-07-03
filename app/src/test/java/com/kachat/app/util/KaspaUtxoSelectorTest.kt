package com.kachat.app.util

import com.kachat.app.services.Outpoint
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KaspaUtxoSelectorTest {

    private fun utxo(amount: Long, txId: String = "a", index: Int = 0) = UtxoEntry(
        address = "kaspa:test",
        outpoint = Outpoint(transactionId = txId + index, index = index),
        utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey("aa"), blockDaaScore = 0, isCoinbase = false)
    )

    @Test
    fun `selects the fewest large UTXOs needed to cover amount plus fee`() {
        val utxos = listOf(utxo(100_000_000L, "a"), utxo(50_000_000L, "b"), utxo(10_000_000L, "c"))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(1, result.selectedUtxos.size) // the 100M one alone covers 10M + fee
        assertEquals(100_000_000L, result.totalSelected)
    }

    @Test
    fun `change equals total minus final amount minus fee`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(result.totalSelected - result.finalAmount - result.estimatedFee, result.changeAmount)
    }

    @Test
    fun `insufficient funds reports a required amount greater than what's available`() {
        val utxos = listOf(utxo(1_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(result.totalSelected < result.requiredAmount)
    }

    @Test
    fun `a payload increases the estimated fee`() {
        val utxos = listOf(utxo(100_000_000L))
        val withoutPayload = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        val withPayload = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = ByteArray(500), recipientScriptLen = 34, changeScriptLen = 34
        )
        assertTrue(withPayload.estimatedFee > withoutPayload.estimatedFee)
    }
}
