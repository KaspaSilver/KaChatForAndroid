package com.kachat.app.repository

import com.kachat.app.models.BroadcastChannelEntity
import com.kachat.app.models.BroadcastMessageEntity
import com.kachat.app.models.BroadcastRetention
import com.kachat.app.models.HiddenBroadcastSenderEntity
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.MessageProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcast rooms — public, unencrypted, one-to-many channels identified by a plaintext channel
 * name (see MessageProtocol's bcast payload functions). Kept separate from [ChatRepository]:
 * channels/messages here have no contact concept, no per-account message scoping (the message
 * cache is a raw capture of public chain data, shared across whichever account is active), and no
 * encryption, so folding this into ChatRepository would mostly add conditionals rather than reuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BroadcastRepository @Inject constructor(
    private val database: KaChatDatabase,
    private val walletManager: WalletManager,
    private val walletService: WalletService
) {
    /** Channels joined by whichever account is currently active — re-emits automatically on account switch. */
    fun getJoinedChannels(): Flow<List<BroadcastChannelEntity>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptyList()) else database.broadcastDao().getJoinedChannels(address)
        }
    }

    /** "Joining" and "creating" a channel are the same action — there's no ownership/membership protocol. */
    suspend fun joinChannel(rawName: String) {
        val name = MessageProtocol.normalizeChannelName(rawName)
        require(MessageProtocol.isValidChannelName(name)) { "Invalid channel name" }
        database.broadcastDao().joinChannel(
            BroadcastChannelEntity(channelName = name, walletAddress = walletManager.getAddress())
        )
    }

    /** Removes the channel from the joined list AND permanently deletes every cached message for it — the UI must confirm this with the user first, since it's destructive and can't be undone (rejoining later starts with no history). */
    suspend fun leaveChannel(channelName: String) {
        database.broadcastDao().leaveChannel(channelName, walletManager.getAddress())
        database.broadcastDao().deleteMessagesForChannel(channelName)
    }

    /**
     * Per-channel opt-in to background scanning — the user chooses exactly which channels stay
     * live while the app is backgrounded, rather than one all-or-nothing setting. Turning this
     * off also turns notifications off for the channel (a channel that isn't being scanned has
     * no way to know about new messages to notify about).
     */
    suspend fun setAlwaysListen(channelName: String, alwaysListen: Boolean) {
        if (alwaysListen) {
            database.broadcastDao().setAlwaysListen(channelName, walletManager.getAddress(), true)
        } else {
            database.broadcastDao().disableAlwaysListenAndNotify(channelName, walletManager.getAddress())
        }
    }

    /** Per-channel opt-in to a notification for new messages — enabling this also turns always-listen on for the channel, since notifications depend on it actually being scanned. */
    suspend fun setNotifyEnabled(channelName: String, notifyEnabled: Boolean) {
        val address = walletManager.getAddress()
        if (notifyEnabled) {
            database.broadcastDao().enableNotifyAndAlwaysListen(channelName, address)
        } else {
            database.broadcastDao().disableNotify(channelName, address)
        }
    }

    /** Per-channel override of local message retention, set via the settings icon next to a channel — clamped to [1 second, BroadcastRetention.MAX_MILLIS] so the UI's 3-day cap can't be bypassed by a bad input. */
    suspend fun setRetentionMillis(channelName: String, retentionMillis: Long) {
        val clamped = retentionMillis.coerceIn(1_000L, BroadcastRetention.MAX_MILLIS)
        database.broadcastDao().setRetentionMillis(channelName, walletManager.getAddress(), clamped)
    }

    /** The active account's always-listen channel names — non-empty drives whether background scanning runs at all, and membership decides which channels' messages actually get cached while it's running. */
    fun getAlwaysListenChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getAlwaysListenChannelNames(address).map { it.toSet() }
        }
    }

    /** The active account's notify-enabled channel names — drives which channels' new messages fire a system notification. */
    fun getNotifyEnabledChannelNames(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getNotifyEnabledChannelNames(address).map { it.toSet() }
        }
    }

    /** Never includes messages from a hidden sender — including ones already cached from before the hide, not just future ones (see BroadcastScanningService for the future-side enforcement). */
    fun getMessages(channelName: String): Flow<List<BroadcastMessageEntity>> {
        return combine(database.broadcastDao().getMessagesForChannel(channelName), getHiddenSenderAddresses()) { messages, hidden ->
            messages.filterNot { it.senderAddress in hidden }
        }
    }

    /** The active account's hidden sender addresses, set via "Hide User" on a sender's avatar — re-emits on account switch, same as everything else here. */
    fun getHiddenSenderAddresses(): Flow<Set<String>> {
        return walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) flowOf(emptySet()) else database.broadcastDao().getHiddenSenderAddresses(address).map { it.toSet() }
        }
    }

    suspend fun hideSender(senderAddress: String) {
        database.broadcastDao().hideSender(HiddenBroadcastSenderEntity(senderAddress, walletManager.getAddress()))
    }

    suspend fun unhideSender(senderAddress: String) {
        database.broadcastDao().unhideSender(senderAddress, walletManager.getAddress())
    }

    /**
     * Inserts an optimistic "pending" placeholder immediately (before the network call) so the
     * message shows up in the room right away and stays visible even if the send fails — matches
     * ChatViewModel.sendMessage's exact pattern for 1:1 chats. On success the placeholder is
     * swapped for the real message (real txId, "sent"); on failure it flips to "failed" in place
     * with a Retry option, rather than disappearing silently.
     */
    suspend fun sendBroadcast(channelName: String, content: String): String {
        val myAddress = walletManager.getAddress()
        val pendingId = "pending_${java.util.UUID.randomUUID()}"
        database.broadcastDao().insertMessage(
            BroadcastMessageEntity(
                id = pendingId,
                channelName = channelName,
                senderAddress = myAddress,
                content = content,
                blockTimestamp = System.currentTimeMillis(),
                deliveryStatus = "pending"
            )
        )
        try {
            val txId = walletService.sendBroadcast(channelName, content).txId
            database.broadcastDao().deleteMessage(pendingId)
            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = channelName,
                    senderAddress = myAddress,
                    content = content,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
            return txId
        } catch (e: Exception) {
            database.broadcastDao().updateMessageStatus(pendingId, "failed")
            throw e
        }
    }

    /** Re-attempts a failed broadcast, reusing its same "pending_<uuid>" id/content — the same placeholder resurrected, not a new message, matching ChatViewModel.retrySendMessage. */
    suspend fun retryBroadcast(message: BroadcastMessageEntity) {
        database.broadcastDao().updateMessageStatus(message.id, "pending")
        try {
            val txId = walletService.sendBroadcast(message.channelName, message.content).txId
            database.broadcastDao().deleteMessage(message.id)
            database.broadcastDao().insertMessage(
                BroadcastMessageEntity(
                    id = txId,
                    channelName = message.channelName,
                    senderAddress = message.senderAddress,
                    content = message.content,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
        } catch (e: Exception) {
            database.broadcastDao().updateMessageStatus(message.id, "failed")
        }
    }
}
