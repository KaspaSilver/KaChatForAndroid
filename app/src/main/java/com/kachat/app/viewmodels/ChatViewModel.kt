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

    fun sendMessage(contactId: String, text: String) {
        viewModelScope.launch {
            try {
                if (text.isEmpty()) return@launch
                val fromAddress = walletManager.getAddress()
                val payloadHex = text.toByteArray().joinToString("") { "%02x".format(it) }
                
                val api = networkService.kaspaRestApi.value ?: return@launch
                val utxos = api.getUtxos(fromAddress)
                
                if (utxos.isEmpty()) return@launch

                var totalSelected = 0L
                val selectedUtxos = mutableListOf<com.kachat.app.services.UtxoEntry>()
                var fee = 0L
                
                for (utxo in utxos) {
                    selectedUtxos.add(utxo)
                    totalSelected += utxo.utxoEntry.amount
                    
                    val payloadSize = payloadHex.chunked(2).size
                    val mass = 4 + 8 + payloadSize + (selectedUtxos.size * 66) + (1 * 34) // Only 1 output for messages (self-send)
                    fee = (mass * _networkFeeRate.value).toLong().coerceAtLeast(1L)
                    
                    if (totalSelected >= fee) break 
                }

                if (totalSelected < fee) return@launch

                val inputs = selectedUtxos.map { utxo ->
                    com.kachat.app.services.RawInput(
                        previousOutpoint = utxo.outpoint,
                        signatureScript = ""
                    )
                }
                
                // Self-send pattern for messages
                val outputs = mutableListOf(
                    com.kachat.app.services.RawOutputWithVersion(
                        amount = totalSelected - fee,
                        scriptPublicKey = com.kachat.app.services.ScriptPublicKeyWithVersion(
                            scriptPublicKey = com.kachat.app.util.KaspaAddress.getScriptPublicKey(fromAddress)
                        )
                    )
                )

                val rawTx = com.kachat.app.services.RawTransaction(
                    inputs = inputs,
                    outputs = outputs,
                    payload = payloadHex
                )

                val signedTx = com.kachat.app.util.KaspaTransactionSigner.signTransaction(
                    rawTx = rawTx,
                    utxos = selectedUtxos,
                    privateKey = walletManager.getPrivateKeyBytes()
                )

                // 5. Broadcast
                val response = api.postTransaction(com.kachat.app.services.PostTransactionRequest(signedTx))

                // Local bubble
                val message = MessageEntity(
                    id = response.transactionId,
                    contactId = contactId,
                    walletAddress = fromAddress,
                    type = "msg",
                    direction = "sent",
                    plaintextBody = text,
                    encryptedPayload = payloadHex,
                    amountSompi = 0,
                    blockTimestamp = System.currentTimeMillis()
                )
                chatRepository.insertMessage(message)
                
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
            }
        }
    }

    fun sendPayment(contactId: String, amount: String) {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "Starting sendPayment to $contactId for $amount KAS")
                val amountKas = amount.toDoubleOrNull() ?: run {
                    Log.e("ChatViewModel", "Invalid amount: $amount")
                    return@launch
                }
                val sompi = (amountKas * 100_000_000).toLong()
                val fromAddress = walletManager.getAddress()
                Log.d("ChatViewModel", "From address: $fromAddress")
                
                val payloadHex = ("Sent $amount KAS").toByteArray().joinToString("") { "%02x".format(it) }
                val api = networkService.kaspaRestApi.value ?: run {
                    Log.e("ChatViewModel", "REST API not available")
                    return@launch
                }
                val utxos = api.getUtxos(fromAddress)
                Log.d("ChatViewModel", "Fetched ${utxos.size} UTXOs")
                
                if (utxos.isEmpty()) {
                    Log.w("ChatViewModel", "No UTXOs found for address")
                    return@launch
                }

                // 2. Select UTXOs
                var totalSelected = 0L
                val selectedUtxos = mutableListOf<com.kachat.app.services.UtxoEntry>()
                var fee = 0L
                
                for (utxo in utxos) {
                    selectedUtxos.add(utxo)
                    totalSelected += utxo.utxoEntry.amount
                    
                    // Update fee based on input count and actual network rate
                    val payloadSize = payloadHex.chunked(2).size
                    val mass = 4 + 8 + payloadSize + (selectedUtxos.size * 66) + (2 * 34)
                    fee = (mass * _networkFeeRate.value).toLong().coerceAtLeast(1L)
                    
                    Log.d("ChatViewModel", "Selected UTXO: ${utxo.utxoEntry.amount} sompi. Total selected: $totalSelected, needed: ${sompi + fee}")
                    if (totalSelected >= sompi + fee) break 
                }

                var finalSompi = sompi
                if (totalSelected < sompi + fee) {
                    // If we have almost enough, maybe the user tried to send "Max"
                    if (totalSelected > fee && (sompi + fee - totalSelected) < 2000) {
                        Log.i("ChatViewModel", "Adjusting amount for Max send: $sompi -> ${totalSelected - fee}")
                        finalSompi = totalSelected - fee
                    } else {
                        Log.w("ChatViewModel", "Insufficient funds: totalSelected=$totalSelected, needed=${sompi + fee}")
                        return@launch
                    }
                }

                // 3. Create raw transaction
                val inputs = selectedUtxos.map { utxo ->
                    com.kachat.app.services.RawInput(
                        previousOutpoint = utxo.outpoint,
                        signatureScript = ""
                    )
                }
                
                val outputs = mutableListOf(
                    com.kachat.app.services.RawOutputWithVersion(
                        amount = finalSompi,
                        scriptPublicKey = com.kachat.app.services.ScriptPublicKeyWithVersion(
                            scriptPublicKey = com.kachat.app.util.KaspaAddress.getScriptPublicKey(contactId)
                        )
                    )
                )
                
                // Change output
                val change = totalSelected - finalSompi - fee
                if (change > 500) {
                    outputs.add(com.kachat.app.services.RawOutputWithVersion(
                        amount = change,
                        scriptPublicKey = com.kachat.app.services.ScriptPublicKeyWithVersion(
                            scriptPublicKey = com.kachat.app.util.KaspaAddress.getScriptPublicKey(fromAddress)
                        )
                    ))
                }

                val rawTx = com.kachat.app.services.RawTransaction(
                    inputs = inputs,
                    outputs = outputs,
                    payload = payloadHex
                )
                Log.d("ChatViewModel", "Raw transaction created with ${inputs.size} inputs and ${outputs.size} outputs")

                // 4. Sign
                val signedTx = com.kachat.app.util.KaspaTransactionSigner.signTransaction(
                    rawTx = rawTx,
                    utxos = selectedUtxos,
                    privateKey = walletManager.getPrivateKeyBytes()
                )
                Log.d("ChatViewModel", "Transaction signed")

                // 5. Broadcast
                val response = api.postTransaction(com.kachat.app.services.PostTransactionRequest(signedTx))
                Log.i("ChatViewModel", "Transaction broadcast successful: ${response.transactionId}")
                
                // 6. Save local message placeholder
                val message = MessageEntity(
                    id = response.transactionId,
                    contactId = contactId,
                    walletAddress = fromAddress,
                    type = "pay",
                    direction = "sent",
                    plaintextBody = "Sent ${String.format(Locale.US, "%.8f", finalSompi.toDouble()/1e8)} KAS",
                    encryptedPayload = payloadHex,
                    amountSompi = finalSompi,
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
