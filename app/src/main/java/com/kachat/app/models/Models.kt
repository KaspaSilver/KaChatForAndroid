package com.kachat.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Message stored locally in Room.
 *
 * Maps to the ciph_msg protocol payloads:
 *   - type = "handshake" → handshake
 *   - type = "comm"      → contextual message
 *   - type = "pay"       → payment with memo
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,             // Kaspa transaction ID
    val contactId: String,                  // Foreign key → ContactEntity.id
    val walletAddress: String,              // Which wallet this belongs to
    val type: String,                       // "handshake" | "comm" | "pay"
    val direction: String,                  // "sent" | "received"
    val plaintextBody: String?,             // Decrypted message text (null if not yet decrypted)
    val encryptedPayload: String,           // Raw ciph_msg payload from chain
    val amountSompi: Long?,                 // For "pay" type: amount in sompi (1 KAS = 1e8 sompi)
    val blockTimestamp: Long,               // Block time in epoch ms
    val isRead: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis()
)

/**
 * Contact stored locally.
 * Matches the iOS contact model with alias and KNS support.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,             // Kaspa address (kaspa:q...)
    val alias: String?,                     // User-given nickname
    val knsName: String?,                   // KNS domain e.g. "alice.kas"
    val publicKeyHex: String?,              // Secp256k1 public key (after handshake)
    val handshakeComplete: Boolean = false,
    val isArchived: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * In-memory model for a conversation (not persisted directly — derived from messages).
 */
data class Conversation(
    val contact: ContactEntity,
    val lastMessage: MessageEntity?,
    val unreadCount: Int
)

/**
 * Inner JSON plaintext of a "handshake" ciph_msg payload, encrypted before transmission.
 * Field names match the iOS `HandshakePayload` struct so a real KaChat iOS user can decode it.
 */
data class HandshakePayload(
    val type: String = "handshake",
    val alias: String?,
    val timestamp: Long,
    val conversationId: String?,
    val version: Int = 1,
    val recipientAddress: String?,
    val sendToRecipient: Boolean = true,
    val isResponse: Boolean? = null
)
