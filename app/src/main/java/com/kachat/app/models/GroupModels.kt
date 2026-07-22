package com.kachat.app.models

import androidx.room.Entity

/**
 * Group chat metadata - non-secret. Secret key material (group seed/root epoch/blinding key/
 * device id/msg counter) lives in [com.kachat.app.services.GroupSecretStore] (Keystore-backed
 * EncryptedSharedPreferences), never in Room - mirrors how [MessageEntity] stores only encrypted
 * payloads while the wallet's own private key lives in WalletManager's own encrypted prefs, not
 * the database.
 *
 * Primary key is (groupId, walletAddress), same reasoning as [ContactEntity]/[MessageEntity]:
 * more than one local account could theoretically be a member of the same group.
 */
@Entity(tableName = "groups", primaryKeys = ["groupId", "walletAddress"])
data class GroupEntity(
    val groupId: String,               // hex
    val walletAddress: String,         // which local account this group belongs to
    val name: String,
    val adminAddress: String,
    val adminXOnlyPubKeyHex: String,
    val currentEpoch: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val isAdmin: Boolean,
    val membersJson: String            // JSON-encoded List<GroupMember>, see GroupRepository
)

/**
 * A member of a group chat - embedded as JSON inside [GroupEntity.membersJson] rather than its
 * own table: rosters are small and always read/written as a whole unit together (a membership
 * change already means rewriting the whole roster + rotating the epoch).
 */
data class GroupMember(
    val address: String,
    val xOnlyPubKeyHex: String,
    val isAdmin: Boolean,
    val displayName: String? = null
)

/**
 * A group chat message - content stored as raw gcomm ciphertext (hex), NOT plaintext, decrypted
 * on read using the group's Keystore-held root key (see GroupRepository). Same posture as
 * [MessageEntity.encryptedPayload] - compromising this database alone doesn't reveal content.
 */
@Entity(tableName = "group_messages", primaryKeys = ["txId", "walletAddress"])
data class GroupMessageEntity(
    val txId: String,                  // Kaspa transaction ID, or a synthetic "pending_<uuid>" while a send is in flight
    val walletAddress: String,
    val groupId: String,
    val senderAddress: String?,        // resolved from senderId against the roster; null if unknown
    val senderIdHex: String,
    val epoch: Long,
    val msgIdHex: String,
    val contentEncryptedHex: String,
    val blockTimestamp: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: String = "sent" // "pending" | "sent" | "failed"
)

/**
 * How far into one group catch-up sync object's indexer stream this wallet has already synced -
 * same "adaptive per-object cursor" idea as [MessageSyncCursorEntity], applied to group chat's
 * two sync object shapes: `gcomm|<groupId>|<blindedGroupIdHex>` (queried once per known member,
 * since `blinded_group_id` is per-sender not per-group - see GroupCipher's protocol notes) and
 * `gctl|<adminAddressLowercased>` (queried once per group's admin address). [syncKey] folds both
 * shapes into one disjoint string key rather than a second table, since neither maps cleanly onto
 * (contactId, aliasHex).
 */
@Entity(tableName = "group_sync_cursors", primaryKeys = ["syncKey", "walletAddress"])
data class GroupSyncCursorEntity(
    val syncKey: String,
    val walletAddress: String,
    val lastBlockTime: Long
)
