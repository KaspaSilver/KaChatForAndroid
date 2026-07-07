package com.kachat.app.services.database

import androidx.room.*
import com.kachat.app.models.MessageEntity
import com.kachat.app.models.MessageSyncCursorEntity
import com.kachat.app.models.UnreadCount
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    fun getMessagesForContact(contactId: String, walletAddress: String): Flow<List<MessageEntity>>

    /** Every message for this wallet, across all contacts — used by chat-history export, not the live UI. */
    @Query("SELECT * FROM messages WHERE walletAddress = :walletAddress ORDER BY blockTimestamp ASC")
    suspend fun getAllMessagesForWallet(walletAddress: String): List<MessageEntity>

    /** One row per contactId (within this wallet) — whichever message has the most recent blockTimestamp. */
    @Query(
        """
        SELECT * FROM messages
        WHERE walletAddress = :walletAddress AND blockTimestamp IN (
            SELECT MAX(blockTimestamp) FROM messages WHERE walletAddress = :walletAddress GROUP BY contactId
        )
        """
    )
    fun getLatestMessagePerContact(walletAddress: String): Flow<List<MessageEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id AND walletAddress = :walletAddress)")
    suspend fun exists(id: String, walletAddress: String): Boolean

    @Query(
        """
        SELECT contactId, COUNT(*) as count FROM messages
        WHERE isRead = 0 AND direction = 'received' AND walletAddress = :walletAddress
        GROUP BY contactId
        """
    )
    fun getUnreadCounts(walletAddress: String): Flow<List<UnreadCount>>

    @Query("UPDATE messages SET isRead = 1 WHERE contactId = :contactId AND walletAddress = :walletAddress AND isRead = 0")
    suspend fun markAllAsRead(contactId: String, walletAddress: String)

    /** Contacts (within this wallet) whose entire message history is payment-only — never a real handshake/comm message. */
    @Query(
        """
        SELECT contactId FROM messages
        WHERE walletAddress = :walletAddress
        GROUP BY contactId
        HAVING COUNT(*) = SUM(CASE WHEN type = 'pay' THEN 1 ELSE 0 END)
        """
    )
    fun getPaymentOnlyContactIds(walletAddress: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun updateStatus(id: String, walletAddress: String, status: String)

    @Query("DELETE FROM messages WHERE id = :id AND walletAddress = :walletAddress")
    suspend fun deleteById(id: String, walletAddress: String)

    /** "Wipe and re-sync incoming messages" — sent messages, contacts, and the wallet itself are untouched. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress AND direction = 'received'")
    suspend fun deleteReceivedForWallet(walletAddress: String)

    /** Every message with one specific contact, gone — used by ChatRepository.deleteChat's full-removal flow. */
    @Query("DELETE FROM messages WHERE contactId = :contactId AND walletAddress = :walletAddress")
    suspend fun deleteAllForContact(contactId: String, walletAddress: String)

    /** Every message for this wallet, gone — used when wiping an entire account. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress")
    suspend fun deleteAllForWallet(walletAddress: String)

    /** Backup retention pruning — permanently deletes messages older than [cutoffMillis] for this wallet. */
    @Query("DELETE FROM messages WHERE walletAddress = :walletAddress AND blockTimestamp < :cutoffMillis")
    suspend fun deleteOlderThan(walletAddress: String, cutoffMillis: Long)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    /** How far into this (contact, alias) message stream we've already synced — see [MessageSyncCursorEntity]. */
    @Query("SELECT lastBlockTime FROM message_sync_cursors WHERE contactId = :contactId AND walletAddress = :walletAddress AND aliasHex = :aliasHex")
    suspend fun getMessageSyncCursor(contactId: String, walletAddress: String, aliasHex: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMessageSyncCursor(cursor: MessageSyncCursorEntity)

    /** Resets every per-contact sync cursor for this wallet — used by "wipe and re-sync" so it actually re-fetches full history again instead of picking up where the (now-deleted) cache left off. */
    @Query("DELETE FROM message_sync_cursors WHERE walletAddress = :walletAddress")
    suspend fun deleteSyncCursorsForWallet(walletAddress: String)
}
