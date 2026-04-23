package com.kachat.app.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: com.kachat.app.repository.ChatRepository,
    private val networkService: com.kachat.app.services.NetworkService,
    private val walletManager: com.kachat.app.services.WalletManager,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
    private val walletService: com.kachat.app.services.WalletService
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = chatRepository.getContacts().map { contacts ->
        contacts.map { contact ->
            Conversation(contact, null, 0) // TODO: Get last message and unread count
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val archivedConversations: StateFlow<List<Conversation>> = chatRepository.getArchivedContacts().map { contacts ->
        contacts.map { contact ->
            Conversation(contact, null, 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val archivedCount: StateFlow<Int> = archivedConversations.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    fun archiveChat(contactId: String) {
        viewModelScope.launch { chatRepository.archiveContact(contactId) }
    }

    fun unarchiveChat(contactId: String) {
        viewModelScope.launch { chatRepository.unarchiveContact(contactId) }
    }

    fun updateContactName(contactId: String, newName: String) {
        viewModelScope.launch {
            val existing = chatRepository.getContact(contactId) ?: return@launch
            val updated = existing.copy(alias = if (newName.isBlank()) null else newName)
            chatRepository.addContact(updated)
        }
    }

    private val _contactBalances = MutableStateFlow<Map<String, String>>(emptyMap())
    val contactBalances: StateFlow<Map<String, String>> = _contactBalances.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshChats() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                chatRepository.syncMessages()
                walletService.refreshBalance()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error refreshing chats", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    val estimateFeesEnabled: StateFlow<Boolean> = settings.estimateFees
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    private val _currentUtxos = MutableStateFlow<List<com.kachat.app.services.UtxoEntry>>(emptyList())
    val currentUtxos: StateFlow<List<com.kachat.app.services.UtxoEntry>> = _currentUtxos.asStateFlow()

    private val _paymentAmount = MutableStateFlow("")
    val paymentAmount: StateFlow<String> = _paymentAmount.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private val _networkFeeRate = MutableStateFlow(1.0) // sompi per byte
    val networkFeeRate: StateFlow<Double> = _networkFeeRate.asStateFlow()

    val estimatedFeeSompi: StateFlow<Long?> = combine(paymentAmount, _messageText, _currentUtxos, estimateFeesEnabled, _networkFeeRate) { amount, text, utxos, enabled, rate ->
        if (!enabled || (amount.isEmpty() && text.isEmpty())) return@combine null
        
        val isPayment = amount.isNotEmpty()
        val sompiNeeded = if (isPayment) {
            (amount.toDoubleOrNull() ?: 0.0) * 100_000_000
        } else {
            0.0
        }.toLong()
        
        var total = 0L
        var count = 0
        for (utxo in utxos) {
            total += utxo.utxoEntry.amount
            count++
            if (total >= sompiNeeded + 1000) break // Buffer for fee
        }
        
        if (total < sompiNeeded && isPayment) return@combine null
        
        val payload = if (isPayment) "Sent $amount KAS" else text
        val payloadSize = payload.toByteArray().size
        
        // Accurate mass formula: 
        // 4 (version) + 8 (payload len) + payload + (inputs * 66) + (outputs * 34)
        val estimatedSize = 4 + 8 + payloadSize + (count.coerceAtLeast(1) * 66) + (2 * 34)
        (estimatedSize * rate).toLong().coerceAtLeast(1L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun setMessageText(text: String) {
        _messageText.value = text
    }

    fun setPaymentAmount(amount: String) {
        _paymentAmount.value = amount
    }

    fun updateEstimateFees(enabled: Boolean) {
        viewModelScope.launch { settings.setEstimateFees(enabled) }
    }

    fun refreshUtxos() {
        viewModelScope.launch {
            try {
                val address = walletManager.getAddress()
                val api = networkService.kaspaRestApi.value ?: return@launch
                
                // Refresh fee rate from network
                try {
                    val feeInfo = api.getFeeEstimate()
                    _networkFeeRate.value = feeInfo.normalBucket
                } catch (e: Exception) {
                    Log.w("ChatViewModel", "Could not fetch fee estimate, using default 1.0")
                }

                _currentUtxos.value = api.getUtxos(address)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addContact(address: String, name: String?) {
        viewModelScope.launch {
            val existing = chatRepository.getContact(address)
            if (existing != null) {
                // If contact exists, unarchive it and update name if provided
                val updated = existing.copy(
                    alias = if (name.isNullOrBlank()) existing.alias else name,
                    isArchived = false
                )
                chatRepository.addContact(updated)
            } else {
                val newContact = ContactEntity(
                    id = address,
                    alias = if (name.isNullOrBlank()) null else name,
                    knsName = if (address.contains(".kas")) address else null,
                    publicKeyHex = null,
                    isArchived = false
                )
                chatRepository.addContact(newContact)
            }
        }
    }

    /**
     * Sends an on-chain message via WalletService.
     * Matches iOS SendKasView flow.
     */
    fun sendMessage(contactId: String, text: String) {
        viewModelScope.launch {
            try {
                if (text.isEmpty()) return@launch
                
                // Call Wallet Engine
                val txId = walletService.sendKasiaMessage(contactId, text)
                
                // Update local UI immediately
                val message = MessageEntity(
                    id = txId,
                    contactId = contactId,
                    walletAddress = walletManager.getAddress(),
                    type = "msg",
                    direction = "sent",
                    plaintextBody = text,
                    encryptedPayload = "", // TODO: Real encryption in Phase 4
                    amountSompi = 0,
                    blockTimestamp = System.currentTimeMillis()
                )
                chatRepository.insertMessage(message)
                
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
            }
        }
    }

    /**
     * Sends a Kaspa payment via WalletService.
     * Matches iOS SendKasView flow.
     */
    fun sendPayment(contactId: String, amount: String) {
        viewModelScope.launch {
            try {
                val amountKas = amount.toDoubleOrNull() ?: return@launch
                val sompi = (amountKas * 100_000_000).toLong()
                
                // Call Wallet Engine
                val txId = walletService.sendKaspa(toAddress = contactId, amountSompi = sompi)
                
                // Update local UI immediately
                val message = MessageEntity(
                    id = txId,
                    contactId = contactId,
                    walletAddress = walletManager.getAddress(),
                    type = "pay",
                    direction = "sent",
                    plaintextBody = "Sent $amount KAS",
                    encryptedPayload = "",
                    amountSompi = sompi,
                    blockTimestamp = System.currentTimeMillis()
                )
                chatRepository.insertMessage(message)
                
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending payment", e)
            }
        }
    }

    fun getConversation(contactId: String): Conversation? {
        return conversations.value.find { it.contact.id == contactId }
    }
    
    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return chatRepository.getMessages(contactId)
    }

    fun refreshContactBalance(address: String) {
        if (!com.kachat.app.util.KaspaAddress.isValid(address)) return
        
        viewModelScope.launch {
            try {
                chatRepository.syncMessages()

                val api = networkService.kaspaRestApi.value ?: return@launch
                val response = api.getBalance(address)
                val kasAmount = response.balance.toDouble() / 100_000_000.0
                val balanceStr = String.format(Locale.US, "%.8f", kasAmount)
                _contactBalances.value = _contactBalances.value + (address to balanceStr)
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
}
