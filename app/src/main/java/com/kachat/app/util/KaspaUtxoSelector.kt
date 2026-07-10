package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * Pure UTXO selection + mass-based fee estimation, shared by every transaction builder
 * (regular sends in `KaspaWalletEngine`, KNS commit transactions in `KnsInscriptionEngine`) —
 * extracted so the logic exists in exactly one place rather than being duplicated per builder.
 */
object KaspaUtxoSelector {
    data class SelectionResult(
        val selectedUtxos: List<UtxoEntry>,
        val totalSelected: Long,
        val estimatedFee: Long,
        val finalAmount: Long,
        val changeAmount: Long,
        val requiredAmount: Long
    )

    fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int
    ): SelectionResult {
        var totalSelected = 0L
        val selectedUtxos = mutableListOf<UtxoEntry>()
        var estimatedFee = 0L

        val payloadSize = payloadBytes?.size ?: 0

        // A zero-amount send (every 1:1/broadcast message — a self-stash) never gets a recipient
        // output at all: KaspaWalletEngine.sendKaspa skips it outright since a 0-value output is
        // non-standard and gets rejected, leaving only the change output. Pricing those against 2
        // outputs (as this used to do unconditionally) silently overpaid every message's real
        // on-chain fee by a phantom output's mass (~412 mass, ~0.0004 KAS at the network minimum
        // rate) — matches iOS's selectUtxosForContextualMessage, which prices real message sends
        // off a single output. A real payment (amountSompi > 0) keeps the 2-output assumption:
        // if change ends up being dust and gets dropped, the real required fee is only lower, so
        // that direction never underpays the network.
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)

        // Iterate and select UTXOs until amount + fee is covered
        for (utxo in utxos.sortedByDescending { it.utxoEntry.amount }) {
            selectedUtxos.add(utxo)
            totalSelected += utxo.utxoEntry.amount

            val mass = KaspaMass.calculateMass(
                numInputs = selectedUtxos.size,
                outputScriptLens = outputScriptLens,
                payloadSize = payloadSize
            )
            estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram)

            if (totalSelected >= (amountSompi + estimatedFee)) break
        }

        var finalAmount = amountSompi
        var requiredAmount = amountSompi + estimatedFee

        // Check if we can fulfill the request
        if (totalSelected < requiredAmount) {
            // "Max Send" logic: if we are close (within 2000 sompi), adjust the amount
            if (totalSelected > estimatedFee && (requiredAmount - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
                requiredAmount = totalSelected
            }
        }

        val changeAmount = totalSelected - finalAmount - estimatedFee

        return SelectionResult(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = finalAmount,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount
        )
    }

    /**
     * For the spending-address "sweep everything on every send" design (see
     * KaspaWalletEngine.sendSpendingPayment): unlike [selectUtxosAndCalculateFee], which stops
     * as soon as it's picked *enough* large UTXOs to cover the amount+fee, this always spends
     * every given UTXO — so a spending address never ends a send holding a leftover fragment
     * behind. In steady state this address only ever holds the single change UTXO from its own
     * last spend (or a top-up), so this doesn't cost anything extra in practice — it only
     * differs from the greedy selector when the address happens to hold more than one UTXO
     * (e.g. multiple top-ups before a spend).
     */
    fun selectAllUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int
    ): SelectionResult {
        val selectedUtxos = utxos.toList()
        val totalSelected = selectedUtxos.sumOf { it.utxoEntry.amount }
        val payloadSize = payloadBytes?.size ?: 0
        val outputScriptLens = if (amountSompi > 0) listOf(recipientScriptLen, changeScriptLen) else listOf(changeScriptLen)

        val mass = KaspaMass.calculateMass(
            numInputs = selectedUtxos.size,
            outputScriptLens = outputScriptLens,
            payloadSize = payloadSize
        )
        val estimatedFee = KaspaMass.calculateFee(mass, feeRateSompiPerGram)

        var finalAmount = amountSompi
        var requiredAmount = amountSompi + estimatedFee

        // Same "max send" dust tolerance as the greedy selector — if the sweep is short by a
        // negligible amount, take the fee out of the payment rather than failing the send.
        if (totalSelected < requiredAmount) {
            if (totalSelected > estimatedFee && (requiredAmount - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
                requiredAmount = totalSelected
            }
        }

        val changeAmount = totalSelected - finalAmount - estimatedFee

        return SelectionResult(
            selectedUtxos = selectedUtxos,
            totalSelected = totalSelected,
            estimatedFee = estimatedFee,
            finalAmount = finalAmount,
            changeAmount = changeAmount,
            requiredAmount = requiredAmount
        )
    }
}
