package com.kachat.app.services.grpc

/**
 * Result of probing one Kaspa node via real gRPC calls (GetInfo + GetBlockDagInfo).
 */
data class NodeProbeResult(
    val address: String,
    val reachable: Boolean,
    val latencyMs: Long?,
    val isSynced: Boolean?,
    val isUtxoIndexed: Boolean?,
    val serverVersion: String?,
    val networkName: String?,
    val virtualDaaScore: Long?,
    val error: String? = null
)

/**
 * Probes an already-connected [connection] via GetInfo + GetBlockDagInfo. Does NOT
 * open or close the connection — gRPC channels are meant to be long-lived and reused
 * across many calls, not opened/closed per probe. (An earlier version of this
 * function opened and tore down a fresh channel on every call; on-device testing
 * found that churn accumulated native/thread resources fast enough to get the app
 * OOM-killed by the OS every ~90-140 seconds. Connection lifecycle is now owned by
 * whoever holds the persistent connection — see NodePoolManager.)
 */
suspend fun probeExisting(address: String, connection: KaspadConnection): NodeProbeResult {
    return try {
        val start = System.currentTimeMillis()
        val info = connection.getInfo()
        val latency = System.currentTimeMillis() - start
        val dagInfo = connection.getBlockDagInfo()
        NodeProbeResult(
            address = address,
            reachable = true,
            latencyMs = latency,
            isSynced = info.isSynced,
            isUtxoIndexed = info.isUtxoIndexed,
            serverVersion = info.serverVersion,
            networkName = dagInfo.networkName,
            virtualDaaScore = dagInfo.virtualDaaScore
        )
    } catch (e: Exception) {
        NodeProbeResult(
            address = address,
            reachable = false,
            latencyMs = null,
            isSynced = null,
            isUtxoIndexed = null,
            serverVersion = null,
            networkName = null,
            virtualDaaScore = null,
            error = e.message
        )
    }
}
