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
            val address = try {
                KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain, index)
            } catch (e: Exception) {
                break
            }
            val hasHistory = try {
                api.getTransactions(address, limit = 1).isNotEmpty()
            } catch (e: Exception) {
                Log.w("ColdStorageAddressDiscovery", "Lookup failed for index $index, stopping scan", e)
                break
            }
            val balance = try {
                api.getBalance(address).balance
            } catch (e: Exception) {
                0L
            }
            results.add(DiscoveredAddress(index, address, balance, hasHistory))
            consecutiveUnused = if (hasHistory || balance > 0) 0 else consecutiveUnused + 1
            index++
        }

        return results
    }
}
