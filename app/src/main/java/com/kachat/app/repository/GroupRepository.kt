@file:OptIn(ExperimentalStdlibApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kachat.app.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.GroupEntity
import com.kachat.app.models.GroupMember
import com.kachat.app.models.GroupMessageEntity
import com.kachat.app.models.GroupSyncCursorEntity
import com.kachat.app.services.GroupBag
import com.kachat.app.services.GroupControlIndexerResponse
import com.kachat.app.services.GroupMessageIndexerResponse
import com.kachat.app.services.GroupSecretStore
import com.kachat.app.services.KasiaIndexerApi
import com.kachat.app.services.NetworkService
import com.kachat.app.services.NotificationHelper
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.GroupCipher
import com.kachat.app.util.ImageMessage
import com.kachat.app.util.KasiaCipher
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageReply
import com.kachat.app.util.Schnorr
import com.kachat.app.util.VoiceMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A decrypted group chat message - UI-facing, mirrors [com.kachat.app.models.GroupMessageEntity] but with plaintext content. */
data class GroupMessage(
    val txId: String,
    val groupId: String,
    val senderAddress: String?,
    val senderIdHex: String,
    val content: String,
    val blockTimestamp: Long,
    val isOutgoing: Boolean,
    val deliveryStatus: String
)

/**
 * Group chat lifecycle (create/add/remove member, epoch rotation), sending, and message
 * decryption. Kotlin port of iOS KaChat's `GroupChatService.swift` — see that file's doc
 * comment for the full protocol rationale. Discovery (block-scan for `gcomm`/`gctl` on-chain
 * payloads) lives in [com.kachat.app.services.GroupScanningService], which calls the
 * `handleIncoming*` functions here.
 *
 * Two on-chain payload types, both self-stash (sender spends their own identity-address UTXOs,
 * output returns to their own identity address):
 *  - `ciph_msg:1:gcomm:...` - a group message.
 *  - `ciph_msg:1:gctl:...` - a control message (`gctl_root`/`gctl_epoch`), ECIES-encrypted (via
 *    [KasiaCipher], the same crypto 1:1 messages use) to one specific recipient.
 *
 * Deliberately no invite-link/beacon join path: every member is added directly by the admin, who
 * already knows who they are (see `addMember`/`createGroup`). A prior revision had a
 * publicly-joinable invite beacon (KaChat extension, not in the reference spec) - removed once
 * group chats route through indexers, since a way for anyone to discover and join a group's
 * *encrypted* chat is exactly the kind of thing that could be used to infer something bad is
 * happening inside it and pressure an indexer operator into censoring it.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val groupSecretStore: GroupSecretStore,
    private val networkService: NetworkService,
    private val notificationHelper: NotificationHelper
) {
    private val gson = Gson()
    private val membersListType = object : TypeToken<List<GroupMember>>() {}.type

    // -------------------------------------------------------------------------
    // Groups (reactive, scoped to whichever account is active)
    // -------------------------------------------------------------------------

    fun getGroups(): Flow<List<GroupEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.groupDao().getGroups(address)
        }
    }

    fun getGroupCount(): Flow<Int> = getGroups().map { it.size }

    /** True whenever a wallet is active, regardless of group state - see [com.kachat.app.services.GroupScanningService] for why `gctl` scanning must key off this instead of group count. */
    val hasActiveWallet: Flow<Boolean> = walletManager.activeAddressFlow.map { it != null }

    /** "kaspa" or "kaspatest", read off the active wallet's own address - used to reconstruct a sender's address from a raw pubkey/script for the active network instead of assuming mainnet. */
    fun addressPrefix(): String = walletManager.getAddress().substringBefore(":")

    fun membersOf(group: GroupEntity): List<GroupMember> =
        try { gson.fromJson<List<GroupMember>>(group.membersJson, membersListType) ?: emptyList() } catch (e: Exception) { emptyList() }

    /** Decrypted messages for a group, oldest first - decryption happens here, on read, from stored ciphertext. */
    fun getMessages(groupId: String): Flow<List<GroupMessage>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList())
            else database.groupDao().getMessages(groupId, address).map { entities ->
                val bag = groupSecretStore.loadBag(address, groupId) ?: return@map emptyList()
                val groupIdBytes = groupId.hexToByteArray()
                entities.mapNotNull { decryptEntity(it, bag, groupIdBytes) }
            }
        }
    }

    private fun decryptEntity(entity: GroupMessageEntity, bag: GroupBag, groupIdBytes: ByteArray): GroupMessage? {
        val root = groupRootEpochFor(entity.epoch, bag, groupIdBytes) ?: return null
        val senderId = try { entity.senderIdHex.hexToByteArray() } catch (e: Exception) { return null }
        val msgId = try { entity.msgIdHex.hexToByteArray() } catch (e: Exception) { return null }
        val ciphertext = try { entity.contentEncryptedHex.hexToByteArray() } catch (e: Exception) { return null }
        val plaintext = GroupCipher.decryptMessage(ciphertext, root, groupIdBytes, entity.epoch, senderId, msgId) ?: return null
        return GroupMessage(
            txId = entity.txId, groupId = entity.groupId, senderAddress = entity.senderAddress, senderIdHex = entity.senderIdHex,
            content = plaintext, blockTimestamp = entity.blockTimestamp, isOutgoing = entity.isOutgoing, deliveryStatus = entity.deliveryStatus
        )
    }

    /**
     * Admins can derive any past epoch's root on demand (they hold groupSeed); non-admins only
     * retain the current epoch's root - by design, this is the protocol's forward-secrecy
     * boundary, not a bug.
     */
    private fun groupRootEpochFor(epoch: Long, bag: GroupBag, groupIdBytes: ByteArray): ByteArray? {
        if (epoch == bag.currentEpoch) return bag.groupRootEpoch.hexToByteArray()
        val seedHex = bag.groupSeed ?: return null
        return GroupCipher.deriveGroupRootEpoch(seedHex.hexToByteArray(), groupIdBytes, epoch)
    }

    // -------------------------------------------------------------------------
    // Group creation & membership
    // -------------------------------------------------------------------------

    suspend fun createGroup(name: String, members: List<ContactEntity>): GroupEntity {
        val walletAddress = walletManager.getAddress()
        val privateKey = walletManager.getPrivateKeyBytes()
        val adminXOnlyPub = Schnorr.publicKeyXOnly(privateKey)

        val groupSeed = GroupCipher.generateGroupSeed()
        val groupId = GroupCipher.deriveGroupId(groupSeed)
        val groupRootEpoch0 = GroupCipher.deriveGroupRootEpoch(groupSeed, groupId, 0)
        val blindingKey = GroupCipher.deriveBlindingKey(groupSeed, groupId)
        val deviceId = GroupCipher.generateDeviceId()

        val roster = mutableListOf(GroupMember(address = walletAddress, xOnlyPubKeyHex = adminXOnlyPub.toHexString(), isAdmin = true))
        for (contact in members) {
            val memberXOnlyPub = xOnlyPubKeyOrNull(contact.id) ?: continue
            roster.add(GroupMember(address = contact.id, xOnlyPubKeyHex = memberXOnlyPub.toHexString(), isAdmin = false, displayName = contact.alias))
        }

        val bag = GroupBag(
            groupId = groupId.toHexString(),
            groupSeed = groupSeed.toHexString(),
            groupRootEpoch = groupRootEpoch0.toHexString(),
            blindingKey = blindingKey.toHexString(),
            currentEpoch = 0,
            deviceId = deviceId.toHexString(),
            msgCounter = 0
        )
        groupSecretStore.saveBag(walletAddress, bag)

        val entity = GroupEntity(
            groupId = groupId.toHexString(), walletAddress = walletAddress, name = name, adminAddress = walletAddress,
            adminXOnlyPubKeyHex = adminXOnlyPub.toHexString(), currentEpoch = 0, isAdmin = true, membersJson = gson.toJson(roster)
        )
        database.groupDao().upsertGroup(entity)

        // Distribute gctl_root to each initial member directly - they must already be a 1:1
        // contact, i.e. their pubkey is resolvable from their address (every member is added
        // this way; there's no invite-link bootstrap path, see class doc). Best effort: one
        // member's send failing doesn't roll back group creation.
        for (member in roster) {
            if (member.isAdmin) continue
            try {
                sendRootControlMessage(entity, roster, bag, member.address, privateKey)
            } catch (e: Exception) {
                // Logged by the caller's own try/catch around sendKaspa; swallow here so one
                // failed member doesn't abort the rest.
            }
        }

        return entity
    }

    /** Adds a member directly (requires an existing 1:1-resolvable address) - bumps the epoch and redistributes the new root to every member (old + new). */
    suspend fun addMember(contact: ContactEntity, groupId: String) {
        val memberXOnlyPub = xOnlyPubKeyOrNull(contact.id) ?: throw IllegalArgumentException("Invalid address")
        rotateEpoch(groupId, "add") { roster ->
            if (roster.none { it.address == contact.id }) {
                roster.add(GroupMember(address = contact.id, xOnlyPubKeyHex = memberXOnlyPub.toHexString(), isAdmin = false, displayName = contact.alias))
            }
        }
    }

    /** Removes a member and rotates the epoch so the removed member can no longer decrypt future messages (the new root is only distributed to remaining members). */
    suspend fun removeMember(member: GroupMember, groupId: String) {
        rotateEpoch(groupId, "remove") { roster ->
            roster.removeAll { it.address == member.address }
        }
    }

    private suspend fun rotateEpoch(groupId: String, reason: String, mutateRoster: (MutableList<GroupMember>) -> Unit) {
        val walletAddress = walletManager.getAddress()
        val entity = database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        if (!entity.isAdmin) throw IllegalStateException("Only the group admin can change membership.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing admin group secrets.")
        val groupSeed = bag.groupSeed?.hexToByteArray() ?: throw IllegalStateException("Missing admin group secrets.")
        val groupIdBytes = groupId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()

        val roster = membersOf(entity).toMutableList()
        mutateRoster(roster)

        val newEpoch = bag.currentEpoch + 1
        val newRoot = GroupCipher.deriveGroupRootEpoch(groupSeed, groupIdBytes, newEpoch)
        val newBag = bag.copy(currentEpoch = newEpoch, groupRootEpoch = newRoot.toHexString())
        groupSecretStore.saveBag(walletAddress, newBag)

        val updatedEntity = entity.copy(currentEpoch = newEpoch, membersJson = gson.toJson(roster))
        database.groupDao().upsertGroup(updatedEntity)

        for (member in roster) {
            if (member.address == walletAddress) continue
            try {
                sendEpochControlMessage(groupIdBytes, newEpoch, reason, member.address, privateKey)
                sendRootControlMessage(updatedEntity, roster, newBag, member.address, privateKey)
            } catch (e: Exception) {
                // Best effort, same as createGroup - one member's failed delivery doesn't block the rest.
            }
        }
    }

    /**
     * Deletes a group locally: its message history, Keystore-held secrets (root/seed/blinding
     * key), and roster. Local-only, like leaving/deleting a broadcast channel - there's no
     * server-side group record to delete, and other members aren't notified (the trust model
     * is single-admin push, not a shared membership ledger, so this device simply stops
     * tracking the group and can no longer decrypt or send to it).
     */
    suspend fun deleteGroup(groupId: String) {
        val walletAddress = walletManager.getAddress()
        groupSecretStore.deleteBag(walletAddress, groupId)
        database.groupDao().deleteMessagesForGroup(groupId, walletAddress)
        database.groupDao().deleteGroup(groupId, walletAddress)
    }

    // -------------------------------------------------------------------------
    // Sending group messages
    // -------------------------------------------------------------------------

    /**
     * Sends a photo to the group - same [ImageMessage]/[VoiceMessageContent] JSON envelope 1:1
     * chat uses, just carried as a `gcomm` message's plaintext instead of a 1:1 comm payload.
     * Reusing the exact envelope shape means [com.kachat.app.ui.screens.ImageBubble]/
     * [com.kachat.app.util.ImageMessage] render it with no changes.
     */
    suspend fun sendGroupImage(imageBytes: ByteArray, groupId: String, fileName: String = "photo.jpg", mimeType: String = "image/jpeg"): String {
        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val json = com.kachat.app.util.ImageMessage.encode(fileName = fileName, sizeBytes = imageBytes.size.toLong(), base64Image = base64, mimeType = mimeType)
        return sendGroupMessage(json, groupId)
    }

    /** Sends a voice message to the group - same envelope/reuse rationale as [sendGroupImage]. */
    suspend fun sendGroupAudio(audioBytes: ByteArray, groupId: String, fileName: String = "voice.webm", mimeType: String = "audio/webm"): String {
        val base64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val json = com.kachat.app.util.VoiceMessage.encode(fileName = fileName, sizeBytes = audioBytes.size.toLong(), base64Audio = base64, mimeType = mimeType)
        return sendGroupMessage(json, groupId)
    }

    suspend fun sendGroupMessage(text: String, groupId: String): String {
        val walletAddress = walletManager.getAddress()
        database.groupDao().getGroup(groupId, walletAddress) ?: throw IllegalStateException("Unknown group.")
        val bag = groupSecretStore.loadBag(walletAddress, groupId) ?: throw IllegalStateException("Missing group secrets - try rejoining this group.")

        val groupIdBytes = groupId.hexToByteArray()
        val groupRootEpoch = bag.groupRootEpoch.hexToByteArray()
        val blindingKey = bag.blindingKey.hexToByteArray()
        val deviceId = bag.deviceId.hexToByteArray()
        val privateKey = walletManager.getPrivateKeyBytes()
        val senderXOnlyPub = Schnorr.publicKeyXOnly(privateKey)
        val senderId = GroupCipher.deriveSenderId(walletAddress)

        // Persist the incremented counter BEFORE building/sending - a msg_id must never be
        // reused even if the send itself later fails.
        val counter = bag.msgCounter + 1
        groupSecretStore.saveBag(walletAddress, bag.copy(msgCounter = counter))

        val msgId = GroupCipher.buildMsgId(deviceId, counter)
        val ciphertext = GroupCipher.encryptMessage(text, groupRootEpoch, groupIdBytes, bag.currentEpoch, senderId, msgId)
        val aad = GroupCipher.buildMessageAAD(groupIdBytes, bag.currentEpoch, senderId, msgId)
        val signature = GroupCipher.sign(GroupCipher.buildMessageSigningPayload(aad, ciphertext), privateKey)
        val blindedGroupId = GroupCipher.deriveBlindedGroupId(blindingKey, senderXOnlyPub)
        val payloadString = GroupCipher.buildGroupMessagePayload(blindedGroupId, bag.currentEpoch, senderId, senderXOnlyPub, msgId, ciphertext, signature)

        val pendingId = "pending_${UUID.randomUUID()}"
        val nowMs = System.currentTimeMillis()
        database.groupDao().insertMessage(
            GroupMessageEntity(
                txId = pendingId, walletAddress = walletAddress, groupId = groupId, senderAddress = walletAddress,
                senderIdHex = senderId.toHexString(), epoch = bag.currentEpoch, msgIdHex = msgId.toHexString(),
                contentEncryptedHex = ciphertext.toHexString(), blockTimestamp = nowMs, isOutgoing = true, deliveryStatus = "pending"
            )
        )
        try {
            val txId = walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
            database.groupDao().deleteMessage(pendingId, walletAddress)
            database.groupDao().insertMessage(
                GroupMessageEntity(
                    txId = txId, walletAddress = walletAddress, groupId = groupId, senderAddress = walletAddress,
                    senderIdHex = senderId.toHexString(), epoch = bag.currentEpoch, msgIdHex = msgId.toHexString(),
                    contentEncryptedHex = ciphertext.toHexString(), blockTimestamp = nowMs, isOutgoing = true, deliveryStatus = "sent"
                )
            )
            return txId
        } catch (e: Exception) {
            database.groupDao().updateMessageStatus(pendingId, walletAddress, "failed")
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Control message send (gctl_root / gctl_epoch)
    // -------------------------------------------------------------------------

    private suspend fun sendRootControlMessage(entity: GroupEntity, roster: List<GroupMember>, bag: GroupBag, recipientAddress: String, adminPrivateKey: ByteArray) {
        val recipientXOnlyPub = xOnlyPubKeyOrNull(recipientAddress) ?: throw IllegalArgumentException("Invalid address")
        val groupIdBytes = entity.groupId.hexToByteArray()
        val rootPayload = GroupCipher.buildSignedRootPayload(
            groupId = groupIdBytes, epoch = bag.currentEpoch, groupRootEpoch = bag.groupRootEpoch.hexToByteArray(),
            blindingKey = bag.blindingKey.hexToByteArray(), adminSigningPub = entity.adminXOnlyPubKeyHex.hexToByteArray(),
            members = roster.map { it.address }, name = entity.name, adminPrivateKey = adminPrivateKey
        )
        val json = GroupCipher.rootPayloadToJson(rootPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    private suspend fun sendEpochControlMessage(groupIdBytes: ByteArray, epoch: Long, reason: String, recipientAddress: String, adminPrivateKey: ByteArray) {
        val recipientXOnlyPub = xOnlyPubKeyOrNull(recipientAddress) ?: throw IllegalArgumentException("Invalid address")
        val epochPayload = GroupCipher.buildSignedEpochPayload(groupIdBytes, epoch, reason, adminPrivateKey)
        val json = GroupCipher.epochPayloadToJson(epochPayload)
        sendControlPayload(json, recipientXOnlyPub, adminPrivateKey)
    }

    private suspend fun sendControlPayload(json: String, recipientXOnlyPub: ByteArray, privateKey: ByteArray) {
        val walletAddress = walletManager.getAddress()
        val encrypted = KasiaCipher.encrypt(json, recipientXOnlyPub)
        val payloadString = "ciph_msg:1:gctl:" + encrypted.toBytes().toHexString()
        walletService.sendKaspa(toAddress = walletAddress, amountSompi = 0, payloadBytes = payloadString.toByteArray(Charsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // Incoming payload handlers - called from GroupScanningService
    // -------------------------------------------------------------------------

    suspend fun handleIncomingGroupMessage(parsed: GroupCipher.ParsedGroupMessage, txId: String, blockTimestamp: Long) {
        val walletAddress = walletManager.getAddress()
        val hrp = walletAddress.substringBefore(":")
        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }
            val groupIdBytes = try { group.groupId.hexToByteArray() } catch (e: Exception) { continue }

            val candidateBlindedId = GroupCipher.deriveBlindedGroupId(blindingKey, parsed.senderPubKey)
            if (!candidateBlindedId.contentEquals(parsed.blindedGroupId)) continue

            // Found the group. Verify sender identity: pubkey -> address -> in roster -> hashes to senderId.
            val senderAddress = KaspaAddress.encode(hrp, 0x00, parsed.senderPubKey)
            val roster = membersOf(group)
            if (roster.none { it.address == senderAddress }) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: sender $senderAddress not in roster ${roster.map { it.address }}")
                return
            }
            if (!GroupCipher.deriveSenderId(senderAddress).contentEquals(parsed.senderId)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: senderId mismatch for $senderAddress")
                return
            }

            val aad = GroupCipher.buildMessageAAD(groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            val signingPayload = GroupCipher.buildMessageSigningPayload(aad, parsed.ciphertext)
            if (!GroupCipher.verify(parsed.signature, signingPayload, parsed.senderPubKey)) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: bad signature from $senderAddress")
                return
            }

            val root = groupRootEpochFor(parsed.epoch, bag, groupIdBytes)
            if (root == null) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: no root for epoch ${parsed.epoch} (local currentEpoch=${bag.currentEpoch})")
                return
            }
            val plaintext = GroupCipher.decryptMessage(parsed.ciphertext, root, groupIdBytes, parsed.epoch, parsed.senderId, parsed.msgId)
            if (plaintext == null) {
                Log.w("GroupRepository", "Rejected gcomm for group ${group.groupId.take(12)}: decrypt failed from $senderAddress")
                return
            }

            val isOutgoing = senderAddress == walletAddress
            val rowId = database.groupDao().insertMessage(
                GroupMessageEntity(
                    txId = txId, walletAddress = walletAddress, groupId = group.groupId, senderAddress = senderAddress,
                    senderIdHex = parsed.senderId.toHexString(), epoch = parsed.epoch, msgIdHex = parsed.msgId.toHexString(),
                    contentEncryptedHex = parsed.ciphertext.toHexString(), blockTimestamp = blockTimestamp, isOutgoing = isOutgoing, deliveryStatus = "sent"
                )
            )
            // rowId == -1 means insertMessage's IGNORE conflict strategy dropped it as an
            // already-seen txId (e.g. catch-up re-fetching something the live scan already
            // processed) - only notify for a genuinely new, incoming (not our own) message.
            if (rowId != -1L && !isOutgoing) {
                val senderLabel = membersOf(group).firstOrNull { it.address == senderAddress }?.displayName
                    ?: senderAddress.takeLast(8)
                val replyContent = MessageReply.parseOrNull(plaintext)
                val notificationText = when {
                    replyContent != null -> "$senderLabel replied to \"${replyContent.replyToPreview}\""
                    VoiceMessage.parseOrNull(plaintext) != null -> "$senderLabel: 🎤 Audio message"
                    ImageMessage.parseOrNull(plaintext) != null -> "$senderLabel: 📷 Photo"
                    else -> "$senderLabel: $plaintext"
                }
                notificationHelper.showGroup(group.groupId, group.name, notificationText)
            }
            return
        }
        Log.w("GroupRepository", "Rejected gcomm: no local group matched blindedGroupId ${parsed.blindedGroupId.toHexString()}")
    }

    suspend fun handleIncomingControlMessage(payloadString: String, senderAddress: String) {
        val walletAddress = walletManager.getAddress()
        if (senderAddress == walletAddress) return
        val privateKey = walletManager.getPrivateKeyBytes()
        val prefix = "ciph_msg:1:gctl:"
        if (!payloadString.startsWith(prefix)) return
        val hexPayload = payloadString.substring(prefix.length)
        val encryptedBytes = try { hexPayload.hexToByteArray() } catch (e: Exception) { return }
        val encrypted = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return
        val plaintext = try { KasiaCipher.decrypt(encrypted, privateKey) } catch (e: Exception) { return }

        val rootPayload = GroupCipher.rootPayloadFromJson(plaintext)
        if (rootPayload != null && rootPayload.type == "gctl_root" && GroupCipher.verifyRootPayload(rootPayload)) {
            completeJoin(rootPayload)
        }
        // gctl_epoch is an advance-notice heads-up only (state updates on gctl_root arrival,
        // not on gctl_epoch) - no local state change needed here in the data layer.
    }

    /** Applies a verified gctl_root payload: creates or updates the local group secrets + roster. Refuses to downgrade to an older epoch than what's already stored (replay protection). */
    private suspend fun completeJoin(payload: GroupCipher.GroupRootPayload) {
        val walletAddress = walletManager.getAddress()
        val existingBag = groupSecretStore.loadBag(walletAddress, payload.groupId)
        if (existingBag != null && existingBag.currentEpoch > payload.epoch) return
        val isFirstTimeJoin = existingBag == null

        // device_id is persistent per device - preserve it across epoch-rotation updates to an
        // already-joined group; only a genuinely first-time join mints a new one. msgCounter
        // always resets to 0 on a new epoch root. groupSeed is preserved defensively in case
        // this device somehow already held admin secrets for this group.
        val deviceId = existingBag?.deviceId ?: GroupCipher.generateDeviceId().toHexString()
        val bag = GroupBag(
            groupId = payload.groupId, groupSeed = existingBag?.groupSeed, groupRootEpoch = payload.groupRootEpoch,
            blindingKey = payload.blindingKey, currentEpoch = payload.epoch, deviceId = deviceId, msgCounter = 0
        )
        groupSecretStore.saveBag(walletAddress, bag)

        val members = payload.members.mapNotNull { address ->
            val xOnlyPub = xOnlyPubKeyOrNull(address) ?: return@mapNotNull null
            GroupMember(address = address, xOnlyPubKeyHex = xOnlyPub.toHexString(), isAdmin = xOnlyPub.toHexString() == payload.adminSigningPub)
        }
        val adminAddress = try {
            KaspaAddress.encode("kaspa", 0x00, payload.adminSigningPub.hexToByteArray())
        } catch (e: Exception) {
            members.firstOrNull { it.isAdmin }?.address ?: ""
        }

        val entity = GroupEntity(
            groupId = payload.groupId, walletAddress = walletAddress, name = payload.name, adminAddress = adminAddress,
            adminXOnlyPubKeyHex = payload.adminSigningPub, currentEpoch = payload.epoch, isAdmin = walletAddress == adminAddress,
            membersJson = gson.toJson(members)
        )
        database.groupDao().upsertGroup(entity)

        if (isFirstTimeJoin) {
            notificationHelper.showGroup(payload.groupId, "", "You were added to \"${payload.name}\"")
        }
    }

    // -------------------------------------------------------------------------
    // Catch-up sync (indexer-backed, for when the device wasn't actively block-scanning)
    // -------------------------------------------------------------------------

    /**
     * Fetches missed `gcomm`/`gctl` history from the indexer for every local group, so a device
     * that wasn't actively block-scanning while away (backgrounded, killed, or just closed) still
     * catches up. Mirrors [ChatRepository]'s per-(contact, alias) cursor sync, applied to group
     * chat's two sync object shapes (see [GroupSyncCursorEntity]'s doc comment).
     *
     * `blinded_group_id` is per-sender, not per-group, so `gcomm` catch-up queries once per known
     * member (their blinded id is cheap to recompute locally from the group's shared blindingKey).
     * `gctl` catch-up queries by the group's admin address - only meaningful for groups already
     * joined, since a brand-new invite has no admin address to key off yet locally (that case
     * depends on push or being online at the right moment instead).
     */
    suspend fun syncGroups() {
        val api = networkService.indexerApi.value ?: return
        val walletAddress = walletManager.getAddress()
        val groups = database.groupDao().getGroupsOnce(walletAddress)

        for (group in groups) {
            val bag = groupSecretStore.loadBag(walletAddress, group.groupId) ?: continue
            val blindingKey = try { bag.blindingKey.hexToByteArray() } catch (e: Exception) { continue }

            for (member in membersOf(group)) {
                val memberPubKey = try { member.xOnlyPubKeyHex.hexToByteArray() } catch (e: Exception) { continue }
                val blindedGroupIdHex = GroupCipher.deriveBlindedGroupId(blindingKey, memberPubKey).toHexString()
                syncGroupMessages(api, walletAddress, group.groupId, blindedGroupIdHex)
            }

            if (group.adminAddress.isNotEmpty()) {
                syncGroupControl(api, walletAddress, group.adminAddress)
            }
        }
    }

    private suspend fun syncGroupMessages(api: KasiaIndexerApi, walletAddress: String, groupId: String, blindedGroupIdHex: String) {
        val syncKey = "gcomm|$groupId|$blindedGroupIdHex"
        val cursor = database.groupDao().getGroupSyncCursor(syncKey, walletAddress)
        val messages: List<GroupMessageIndexerResponse> = try {
            api.getGroupMessagesByBlindedGroupId(blindedGroupIdHex, blockTime = cursor)
        } catch (e: Exception) {
            Log.w("GroupRepository", "Catch-up gcomm fetch failed for group ${groupId.take(12)}", e)
            return
        }
        val maxBlockTime = messages.maxOfOrNull { it.blockTime }
        if (maxBlockTime != null && maxBlockTime > (cursor ?: 0L)) {
            database.groupDao().setGroupSyncCursor(GroupSyncCursorEntity(syncKey = syncKey, walletAddress = walletAddress, lastBlockTime = maxBlockTime))
        }
        for (msg in messages) {
            val payloadString = reconstructPayloadString("ciph_msg:1:gcomm:", msg.messagePayload) ?: continue
            val parsed = GroupCipher.parseGroupMessagePayload(payloadString) ?: continue
            handleIncomingGroupMessage(parsed, msg.txId, msg.blockTime)
        }
    }

    private suspend fun syncGroupControl(api: KasiaIndexerApi, walletAddress: String, adminAddress: String) {
        val syncKey = "gctl|${adminAddress.lowercase()}"
        val cursor = database.groupDao().getGroupSyncCursor(syncKey, walletAddress)
        val messages: List<GroupControlIndexerResponse> = try {
            api.getGroupControlBySender(adminAddress, blockTime = cursor)
        } catch (e: Exception) {
            Log.w("GroupRepository", "Catch-up gctl fetch failed for admin ${adminAddress.takeLast(10)}", e)
            return
        }
        val maxBlockTime = messages.maxOfOrNull { it.blockTime }
        if (maxBlockTime != null && maxBlockTime > (cursor ?: 0L)) {
            database.groupDao().setGroupSyncCursor(GroupSyncCursorEntity(syncKey = syncKey, walletAddress = walletAddress, lastBlockTime = maxBlockTime))
        }
        for (msg in messages) {
            val payloadString = reconstructPayloadString("ciph_msg:1:gctl:", msg.messagePayload) ?: continue
            handleIncomingControlMessage(payloadString, msg.sender)
        }
    }

    /**
     * Reverses the indexer's double-hex-encoding of `message_payload` (it hex-encodes the raw
     * on-chain sealed hex text as stored) back into the original `ciph_msg:1:<type>:<hex>`
     * on-chain payload string, so it can feed straight into the same parse/decrypt path the live
     * block-scan uses.
     */
    private fun reconstructPayloadString(prefix: String, messagePayloadHex: String): String? {
        val asciiBytes = try { messagePayloadHex.hexToByteArray() } catch (e: Exception) { return null }
        val hexText = try { String(asciiBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        return prefix + hexText
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** x-only pubkey directly encoded in a standard (P2PK) Kaspa address's payload - null for any other address type or malformed input. */
    private fun xOnlyPubKeyOrNull(address: String): ByteArray? {
        return try {
            val (version, payload) = KaspaAddress.decode(address)
            if (version == 0x00.toByte() && payload.size == 32) payload else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears all local group data (Room + Keystore secrets) for [walletAddress] - mirrors
     * [ChatRepository.wipeAllLocalDataForAddress]'s signature/semantics exactly (an explicit
     * Danger Zone action, not an automatic side effect of logout/delete-wallet, and must work
     * for any address, not just whichever wallet happens to be active right now).
     */
    suspend fun clearAllLocalData(walletAddress: String) {
        val groups = database.groupDao().getGroupsOnce(walletAddress)
        for (group in groups) {
            groupSecretStore.deleteBag(walletAddress, group.groupId)
        }
        database.groupDao().deleteAllGroups(walletAddress)
        database.groupDao().deleteAllMessages(walletAddress)
        database.groupDao().deleteGroupSyncCursorsForWallet(walletAddress)
    }
}
