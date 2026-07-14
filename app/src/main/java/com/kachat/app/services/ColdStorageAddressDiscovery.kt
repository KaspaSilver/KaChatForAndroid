package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaspaExtendedPublicKey
import org.bitcoinj.crypto.DeterministicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gap-limit scan over a kpub's derived addresses — the Cold Storage analogue of
 * [SpendingAddressDiscovery], but for a watch-only public key rather than a locally-held
 * mnemonic, and returning every discovered address with its live balance rather than just a
 * single boundary index, since the Cold Storage detail screen needs to show the whole
 * used-address history, not just "the current one."
 */
@Singleton
class ColdStorageAddressDiscovery @Inject constructor(
    private val networkService: NetworkService
) {
    data class DiscoveredAddress(val index: Int, val address: String, val balanceSompi: Long, val hasHistory: Boolean)

    /**
     * @param chain 0 = external/receive, 1 = internal/change (KaChat only ever sources sends/
     * change from chain 0 for cold storage — see [KaspaExtendedPublicKey] doc).
     */
    suspend fun discoverAddresses(rootKey: DeterministicKey, chain: Int = 0, gapLimit: Int = 5): List<DiscoveredAddress> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val results = mutableListOf<DiscoveredAddress>()
        var consecutiveUnused = 0
        var index = 0

        while (consecutiveUnused < gapLimit) {
            val result = checkAddress(rootKey, chain, index) ?: break
            results.add(result)
            consecutiveUnused = if (result.hasHistory || result.balanceSompi > 0) 0 else consecutiveUnused + 1
            index++
        }

        return results
    }

    /**
     * One specific address's live balance/history, outside the gap-limit scan — used to pull in
     * an index a user manually generated past the scan's own stopping point (see
     * [com.kachat.app.viewmodels.ColdStorageViewModel.generateMoreAddresses]), which
     * [discoverAddresses] alone would never reach on a fresh unused-account rescan.
     */
    suspend fun checkAddress(rootKey: DeterministicKey, chain: Int, index: Int): DiscoveredAddress? {
        val api = networkService.kaspaRestApi.value ?: return null
        val address = try {
            KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain, index)
        } catch (e: Exception) {
            return null
        }
        val hasHistory = try {
            api.getTransactions(address, limit = 1).isNotEmpty()
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Lookup failed for index $index", e)
            return null
        }
        val balance = try {
            api.getBalance(address).balance
        } catch (e: Exception) {
            0L
        }
        return DiscoveredAddress(index, address, balance, hasHistory)
    }

    data class AddressTransaction(
        val txId: String,
        val sent: Boolean, // true = this address was a sender on this tx
        val amountSompi: Long, // net amount that left (sent) or arrived (received) — excludes change back to itself
        val blockTimeMillis: Long?
    )

    /**
     * On-chain transaction history for a single address, newest first. Direction/amount aren't
     * fields the REST API returns directly — a tx is only "sent" from [address] if one of its
     * inputs' resolved previous-outpoint address matches (the default `resolve_previous_outpoints`
     * behavior on [KaspaRestApi.getTransactions] already resolves this); the amount then excludes
     * whatever output pays change back to [address] itself, mirroring the same sent-vs-received
     * inference [com.kachat.app.repository.ChatRepository]'s payment sync already relies on.
     */
    suspend fun getTransactionHistory(address: String, limit: Int = 50): List<AddressTransaction> {
        val api = networkService.kaspaRestApi.value ?: return emptyList()
        val transactions = try {
            api.getTransactions(address, limit = limit)
        } catch (e: Exception) {
            Log.w("ColdStorageAddressDiscovery", "Failed to fetch transaction history for $address", e)
            return emptyList()
        }
        return transactions.map { tx ->
            val sent = tx.inputs.any { it.previousOutpointAddress == address }
            val amount = if (sent) {
                tx.outputs.filter { it.scriptPublicKeyAddress != address }.sumOf { it.amount }
            } else {
                tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
            }
            AddressTransaction(tx.transactionId, sent, amount, tx.blockTime)
        }.sortedByDescending { it.blockTimeMillis ?: 0L }
    }
}
