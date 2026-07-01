package com.kachat.app.services

import android.util.Log
import com.google.gson.Gson
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.HandshakePayload
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
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
    private val walletEngine: KaspaWalletEngine,
    private val chatRepository: ChatRepository
) {
    private val _balance = MutableStateFlow(0L)
    val balance: StateFlow<Long> = _balance.asStateFlow()

    /** Amount sent with a handshake transaction: 0.2 KAS (matches iOS `handshakeAmount`). */
    private val HANDSHAKE_AMOUNT_SOMPI = 20_000_000L

    data class SendResult(val txId: String, val payloadHex: String)

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
    suspend fun sendKaspa(toAddress: String, amountSompi: Long, payloadBytes: ByteArray? = null): String {
        val result = walletEngine.sendKaspa(toAddress, amountSompi, payloadBytes)

        if (result.isSuccess) {
            refreshBalance()
            return result.getOrThrow()
        } else {
            throw result.exceptionOrNull() ?: Exception("Unknown error during Kaspa send")
        }
    }

    /**
     * Sends an encrypted on-chain message (Kasia "comm" protocol) as a self-stash
     * transaction. Sends a "handshake" transaction first if we haven't already sent
     * one to this contact — see [sendHandshake] for the caveats around that.
     */
    suspend fun sendKasiaMessage(toContactId: String, text: String): SendResult {
        val recipientPubKey = KaspaAddress.decode(toContactId).second

        val contact = chatRepository.getContact(toContactId)
        if (contact == null || !contact.handshakeComplete) {
            sendHandshake(toContactId, recipientPubKey)
        }

        val alias = walletManager.getAccountName()
        val encrypted = MessageProtocol.encrypt(text, recipientPubKey)
        val payloadBytes = MessageProtocol.buildCommPayload(alias, encrypted)

        val txId = sendKaspa(toAddress = walletManager.getAddress(), amountSompi = 0, payloadBytes = payloadBytes)
        return SendResult(txId, payloadBytes.toHexString())
    }

    /**
     * Sends a "handshake" transaction (0.2 KAS to the recipient) carrying our alias
     * and encrypted for their address-derived public key.
     *
     * NOTE: this only marks [ContactEntity.handshakeComplete] optimistically on send —
     * there is no receive/ack path yet (no gRPC/UTXO-subscription layer), so this
     * reflects "we sent a handshake," not "the recipient acknowledged it." This matches
     * the real protocol, which has no explicit handshake-ack message either.
     */
    private suspend fun sendHandshake(toAddress: String, recipientPubKey: ByteArray): String {
        val payload = HandshakePayload(
            alias = walletManager.getAccountName(),
            timestamp = System.currentTimeMillis(),
            conversationId = null,
            recipientAddress = toAddress,
            isResponse = null
        )
        val json = Gson().toJson(payload)
        val encrypted = MessageProtocol.encrypt(json, recipientPubKey)
        val payloadBytes = MessageProtocol.buildHandshakePayload(encrypted)

        val txId = sendKaspa(toAddress = toAddress, amountSompi = HANDSHAKE_AMOUNT_SOMPI, payloadBytes = payloadBytes)

        val existing = chatRepository.getContact(toAddress)
        chatRepository.addContact(
            (existing ?: ContactEntity(id = toAddress, alias = null, knsName = null, publicKeyHex = null))
                .copy(publicKeyHex = recipientPubKey.toHexString(), handshakeComplete = true)
        )
        return txId
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
