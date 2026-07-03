package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * KaspaWalletEngine — handles low-level transaction construction and broadcasting.
 * Follows the required send flow: Fetch UTXOs -> Build -> Sign -> Broadcast.
 */
@Singleton
class KaspaWalletEngine @Inject constructor(
    private val networkService: NetworkService,
    private val walletManager: WalletManager,
    private val nodePoolManager: NodePoolManager
) {
    // The REST UTXO endpoint lags behind what we've actually broadcast — sending twice
    // in quick succession (e.g. two chat messages back to back) would otherwise select
    // the same still-unconfirmed input for both, and the node rejects the second
    // transaction as an orphan. Track what we've locally already spent/created, keyed
    // by address (multi-account safe), and reconcile against every fresh fetch — an
    // entry is dropped once the REST endpoint's own list confirms it caught up, so this
    // never grows unbounded and never diverges from on-chain reality for long.
    private val pendingSpentOutpoints = mutableMapOf<String, MutableSet<String>>()
    private val pendingChangeUtxos = mutableMapOf<String, MutableList<UtxoEntry>>()

    // Rapid-fire sends (e.g. several chat messages tapped in quick succession) each
    // spawn their own coroutine — without this, two could fetch the same UTXO snapshot
    // before either records its spend, both select the same input, and every one after
    // the first gets rejected by the node as a double-spend/orphan. Retrying doesn't
    // help on its own since a retry can race the same way. This mutex makes the whole
    // fetch -> select -> broadcast -> record sequence one atomic unit per send, so every
    // send observes the true state left by the one before it.
    private val sendMutex = Mutex()

    /**
     * Fresh UTXOs reconciled against our own not-yet-indexed sends: excludes anything we
     * know we've already spent, and adds back our own pending change output(s) so a
     * second send doesn't need to wait for the first to confirm.
     */
    private fun reconcileUtxos(address: String, freshUtxos: List<UtxoEntry>): List<UtxoEntry> {
        val spent = pendingSpentOutpoints.getOrPut(address) { mutableSetOf() }
        val change = pendingChangeUtxos.getOrPut(address) { mutableListOf() }
        return reconcilePendingUtxos(freshUtxos, spent, change)
    }

    /** Call after a successful broadcast so the very next send (before this one confirms) doesn't reuse or miss these UTXOs. */
    private fun recordSpend(address: String, spentUtxos: List<UtxoEntry>, changeUtxo: UtxoEntry?) {
        val spent = pendingSpentOutpoints.getOrPut(address) { mutableSetOf() }
        val change = pendingChangeUtxos.getOrPut(address) { mutableListOf() }
        applySpend(spent, change, spentUtxos, changeUtxo)
    }

    /**
     * Sends Kaspa to a given address.
     * @param toAddress Recipient Kaspa address.
     * @param amountSompi Amount to send in sompi (1 KAS = 100,000,000 sompi).
     * @return Result containing the transaction ID or an error.
     */
    suspend fun sendKaspa(toAddress: String, amountSompi: Long, payloadBytes: ByteArray? = null): Result<String> = sendMutex.withLock {
        try {
            // 1. Validate address
            if (!isValidAddress(toAddress)) {
                return Result.failure(IllegalArgumentException("Invalid recipient address: $toAddress"))
            }

            val fromAddress = walletManager.getAddress()
            val api = networkService.kaspaRestApi.value ?: return Result.failure(IllegalStateException("Network service unavailable"))

            // 2. Fetch UTXOs from node, reconciled against our own not-yet-indexed sends.
            val utxos = reconcileUtxos(fromAddress, api.getUtxos(fromAddress))
            if (utxos.isEmpty()) {
                return Result.failure(IllegalStateException("Insufficient funds: No UTXOs found"))
            }

            // 3. Fetch network fee rate (sompi per mass-gram) — always at least the
            // network-enforced minimum, since a quoted rate below that would still
            // get rejected on broadcast.
            val feeRateSompiPerGram = try {
                val estimate = api.getFeeEstimate()
                val quoted = estimate.normalBuckets.firstOrNull()?.feerate
                    ?: KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM.toDouble()
                ceil(quoted).toLong().coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            } catch (e: Exception) {
                Log.w("KaspaWalletEngine", "Failed to fetch fee estimate, using network minimum", e)
                KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM
            }

            val recipientScriptHex = KaspaAddress.getScriptPublicKey(toAddress)
            val changeScriptHex = KaspaAddress.getScriptPublicKey(fromAddress)

            // 4. UTXO selection and fee calculation using Kaspa's real mass model
            val selectionResult = selectUtxosAndCalculateFee(
                utxos = utxos,
                amountSompi = amountSompi,
                feeRateSompiPerGram = feeRateSompiPerGram,
                payloadBytes = payloadBytes,
                recipientScriptLen = recipientScriptHex.length / 2,
                changeScriptLen = changeScriptHex.length / 2
            )
            if (selectionResult.totalSelected < selectionResult.requiredAmount) {
                return Result.failure(IllegalStateException("Insufficient funds: Needed ${selectionResult.requiredAmount}, have ${selectionResult.totalSelected}"))
            }

            // 5. Create transaction outputs (recipient + change).
            // Skip a zero-amount recipient output (e.g. self-stash messages where
            // amountSompi=0) — a 0-value output is non-standard and gets rejected;
            // the full remaining balance goes out via the change output instead.
            val outputs = mutableListOf<RawOutputWithVersion>()
            if (selectionResult.finalAmount > 0) {
                outputs.add(
                    RawOutputWithVersion(
                        amount = selectionResult.finalAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(recipientScriptHex, 0)
                    )
                )
            }
            var changeOutputIndex = -1
            if (selectionResult.changeAmount > 500) { // Minimum dust threshold
                changeOutputIndex = outputs.size
                outputs.add(
                    RawOutputWithVersion(
                        amount = selectionResult.changeAmount,
                        scriptPublicKey = ScriptPublicKeyWithVersion(changeScriptHex, 0)
                    )
                )
            }
            if (outputs.isEmpty()) {
                return Result.failure(IllegalStateException("Insufficient funds to cover network fee"))
            }

            val payloadHex = payloadBytes?.joinToString("") { "%02x".format(it) }

            val rawTx = RawTransaction(
                inputs = selectionResult.selectedUtxos.map { utxo ->
                    RawInput(previousOutpoint = utxo.outpoint, signatureScript = "")
                },
                outputs = outputs,
                gas = 0,
                payload = payloadHex
            )

            // 6. Sign transaction with private key (locally)
            val signedTx = KaspaTransactionSigner.signTransaction(
                rawTx = rawTx,
                utxos = selectionResult.selectedUtxos,
                privateKey = walletManager.getPrivateKeyBytes()
            )

            // 7. Broadcast transaction.
            // The REST gateway (api.kaspa.org POST /transactions) works fine for plain
            // payments but rejects payload-carrying transactions with a false
            // "signature script" failure — the signature is cryptographically valid
            // (verified against official rusty-kaspa test vectors), so the bug is in
            // the REST gateway's JSON-to-RPC payload translation, not the transaction
            // itself. The iOS reference app never uses REST for broadcast either — it
            // submits exclusively over gRPC — so payload-carrying sends go straight to
            // a node via gRPC here, bypassing the REST gateway entirely. Plain payments
            // (no payload) keep using REST since that path is already proven working.
            val transactionId = if (payloadBytes != null) {
                nodePoolManager.getBroadcastConnection().submitTransaction(signedTx)
            } else {
                api.postTransaction(PostTransactionRequest(signedTx)).transactionId
            }

            val changeUtxo = if (changeOutputIndex >= 0) {
                UtxoEntry(
                    address = fromAddress,
                    outpoint = Outpoint(transactionId = transactionId, index = changeOutputIndex),
                    utxoEntry = UtxoData(
                        amount = selectionResult.changeAmount,
                        scriptPublicKey = ScriptPublicKey(changeScriptHex),
                        blockDaaScore = 0,
                        isCoinbase = false
                    )
                )
            } else null
            recordSpend(fromAddress, selectionResult.selectedUtxos, changeUtxo)

            Result.success(transactionId)
        } catch (e: Exception) {
            Log.e("KaspaWalletEngine", "Error sending Kaspa", e)
            Result.failure(e)
        }
    }

    private fun isValidAddress(address: String): Boolean {
        return try {
            // Basic validation using KaspaAddress utility
            KaspaAddress.getScriptPublicKey(address).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun selectUtxosAndCalculateFee(
        utxos: List<UtxoEntry>,
        amountSompi: Long,
        feeRateSompiPerGram: Long,
        payloadBytes: ByteArray?,
        recipientScriptLen: Int,
        changeScriptLen: Int
    ) = KaspaUtxoSelector.selectUtxosAndCalculateFee(
        utxos, amountSompi, feeRateSompiPerGram, payloadBytes, recipientScriptLen, changeScriptLen
    )

    companion object {
        internal fun outpointKey(outpoint: Outpoint) = "${outpoint.transactionId}:${outpoint.index}"

        /**
         * Pure reconciliation logic (no network/DI dependencies) — mutates [pendingSpentKeys]/
         * [pendingChange] in place to drop entries the fresh fetch confirms are caught up,
         * and returns the fresh list with our own not-yet-indexed spend/change applied.
         */
        internal fun reconcilePendingUtxos(
            freshUtxos: List<UtxoEntry>,
            pendingSpentKeys: MutableSet<String>,
            pendingChange: MutableList<UtxoEntry>
        ): List<UtxoEntry> {
            val freshKeys = freshUtxos.map { outpointKey(it.outpoint) }.toSet()
            pendingSpentKeys.retainAll(freshKeys)
            pendingChange.removeAll { outpointKey(it.outpoint) in freshKeys }
            return freshUtxos.filter { outpointKey(it.outpoint) !in pendingSpentKeys } + pendingChange
        }

        /**
         * Pure spend-recording logic — mutates [pendingSpentKeys]/[pendingChange] in place.
         * Critically, a just-spent input must be dropped from [pendingChange] immediately:
         * otherwise a spent synthetic change UTXO stays "available" forever (it can never
         * naturally disappear via [reconcilePendingUtxos]'s fresh-fetch check, since a UTXO
         * spent before it even confirms never shows up as fresh on its own) and keeps
         * getting greedily re-selected — causing every send after it to fail with the same
         * "already spent in the mempool" rejection, unfixable by retrying.
         */
        internal fun applySpend(
            pendingSpentKeys: MutableSet<String>,
            pendingChange: MutableList<UtxoEntry>,
            spentUtxos: List<UtxoEntry>,
            newChangeUtxo: UtxoEntry?
        ) {
            val spentKeys = spentUtxos.map { outpointKey(it.outpoint) }.toSet()
            pendingSpentKeys.addAll(spentKeys)
            pendingChange.removeAll { outpointKey(it.outpoint) in spentKeys }
            if (newChangeUtxo != null) {
                pendingChange.add(newChangeUtxo)
            }
        }
    }
}
