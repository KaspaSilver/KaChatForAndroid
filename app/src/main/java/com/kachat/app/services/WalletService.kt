package com.kachat.app.services

import android.util.Log
import com.google.gson.Gson
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.HandshakePayload
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
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
    private val chatRepository: ChatRepository,
    private val knsService: KnsService,
    private val knsInscriptionEngine: KnsInscriptionEngine
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
     * transaction. No handshake is required first: if we've already completed a real
     * handshake with this contact we keep using that legacy alias, otherwise we tag
     * the message with a deterministic alias derived via ECDH from both addresses —
     * the recipient can independently derive the exact same value and find it without
     * ever seeing a handshake (see [WalletManager.theirDeterministicAlias]).
     */
    suspend fun sendKasiaMessage(toContactId: String, text: String): SendResult {
        val recipientPubKey = KaspaAddress.decode(toContactId).second
        val contact = chatRepository.getContact(toContactId)

        val alias = if (contact?.handshakeComplete == true && contact.myAlias != null) {
            contact.myAlias
        } else {
            walletManager.theirDeterministicAlias(toContactId)
        }

        val encrypted = MessageProtocol.encrypt(text, recipientPubKey)
        val payloadBytes = MessageProtocol.buildCommPayload(alias, encrypted)

        val txId = sendKaspa(toAddress = walletManager.getAddress(), amountSompi = 0, payloadBytes = payloadBytes)
        return SendResult(txId, payloadBytes.toHexString())
    }

    /**
     * Sends a "handshake" transaction (0.2 KAS to the recipient) carrying our alias
     * and encrypted for their address-derived public key.
     *
     * NOTE: [handshakeComplete] only reflects "we sent a handshake," not "the recipient
     * acknowledged it" — the real protocol has no explicit handshake-ack message either.
     * [isResponse] marks this as a reply to an incoming request (see [acceptHandshake]),
     * which immediately activates the conversation locally rather than leaving it pending.
     */
    private suspend fun sendHandshake(toAddress: String, recipientPubKey: ByteArray, isResponse: Boolean = false): String {
        val existing = chatRepository.getContact(toAddress)
        // Our protocol alias is a random per-contact ID (real clients validate it as
        // exactly 12 lowercase hex chars) — NOT our human-readable account name. Using
        // the account name here fails that validation on the receiving client and
        // silently breaks the whole handshake/message exchange with it.
        val myAlias = existing?.myAlias ?: generateAlias()

        val payload = HandshakePayload(
            alias = myAlias,
            timestamp = System.currentTimeMillis(),
            conversationId = null,
            recipientAddress = toAddress,
            isResponse = isResponse,
            // Confirms both sides' aliases in one shot on a response — without this, a
            // real Kasia client may not consider its side of the conversation fully
            // active and so never start polling for our self-stashed messages.
            theirAlias = if (isResponse) existing?.theirAlias else null
        )
        val json = Gson().toJson(payload)
        val encrypted = MessageProtocol.encrypt(json, recipientPubKey)
        val payloadBytes = MessageProtocol.buildHandshakePayload(encrypted)

        val txId = sendKaspa(toAddress = toAddress, amountSompi = HANDSHAKE_AMOUNT_SOMPI, payloadBytes = payloadBytes)

        chatRepository.addContact(
            (existing ?: ContactEntity(id = toAddress, walletAddress = walletManager.getAddress(), alias = null, knsName = null, publicKeyHex = null))
                .copy(
                    publicKeyHex = recipientPubKey.toHexString(),
                    handshakeComplete = true,
                    conversationStatus = if (isResponse) "active" else (existing?.conversationStatus ?: "active"),
                    myAlias = myAlias
                )
        )

        // So the sender also sees a "Request to communicate" bubble in their own
        // thread, matching the real reference apps — previously only the recipient
        // ever got a local record of the handshake.
        chatRepository.insertMessage(
            MessageEntity(
                id = txId,
                contactId = toAddress,
                walletAddress = walletManager.getAddress(),
                type = MessageProtocol.TYPE_HANDSHAKE,
                direction = "sent",
                plaintextBody = "[Request to communicate]",
                encryptedPayload = payloadBytes.toHexString(),
                amountSompi = HANDSHAKE_AMOUNT_SOMPI,
                blockTimestamp = System.currentTimeMillis()
            )
        )

        return txId
    }

    /**
     * Accepts an incoming handshake request: sends a real reciprocal handshake
     * transaction and activates the conversation locally. Declining is handled
     * entirely client-side (see ChatViewModel.declineHandshake) — no transaction
     * is sent for a decline, matching the real protocol/reference apps.
     */
    suspend fun acceptHandshake(contactId: String): String {
        val recipientPubKey = KaspaAddress.decode(contactId).second
        return sendHandshake(contactId, recipientPubKey, isResponse = true)
    }

    /**
     * Manually sends an initial (non-response) handshake to a brand new contact —
     * the explicit "start a conversation" action, as opposed to [sendKasiaMessage]'s
     * auto-send-if-needed behavior when the first message goes out.
     */
    suspend fun sendHandshakeToNewContact(contactId: String): String {
        val recipientPubKey = KaspaAddress.decode(contactId).second
        return sendHandshake(contactId, recipientPubKey, isResponse = false)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    data class DomainInscribeResult(
        val domain: String,
        val isReservedDomain: Boolean,
        val serviceFeeSompi: Long,
        val commitTxId: String,
        val revealTxId: String,
        val verified: Boolean
    )

    private data class KnsCreateDomainPayload(val op: String, val p: String, val v: String)

    enum class KnsInscribeStep { CHECKING_AVAILABILITY, FETCHING_FEE, SUBMITTING_COMMIT, SUBMITTING_REVEAL, VERIFYING }

    /**
     * Registers a new `.kas` domain for the wallet's own address — real on-chain commit/reveal
     * inscription, verified against iOS's `KNSDomainInscribeService.inscribeDomain`
     * (`KNSService.swift:1312-1407`). Costs real KAS: a fee-tier lookup determines the price
     * (shorter labels cost dramatically more), paid to KNS's fixed revenue address unless the
     * domain is server-flagged "reserved" (fee waived, reveal goes back to the wallet itself).
     * [onStep] reports progress — this can take 30-90+ seconds (broadcast + verification polling)
     * and the caller is watching real money move, so a silent spinner isn't good enough.
     */
    suspend fun inscribeDomain(rawLabel: String, onStep: (KnsInscribeStep) -> Unit = {}): DomainInscribeResult {
        val label = KnsService.normalizeDomainLabel(rawLabel) ?: throw IllegalArgumentException("Invalid domain label")
        val fullDomain = "$label.kas"
        val myAddress = walletManager.getAddress()

        onStep(KnsInscribeStep.CHECKING_AVAILABILITY)
        val availability = knsService.checkDomainAvailability(myAddress, fullDomain)
        if (!availability.available) throw IllegalStateException("Domain $fullDomain is not available")

        if (!availability.isReservedDomain && !myAddress.startsWith("kaspa:")) {
            throw IllegalStateException("KNS domain inscription revenue address is not configured for testnet")
        }

        onStep(KnsInscribeStep.FETCHING_FEE)
        val feeTiers = knsService.fetchInscribeFeeTiers()
        val tier = KnsService.feeTierForLabel(label)
        val tierFee = KnsService.feeForTier(tier, feeTiers)
        val revealKas = KnsService.revealAmountKas(tierFee, availability.isReservedDomain)
        val commitKas = KnsService.commitAmountKas(revealKas)
        val revealSompi = kasToSompi(revealKas)
        val commitSompi = kasToSompi(commitKas)

        val payloadJson = Gson().toJson(KnsCreateDomainPayload(op = "create", p = "domain", v = label)).toByteArray()
        val revealTarget = if (availability.isReservedDomain) myAddress else MAINNET_REVENUE_ADDRESS

        onStep(KnsInscribeStep.SUBMITTING_COMMIT)
        val commit = knsInscriptionEngine.buildAndSubmitCommit(
            payloadJson = payloadJson,
            commitAmountSompi = commitSompi,
            revealAmountSompi = revealSompi,
            revealTargetAddress = revealTarget,
            operationType = "domain"
        )
        onStep(KnsInscribeStep.SUBMITTING_REVEAL)
        val revealTxId = knsInscriptionEngine.buildAndSubmitReveal(commit, revealTarget)

        onStep(KnsInscribeStep.VERIFYING)
        val verified = verifyDomainOwnership(fullDomain, myAddress)
        refreshBalance()

        return DomainInscribeResult(
            domain = fullDomain,
            isReservedDomain = availability.isReservedDomain,
            serviceFeeSompi = revealSompi,
            commitTxId = commit.commitTxId,
            revealTxId = revealTxId,
            verified = verified
        )
    }

    data class ProfileUpdateResult(val fieldKey: String, val commitTxId: String, val revealTxId: String, val verified: Boolean)

    private data class KnsAddProfilePayload(val op: String, val id: String, val key: String, val value: String)

    /** Flat, self-funded commit/reveal amounts for profile field updates — not tiered like domain registration, and the reveal goes back to the wallet itself (matches iOS's `submitAddProfile` defaults). */
    private val PROFILE_COMMIT_SOMPI = 200_000_000L
    private val PROFILE_REVEAL_SOMPI = 100_000_000L

    /**
     * Sets one on-chain KNS profile field (bio, social links, etc.) on the given domain asset —
     * same commit/reveal engine as [inscribeDomain], but a flat, much smaller, self-funded cost.
     * Verified against iOS's `KNSProfileWriteService.submitAddProfile` (`KNSService.swift:1150-1277`).
     */
    suspend fun updateKnsProfileField(assetId: String, fieldKey: String, value: String, onStep: (KnsInscribeStep) -> Unit = {}): ProfileUpdateResult {
        val trimmedAssetId = assetId.trim()
        require(trimmedAssetId.isNotEmpty()) { "Missing KNS asset id" }
        val trimmedValue = value.trim()
        val myAddress = walletManager.getAddress()

        val payloadJson = Gson().toJson(KnsAddProfilePayload(op = "addProfile", id = trimmedAssetId, key = fieldKey, value = trimmedValue)).toByteArray()

        onStep(KnsInscribeStep.SUBMITTING_COMMIT)
        val commit = knsInscriptionEngine.buildAndSubmitCommit(
            payloadJson = payloadJson,
            commitAmountSompi = PROFILE_COMMIT_SOMPI,
            revealAmountSompi = PROFILE_REVEAL_SOMPI,
            revealTargetAddress = myAddress,
            operationType = "profile"
        )
        onStep(KnsInscribeStep.SUBMITTING_REVEAL)
        val revealTxId = knsInscriptionEngine.buildAndSubmitReveal(commit, myAddress)

        onStep(KnsInscribeStep.VERIFYING)
        val verified = verifyProfileField(trimmedAssetId, fieldKey, trimmedValue)
        refreshBalance()

        return ProfileUpdateResult(fieldKey = fieldKey, commitTxId = commit.commitTxId, revealTxId = revealTxId, verified = verified)
    }

    data class TransferDomainResult(val domain: String, val toAddress: String, val commitTxId: String, val revealTxId: String, val verified: Boolean)

    private data class KnsTransferDomainPayload(val op: String, val p: String, val id: String, val to: String)

    /** Same flat commit cost as a profile edit, but reveal is 0 — ownership moves via the inscription payload's "to" field, not by paying the recipient (matches iOS's `KNSDomainTransferService.transferDomain`, `KNSService.swift:1517-1630`, which reuses the exact same `addProfile` builder functions). */
    private val TRANSFER_COMMIT_SOMPI = 200_000_000L
    private val TRANSFER_REVEAL_SOMPI = 0L

    /**
     * Transfers ownership of an owned domain to another address — irreversible once the reveal
     * confirms. [toAddress] must already be a resolved, validated Kaspa address (any ".kas"
     * resolution and format validation happens earlier, in the caller, so the UI can show the
     * user exactly what address they're sending to before they confirm). Re-validates here too
     * as a backend safety net: rejects sending to your own address, a different network's
     * address, or a domain you no longer actually own.
     */
    suspend fun transferDomain(fullDomain: String, assetId: String, toAddress: String, onStep: (KnsInscribeStep) -> Unit = {}): TransferDomainResult {
        val trimmedAssetId = assetId.trim()
        require(trimmedAssetId.isNotEmpty()) { "Missing KNS asset id" }
        val myAddress = walletManager.getAddress()

        require(KaspaAddress.isValid(toAddress)) { "Invalid recipient address" }
        require(toAddress != myAddress) { "Recipient address must be different from your wallet" }
        require(toAddress.substringBefore(":") == myAddress.substringBefore(":")) { "Recipient address is on the wrong network" }

        val currentOwner = knsService.resolve(fullDomain)
        if (currentOwner != myAddress) throw IllegalStateException("Domain is not owned by current wallet")

        val payloadJson = Gson().toJson(KnsTransferDomainPayload(op = "transfer", p = "domain", id = trimmedAssetId, to = toAddress)).toByteArray()

        onStep(KnsInscribeStep.SUBMITTING_COMMIT)
        val commit = knsInscriptionEngine.buildAndSubmitCommit(
            payloadJson = payloadJson,
            commitAmountSompi = TRANSFER_COMMIT_SOMPI,
            revealAmountSompi = TRANSFER_REVEAL_SOMPI,
            revealTargetAddress = myAddress,
            operationType = "transfer"
        )
        onStep(KnsInscribeStep.SUBMITTING_REVEAL)
        val revealTxId = knsInscriptionEngine.buildAndSubmitReveal(commit, myAddress)

        onStep(KnsInscribeStep.VERIFYING)
        val verified = verifyDomainOwnership(fullDomain, toAddress)
        refreshBalance()

        return TransferDomainResult(domain = fullDomain, toAddress = toAddress, commitTxId = commit.commitTxId, revealTxId = revealTxId, verified = verified)
    }

    /**
     * Uploads a profile avatar/banner image (already downscaled + PNG-encoded by the caller) and
     * writes the resulting URL on-chain — reuses [updateKnsProfileField] for the on-chain half, so
     * this costs exactly one more real commit/reveal transaction (~2/1 KAS) on top of the upload
     * itself, same as any other profile field.
     */
    suspend fun uploadKnsProfileImage(assetId: String, uploadType: String, imageBytes: ByteArray, onStep: (KnsInscribeStep) -> Unit = {}): ProfileUpdateResult {
        val url = knsService.uploadProfileImageWithFallback(assetId, uploadType, imageBytes, walletManager.getPrivateKeyBytes())
        val fieldKey = if (uploadType == "avatar") "avatarUrl" else "bannerUrl"
        return updateKnsProfileField(assetId, fieldKey, url, onStep)
    }

    /** Marks an owned domain as primary — off-chain, free, no transaction. */
    suspend fun setPrimaryDomain(assetId: String) {
        knsService.setPrimaryDomain(assetId.trim(), walletManager.getPrivateKeyBytes())
    }

    /** Polls the profile endpoint until the new field value is indexed, up to 90s — a UX confirmation step only. */
    private suspend fun verifyProfileField(assetId: String, fieldKey: String, expectedValue: String): Boolean {
        val deadlineMs = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadlineMs) {
            if (fieldValue(knsService.getProfile(assetId), fieldKey) == expectedValue) return true
            delay(2_000L)
        }
        return false
    }

    /**
     * Retries just the reveal half of a KNS inscription whose commit already broadcast but whose
     * reveal never completed (app killed, network error, etc.) — reconstructs the exact same
     * commit context that was persisted right after the commit succeeded, so the same funds get
     * revealed rather than left stuck in the P2SH commit output.
     */
    suspend fun retryPendingKnsReveal(pending: com.kachat.app.models.PendingKnsCommit): String {
        val commit = KnsInscriptionEngine.CommitResult(
            commitTxId = pending.commitTxId,
            redeemScript = pending.redeemScriptHex.hexToBytes(),
            commitScriptPubKeyHex = pending.commitScriptPubKeyHex,
            commitAmountSompi = pending.commitAmountSompi,
            revealAmountSompi = pending.revealAmountSompi
        )
        val revealTxId = knsInscriptionEngine.buildAndSubmitReveal(commit, pending.revealTargetAddress)
        refreshBalance()
        return revealTxId
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Polls forward resolution until the new domain's owner matches, up to 90s — a UX confirmation step, not something the broadcast itself depends on. */
    private suspend fun verifyDomainOwnership(fullDomain: String, expectedOwnerAddress: String): Boolean {
        val deadlineMs = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadlineMs) {
            if (knsService.resolve(fullDomain) == expectedOwnerAddress) return true
            delay(2_000L)
        }
        return false
    }

    private fun kasToSompi(kas: Double): Long {
        require(kas >= 0) { "Negative KAS amount is invalid" }
        return Math.round(kas * 100_000_000.0)
    }

    companion object {
        private const val MAINNET_REVENUE_ADDRESS = "kaspa:qyp4nvaq3pdq7609z09fvdgwtc9c7rg07fuw5zgeee7xpr085de59eseqfcmynn"

        /** Real per-conversation pseudonymous alias — 6 random bytes as 12 lowercase hex chars, matching the format both Kasia web and iOS KaChat generate and validate. */
        internal fun generateAlias(): String {
            val bytes = ByteArray(6)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Maps a KNS profile field key (e.g. "bio", "avatarUrl") to its current value — shared by verification polling and the Edit KNS Profile screen's change-diffing. */
        internal fun fieldValue(profile: KnsProfileFields?, fieldKey: String): String? = when (fieldKey) {
            "bio" -> profile?.bio
            "avatarUrl" -> profile?.avatarUrl
            "x" -> profile?.x
            "website" -> profile?.website
            "telegram" -> profile?.telegram
            "discord" -> profile?.discord
            "contactEmail" -> profile?.contactEmail
            "github" -> profile?.github
            "redirectUrl" -> profile?.redirectUrl
            "bannerUrl" -> profile?.bannerUrl
            else -> null
        }
    }
}
