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

    /**
     * A zero-amount send is always a self-stash message (1:1 chat, broadcast, voice, photo) —
     * KaspaWalletEngine.sendKaspa always drops the zero-value recipient output for these (a
     * 0-value output is non-standard and gets rejected), leaving only the change output. Pricing
     * against 2 outputs here used to silently overpay every message's real network fee. 162,400
     * sompi matches iOS's own verified baseline for the identical 1-input/1-output/0-payload
     * shape (KaspaFeePolicy's `standardComputeMass = 1_624`, `test_toccata_fee_policy.swift`) —
     * not the 203,600 sompi a phantom 2nd output used to charge.
     */
    @Test
    fun `a zero-amount send prices only the single change output actually built, matching iOS`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 0L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(162_400L, result.estimatedFee)
    }

    @Test
    fun `a real payment still prices both recipient and change outputs`() {
        val utxos = listOf(utxo(100_000_000L))
        val result = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, amountSompi = 10_000_000L, feeRateSompiPerGram = 100L,
            payloadBytes = null, recipientScriptLen = 34, changeScriptLen = 34
        )
        assertEquals(203_600L, result.estimatedFee)
    }
}
