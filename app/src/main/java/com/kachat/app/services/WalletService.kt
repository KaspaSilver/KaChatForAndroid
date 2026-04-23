package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaTransactionSigner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WalletService — handles high-level wallet operations like balance tracking
 * and transaction orchestration (Build -> Sign -> Broadcast).
 *
 * This matches the WalletService/ChatService logic in the iOS app.
 */
@Singleton
class WalletService @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager
) {
    private val _balance = MutableStateFlow(0L)
    val balance: StateFlow<Long> = _balance.asStateFlow()

    suspend fun refreshBalance() {
        val address = try { walletManager.getAddress() } catch (e: Exception) { return }
        val api = networkService.kaspaRestApi.value ?: return
        
        try {
            val response = api.getBalance(address)
            _balance.value = response.balance
        } catch (e: Exception) {
            Log.e("WalletService", "Error refreshing balance", e)
        }
    }

    /**
     * Orchestrates a Kaspa payment: Fetch UTXOs -> Build -> Sign -> Broadcast.
     * @return The transaction ID if successful.
     */
    suspend fun sendKaspa(toAddress: String, amountSompi: Long, payload: String? = null): String {
        val fromAddress = walletManager.getAddress()
        val api = networkService.kaspaRestApi.value ?: throw IllegalStateException("Network service unavailable")
        
        // 1. Fetch UTXOs
        val utxos = api.getUtxos(fromAddress)
        if (utxos.isEmpty()) throw IllegalStateException("Insufficient funds: No UTXOs")

        // 2. Fetch network fee rate
        val feeRate = try {
            api.getFeeEstimate().normalBucket
        } catch (e: Exception) {
            1.0 // Default fallback
        }

        // 3. Select UTXOs and Calculate Fee (Matching iOS automatic logic)
        var totalSelected = 0L
        val selectedUtxos = mutableListOf<UtxoEntry>()
        var estimatedFee = 0L
        
        val payloadHex = payload?.toByteArray()?.joinToString("") { "%02x".format(it) }
        val payloadSize = payloadHex?.chunked(2)?.size ?: 0

        for (utxo in utxos) {
            selectedUtxos.add(utxo)
            totalSelected += utxo.utxoEntry.amount
            
            // Mass-based fee calculation
            val mass = 4 + 8 + payloadSize + (selectedUtxos.size * 66) + (2 * 34)
            estimatedFee = (mass * feeRate).toLong().coerceAtLeast(1L)
            
            if (totalSelected >= amountSompi + estimatedFee) break
        }

        var finalAmount = amountSompi
        if (totalSelected < amountSompi + estimatedFee) {
            // Handle "Max Send" logic automatically if we are close
            if (totalSelected > estimatedFee && (amountSompi + estimatedFee - totalSelected) < 2000) {
                finalAmount = totalSelected - estimatedFee
            } else {
                throw IllegalStateException("Insufficient funds: Needed ${amountSompi + estimatedFee}, have $totalSelected")
            }
        }

        // 4. Build Raw Transaction
        val inputs = selectedUtxos.map { utxo ->
            RawInput(previousOutpoint = utxo.outpoint, signatureScript = "")
        }
        
        val outputs = mutableListOf(
            RawOutputWithVersion(
                amount = finalAmount,
                scriptPublicKey = ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(toAddress), 0)
            )
        )

        // Automatic Change Handling
        val change = totalSelected - finalAmount - estimatedFee
        if (change > 500) {
            outputs.add(
                RawOutputWithVersion(
                    amount = change,
                    scriptPublicKey = ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(fromAddress), 0)
                )
            )
        }

        val rawTx = RawTransaction(
            inputs = inputs,
            outputs = outputs,
            payload = payloadHex
        )

        // 5. Sign (using WalletManager for secure key access)
        val signedTx = KaspaTransactionSigner.signTransaction(
            rawTx = rawTx,
            utxos = selectedUtxos,
            privateKey = walletManager.getPrivateKeyBytes()
        )

        // 6. Broadcast via RPC (REST proxy for now)
        val response = api.postTransaction(PostTransactionRequest(signedTx))
        
        // Refresh local balance after success
        refreshBalance()
        
        return response.transactionId
    }

    /**
     * Specialized method for sending on-chain messages (Kasia protocol).
     */
    suspend fun sendKasiaMessage(toContactId: String, text: String): String {
        // iOS protocol implementation: self-send with payload
        // We reuse sendKaspa but set amount to 0 (or minimum dust) and provide text
        return sendKaspa(toAddress = walletManager.getAddress(), amountSompi = 0, payload = text)
    }
}
