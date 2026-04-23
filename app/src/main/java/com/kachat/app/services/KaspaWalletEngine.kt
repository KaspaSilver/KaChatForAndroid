package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaTransactionSigner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KaspaWalletEngine — handles low-level transaction construction and broadcasting.
 * Follows the required send flow: Fetch UTXOs -> Build -> Sign -> Broadcast.
 */
@Singleton
class KaspaWalletEngine @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager
) {

    /**
     * Sends Kaspa to a given address.
     * @param toAddress Recipient Kaspa address.
     * @param amountSompi Amount to send in sompi (1 KAS = 100,000,000 sompi).
     * @return Result containing the transaction ID or an error.
     */
    suspend fun sendKaspa(toAddress: String, amountSompi: Long, payload: String? = null): Result<String> {
        return try {
            // 1. Validate address
            if (!isValidAddress(toAddress)) {
                return Result.failure(IllegalArgumentException("Invalid recipient address: $toAddress"))
            }

            val fromAddress = walletManager.getAddress()
            val api = networkService.kaspaRestApi.value ?: return Result.failure(IllegalStateException("Network service unavailable"))

            // 2. Fetch UTXOs from node
            val utxos = api.getUtxos(fromAddress)
            if (utxos.isEmpty()) {
                return Result.failure(IllegalStateException("Insufficient funds: No UTXOs found"))
            }

            // 3. Fetch network fee rate
            val feeRate = try {
                api.getFeeEstimate().normalBucket
            } catch (e: Exception) {
                Log.w("KaspaWalletEngine", "Failed to fetch fee estimate, using fallback", e)
                1.0 // Default fallback
            }

            // 4. Use SDK-like generator logic for UTXO selection and fee calculation
            val selectionResult = selectUtxosAndCalculateFee(utxos, amountSompi, feeRate, payload)
            
            if (selectionResult.totalSelected < selectionResult.requiredAmount) {
                return Result.failure(IllegalStateException("Insufficient funds: Needed ${selectionResult.requiredAmount}, have ${selectionResult.totalSelected}"))
            }

            // 5. Create transaction outputs (recipient + change)
            val outputs = mutableListOf(
                RawOutputWithVersion(
                    amount = selectionResult.finalAmount,
                    scriptPublicKey = ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(toAddress), 0)
                )
            )

            // Automatic Change Handling
            if (selectionResult.changeAmount > 500) { // Minimum dust threshold
                outputs.add(
                    RawOutputWithVersion(
                        amount = selectionResult.changeAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(fromAddress), 0)
                    )
                )
            }

            val payloadHex = payload?.toByteArray()?.joinToString("") { "%02x".format(it) }

            val rawTx = RawTransaction(
                inputs = selectionResult.selectedUtxos.map { utxo ->
                    RawInput(previousOutpoint = utxo.outpoint, signatureScript = "")
                },
                outputs = outputs,
                payload = payloadHex
            )

            // 6. Sign transaction with private key (locally)
            val signedTx = KaspaTransactionSigner.signTransaction(
                rawTx = rawTx,
                utxos = selectionResult.selectedUtxos,
                privateKey = walletManager.getPrivateKeyBytes()
            )

            // 7. Broadcast transaction via RPC
            val response = api.postTransaction(PostTransactionRequest(signedTx))
            
            Result.success(response.transactionId)
        } catch (e: Exception) {
            Log.e("KaspaWalletEngine", "Error sending Kaspa", e)
            Result.failure(e)
        }
    }

    private fun isValidAddress(address: String): Boolean {
        return try {
            // Basic validation using KaspaAddress utility
            KaspaAddress.getScriptPublicKey(address).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private data class SelectionResult(
        val selectedUtxos: List<UtxoEntry>,
        val totalSelected: Long,
        val estimatedFee: Long,
        val finalAmount: Long,
        val changeAmount: Long,
        val requiredAmount: Long
    )

    private fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRate: Double,
        payload: String?
    ): SelectionResult {
        var totalSelected = 0L
        val selectedUtxos = mutableListOf<UtxoEntry>()
        var estimatedFee = 0L
        
        val payloadHex = payload?.toByteArray()?.joinToString("") { "%02x".format(it) }
        val payloadSize = payloadHex?.chunked(2)?.size ?: 0

        // Iterate and select UTXOs until amount + fee is covered
        for (utxo in utxos.sortedByDescending { it.utxoEntry.amount }) {
            selectedUtxos.add(utxo)
            totalSelected += utxo.utxoEntry.amount
            
            // Mass-based fee calculation (Standard Kaspa mass model)
            // Mass = 4 (version) + 8 (locktime) + payloadSize + (numInputs * 66) + (numOutputs * 34)
            // We assume 2 outputs (recipient + change) for estimation
            val mass = 4 + 8 + payloadSize + (selectedUtxos.size * 66) + (2 * 34)
            estimatedFee = (mass * feeRate).toLong().coerceAtLeast(1L)
            
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
}
