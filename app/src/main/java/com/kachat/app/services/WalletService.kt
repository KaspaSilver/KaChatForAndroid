package com.kachat.app.services

import android.util.Log
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
    private val walletManager: WalletManager,
    private val walletEngine: KaspaWalletEngine
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
        val result = walletEngine.sendKaspa(toAddress, amountSompi, payload)
        
        if (result.isSuccess) {
            refreshBalance()
            return result.getOrThrow()
        } else {
            throw result.exceptionOrNull() ?: Exception("Unknown error during Kaspa send")
        }
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
