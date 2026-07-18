package com.kachat.app.models

/** Which block explorer website transaction links open in — user picks in Settings > Kaspa Explorer. */
enum class KaspaExplorer(val displayName: String, private val txBaseUrl: String) {
    KASPA_STREAM("kaspa.stream", "https://kaspa.stream/transactions/"),
    KASPA_ORG("explorer.kaspa.org", "https://explorer.kaspa.org/txs/");

    fun txUrl(txId: String): String = "$txBaseUrl$txId"

    companion object {
        val default: KaspaExplorer = KASPA_ORG

        fun fromName(name: String?): KaspaExplorer =
            entries.firstOrNull { it.name == name } ?: default
    }
}
