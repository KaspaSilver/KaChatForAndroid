package com.kachat.app.services.grpc

import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time health record for one known node address.
 *
 * Deliberately simpler than the EWMA/quarantine-curve scheme sketched in the
 * project's POOLS_v2.md reference doc — a plain consecutive-failure counter is
 * enough for a status *display* (this isn't a routing decision under load that
 * needs a graceful backoff curve).
 */
data class NodeRecord(
    val address: String,
    val type: String, // "Seed" | "Discovered" | "Manual"
    val lastProbe: NodeProbeResult?,
    val consecutiveFailures: Int = 0,
    val lastSuccessAt: Long? = null
)

class NodeRegistry {
    private val records = ConcurrentHashMap<String, NodeRecord>()

    fun update(address: String, type: String, result: NodeProbeResult) {
        val prior = records[address]
        records[address] = NodeRecord(
            address = address,
            type = type,
            lastProbe = result,
            consecutiveFailures = if (result.reachable) 0 else (prior?.consecutiveFailures ?: 0) + 1,
            lastSuccessAt = if (result.reachable) System.currentTimeMillis() else prior?.lastSuccessAt
        )
    }

    fun snapshot(): List<NodeRecord> = records.values.toList()

    fun remove(address: String) {
        records.remove(address)
    }

    /** Resets the registry back to just the given addresses (used by "Clear Connection Pool"). */
    fun resetTo(addresses: List<String>, type: String) {
        records.clear()
        addresses.forEach { records[it] = NodeRecord(address = it, type = type, lastProbe = null) }
    }

    fun containsAddress(address: String): Boolean = records.containsKey(address)

    /** Most recent successful probe across every known node — drives the "Last Sync" field. */
    fun lastSuccessAt(): Long? = records.values.mapNotNull { it.lastSuccessAt }.maxOrNull()

    fun statusOf(r: NodeRecord): String = when {
        r.lastProbe?.reachable != true -> if (r.consecutiveFailures >= 3) "Quarantined" else "Suspect"
        r.lastProbe.isSynced == false -> "Suspect" // reachable but not synced — don't trust its data
        else -> "Active"
    }
}
