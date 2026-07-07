package com.kachat.app.models

import androidx.room.Entity

/**
 * Message stored locally in Room.
 *
 * Maps to the ciph_msg protocol payloads:
 *   - type = "handshake" → handshake
 *   - type = "comm"      → contextual message
 *   - type = "pay"       → payment with memo
 *
 * Primary key is (id, walletAddress) rather than just id — a transaction id is unique
 * on-chain, but if two of the user's own accounts are ever both party to the same tx (e.g.
 * sending between two of their own wallets), each account still needs its own independent
 * row (different direction/plaintext framing) instead of one clobbering the other.
 */
@Entity(tableName = "messages", primaryKeys = ["id", "walletAddress"])
data class MessageEntity(
    val id: String,                         // Kaspa transaction ID
    val contactId: String,                  // Foreign key → ContactEntity.id
    val walletAddress: String,              // Which wallet this belongs to
    val type: String,                       // "handshake" | "comm" | "pay"
    val direction: String,                  // "sent" | "received"
    val plaintextBody: String?,             // Decrypted message text (null if not yet decrypted)
    val encryptedPayload: String,           // Raw ciph_msg payload from chain
    val amountSompi: Long?,                 // For "pay" type: amount in sompi (1 KAS = 1e8 sompi)
    val blockTimestamp: Long,               // Block time in epoch ms
    val isRead: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis(),
    val deliveryStatus: String = "sent"     // "pending" | "sent" | "failed" — only meaningful for direction="sent"
)

/**
 * Tracks how far into one contact's `contextual-messages/by-sender` stream this wallet has
 * already synced, per alias (a contact may be messaging under more than one — see
 * `ChatRepository.syncContextualMessages`'s legacy/deterministic alias loop). The indexer's
 * `block_time` query param lets a sync only ask for what's genuinely new since [lastBlockTime]
 * instead of re-fetching the same recent window every time — this is that cursor, persisted so it
 * survives process death. Safe to advance even if the indexer's block_time boundary is inclusive
 * (returns the same last item again): callers already dedup by txId against local storage.
 */
@Entity(tableName = "message_sync_cursors", primaryKeys = ["contactId", "walletAddress", "aliasHex"])
data class MessageSyncCursorEntity(
    val contactId: String,
    val walletAddress: String,
    val aliasHex: String,
    val lastBlockTime: Long
)

/**
 * Contact stored locally.
 * Matches the iOS contact model with alias and KNS support.
 *
 * Primary key is (id, walletAddress) rather than just id — the same third-party address can
 * legitimately be a contact under more than one of the user's own accounts, each with its own
 * independent alias/handshake state, not one shared/overwritten row.
 */
@Entity(tableName = "contacts", primaryKeys = ["id", "walletAddress"])
data class ContactEntity(
    val id: String,                         // Kaspa address (kaspa:q...)
    val walletAddress: String,              // Which of the user's own accounts this contact belongs to
    val alias: String?,                     // User-given nickname
    val knsName: String?,                   // KNS domain e.g. "alice.kas"
    val publicKeyHex: String?,              // Secp256k1 public key (after handshake)
    val handshakeComplete: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val conversationStatus: String = "active", // "pending" | "active" | "rejected"
    val theirAlias: String? = null,         // Alias THEY sent us in their handshake — required to query their self-stashed messages
    val myAlias: String? = null,            // OUR protocol alias for THIS contact — 12 lowercase hex chars, NOT our display name (see WalletService.generateAlias)
    val knsAvatarUrl: String? = null,       // Cached from the KNS profile of `knsName`, so the chat list can render an avatar without a live fetch per row
    val systemContactId: String? = null,    // Phone contact's LOOKUP_KEY, once linked via "Link from Contacts" — takes priority over KNS auto-rename
    val systemContactName: String? = null,  // Name snapshot at link time, for the "Linked: X" row
    val systemContactLinkSource: String? = null // "manual" | "autoCreated" — only "autoCreated" shadow contacts get deleted if Autocreate is turned off
)

/**
 * A tombstone marking that [contactId] was deleted (by this wallet) at [deletedAt] — survives
 * the contact's own row being deleted, unlike the old "archive" flag. `syncContextualMessages`/
 * `processHandshake` check this before ever re-inserting a message or recreating a contact: any
 * message or handshake with `blockTimestamp <= deletedAt` is skipped, so a full re-sync of that
 * sender's on-chain history (which the indexer always returns in full, not just "since last seen")
 * can't silently resurrect a deleted conversation. A genuinely new handshake sent *after*
 * [deletedAt] still creates a fresh contact/conversation normally.
 */
@Entity(tableName = "deleted_contacts", primaryKeys = ["contactId", "walletAddress"])
data class DeletedContactEntity(
    val contactId: String,
    val walletAddress: String,
    val deletedAt: Long = System.currentTimeMillis()
)

/**
 * In-memory model for a conversation (not persisted directly — derived from messages).
 */
data class Conversation(
    val contact: ContactEntity,
    val lastMessage: MessageEntity?,
    val unreadCount: Int
)

/** Room query projection — one row per contact with at least one unread received message. */
data class UnreadCount(
    val contactId: String,
    val count: Int
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
    val isResponse: Boolean? = null,
    val theirAlias: String? = null // Real Kasia web client's field, used on a response to confirm both sides' aliases
)

/**
 * A KNS commit transaction that broadcast successfully but whose reveal hasn't completed yet —
 * persisted the moment commit succeeds, cleared the moment reveal succeeds, so a crash or failure
 * between the two doesn't strand the commit amount with no way to recover it. iOS has no
 * equivalent safety net; this is intentionally more careful given real KAS is on the line.
 */
data class PendingKnsCommit(
    val commitTxId: String,
    val redeemScriptHex: String,
    val commitScriptPubKeyHex: String,
    val commitAmountSompi: Long,
    val revealAmountSompi: Long,
    val revealTargetAddress: String,
    val operationType: String // "domain" | "profile" — for the recovery prompt's wording only
)
