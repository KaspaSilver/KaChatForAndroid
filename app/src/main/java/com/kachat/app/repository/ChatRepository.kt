package com.kachat.app.repository

import com.kachat.app.models.ContactEntity
import com.kachat.app.models.MessageEntity
import com.kachat.app.services.NetworkService
import com.kachat.app.services.WalletManager
import com.kachat.app.services.database.KaChatDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val networkService: NetworkService,
    private val walletManager: WalletManager
) {
    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessagesForContact(contactId)
    }

    fun getContacts(): Flow<List<ContactEntity>> {
        return database.contactDao().getActiveContacts()
    }

    fun getArchivedContacts(): Flow<List<ContactEntity>> {
        return database.contactDao().getArchivedContacts()
    }

    suspend fun archiveContact(id: String) {
        database.contactDao().updateArchivedStatus(id, true)
    }

    suspend fun unarchiveContact(id: String) {
        database.contactDao().updateArchivedStatus(id, false)
    }

    suspend fun addContact(contact: ContactEntity) {
        database.contactDao().insert(contact)
    }

    suspend fun getContact(id: String): ContactEntity? {
        return database.contactDao().getContact(id)
    }

    suspend fun insertMessage(message: MessageEntity) {
        database.messageDao().insert(message)
    }

    suspend fun syncMessages() {
        val address = try { walletManager.getAddress() } catch (e: Exception) { return }
        val api = networkService.indexerApi.value ?: return
        
        try {
            val transactions = api.getMessages(address)
            transactions.forEach { tx ->
                val isSent = tx.inputs.any { it.signatureScript.isNotEmpty() }
                
                // For received payments, the contactId is the sender's address
                // We need to resolve the sender address from the first input's previous outpoint
                // Since our indexer returns transaction objects with inputs, we need to fetch the 
                // actual address that owns that input. 
                // For now, we'll try a simplified logic to find the 'other' side.
                
                var contactId = "unknown"
                var receivedAmountSompi = 0L

                if (isSent) {
                    // It's a sent transaction, find the primary recipient (not us)
                    contactId = tx.outputs.firstOrNull { it.scriptPublicKey.scriptPublicKey != address }?.let { 
                        it.scriptPublicKey.scriptPublicKey
                    } ?: "unknown"
                } else {
                    // It's a received transaction, we are the recipient
                    // The sender is the one who provided the inputs.
                    // NOTE: This assumes a simple transaction. In complex ones, we'd need more logic.
                    // For now, if it's incoming, we look at the inputs.
                    // Since we don't have the sender address directly in the input, we use a placeholder 
                    // or try to fetch it if the API supports it.
                    // Improvement: The Kasia indexer typically provides sender info if tailored for this.
                    
                    // Fallback to transaction ID as a placeholder for sender if unknown
                    contactId = tx.inputs.firstOrNull()?.previousOutpoint?.transactionId ?: "unknown"
                    
                    receivedAmountSompi = tx.outputs.find { it.scriptPublicKey.scriptPublicKey == address }?.amount ?: 0L
                }

                // Auto-create contact if it doesn't exist
                if (contactId != "unknown" && database.contactDao().getContact(contactId) == null) {
                    val newContact = ContactEntity(
                        id = contactId,
                        alias = null,
                        knsName = null,
                        publicKeyHex = null,
                        isArchived = false
                    )
                    database.contactDao().insert(newContact)
                }

                val message = MessageEntity(
                    id = tx.transactionId,
                    contactId = contactId,
                    walletAddress = address,
                    type = if (tx.payload?.isNotEmpty() == true) "msg" else "pay",
                    direction = if (isSent) "sent" else "received",
                    plaintextBody = if (isSent) (tx.payload ?: "Payment") else "Received ${String.format(java.util.Locale.US, "%.2f", receivedAmountSompi.toDouble()/1e8)} KAS",
                    encryptedPayload = tx.payload ?: "",
                    amountSompi = if (isSent) tx.outputs.find { it.scriptPublicKey.scriptPublicKey != address }?.amount else receivedAmountSompi,
                    blockTimestamp = tx.blockTime ?: System.currentTimeMillis()
                )
                database.messageDao().insert(message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
