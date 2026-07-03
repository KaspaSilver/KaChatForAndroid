package com.kachat.app.services

import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.services.grpc.NodeRecord
import com.kachat.app.services.grpc.NodeRegistry
import com.kachat.app.services.grpc.probeExisting
import com.kachat.app.viewmodels.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real gRPC-backed Kaspa node pool: probes seed/discovered/manual nodes over the
 * actual `protowire.RPC` MessageStream (GetInfo/GetBlockDagInfo/GetPeerAddresses),
 * replacing the previous fake always-healthy simulation.
 *
 * Scoped deliberately simpler than the full POOLS_v2.md architecture (no EWMA
 * scoring, no network-epoch tracking, no hedged requests — see the "explicit
 * non-goals" section of the implementation plan): this drives a status *display*,
 * not a live routing decision under load.
 */
@Singleton
class NodePoolManager @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = NodeRegistry()

    // Persistent, reused gRPC connections — one per known node address. gRPC channels
    // are designed to be long-lived and reused across many calls; an earlier version
    // of this class opened and closed a fresh channel per node on every 30s probe
    // cycle, and on-device testing found that churn accumulated enough native/thread
    // resources to get the app OOM-killed roughly every 90-140 seconds. Connections
    // now live here and are only torn down on clearPool()/reconnect() or when a probe
    // reveals the underlying stream has died.
    private val connections = ConcurrentHashMap<String, KaspadConnection>()

    private val _activeNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val activeNodes: StateFlow<List<NodeInfo>> = _activeNodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val allNodes: StateFlow<List<NodeInfo>> = _allNodes.asStateFlow()

    private val seeds = listOf(
        "67.235.212.32:16110",
        "147.135.70.51:16110",
        "187.124.234.86:16110",
        "152.53.52.37:16110",
        "84.32.32.37:16110",
        "198.52.142.150:16110"
    )

    private val manualEndpoints = mutableSetOf<String>()
    private val discoveredEndpoints = mutableSetOf<String>()

    // Real mainnet nodes' GetPeerAddresses responses can list dozens-to-hundreds of
    // peers, and with only 1/6 seeds typically fully "Active" the discovery trigger
    // below fires on almost every cycle. Without a cap, discoveredEndpoints (and the
    // persistent connection opened per address) grew unbounded and caused a real
    // OutOfMemoryError crash on-device after a few minutes of runtime.
    private val maxDiscoveredEndpoints = 20

    private var probeJob: Job? = null

    init {
        registry.resetTo(seeds, "Seed")
        startProbing()
    }

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                probeCycle()
                delay(30_000)
            }
        }
    }

    private fun connectionFor(address: String): KaspadConnection =
        connections.getOrPut(address) { KaspadConnection(address, scope).also { it.connect() } }

    private suspend fun probeCycle() {
        val addresses = (seeds + manualEndpoints + discoveredEndpoints).distinct()

        // Drop connections for addresses no longer tracked (e.g. after clearPool()).
        connections.keys.filter { it !in addresses }.forEach { addr -> connections.remove(addr)?.close() }

        val results = addresses.map { address ->
            scope.async {
                val result = probeExisting(address, connectionFor(address))
                if (!result.reachable) {
                    // The underlying stream likely died — drop it so the next cycle
                    // opens a fresh connection instead of retrying a broken one forever.
                    connections.remove(address)?.close()
                }
                result
            }
        }.awaitAll()

        results.forEach { result ->
            val type = when {
                seeds.contains(result.address) -> "Seed"
                manualEndpoints.contains(result.address) -> "Manual"
                else -> "Discovered"
            }
            registry.update(result.address, type, result)
        }

        // Prune discovered nodes that have gone bad, freeing room for potentially
        // better ones and bounding long-term resource usage — never prune Seed/Manual,
        // those are always intentionally tracked regardless of health.
        registry.snapshot()
            .filter { it.type == "Discovered" && registry.statusOf(it) == "Quarantined" }
            .forEach { record ->
                discoveredEndpoints.remove(record.address)
                connections.remove(record.address)?.close()
                registry.remove(record.address)
            }

        // Discovery: only kick in while the pool is unhealthy, reusing an existing
        // healthy connection rather than opening a new one just for this — the entire
        // v1 discovery mechanism (no DNS seed resolution, no aggressive/conservative pacing).
        val activeSeedCount = registry.snapshot()
            .count { seeds.contains(it.address) && registry.statusOf(it) == "Active" }
        if (activeSeedCount < 3 && discoveredEndpoints.size < maxDiscoveredEndpoints) {
            val discoverFrom = registry.snapshot().firstOrNull { registry.statusOf(it) == "Active" }?.address
            val conn = discoverFrom?.let { connections[it] }
            if (conn != null) {
                try {
                    conn.getPeerAddresses().addressesList.map { it.addr }.forEach { addr ->
                        if (addr.isNotBlank() && !seeds.contains(addr) && !manualEndpoints.contains(addr) &&
                            discoveredEndpoints.size < maxDiscoveredEndpoints
                        ) {
                            discoveredEndpoints.add(addr)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore — next cycle will retry with whatever's active then.
                }
            }
        }

        publish()
    }

    private fun publish() {
        val allInfo = registry.snapshot()
            .map(::toNodeInfo)
            .sortedWith(
                compareByDescending<NodeInfo> { it.status == "Active" }
                    .thenBy { it.latency.removeSuffix("ms").toIntOrNull() ?: Int.MAX_VALUE }
            )
        _allNodes.value = allInfo
        _activeNodes.value = allInfo.filter { it.status == "Active" }
    }

    private fun toNodeInfo(record: NodeRecord): NodeInfo {
        val status = registry.statusOf(record)
        val color = when (status) {
            "Active" -> 0xFF4CD964
            "Suspect" -> 0xFFF39C12
            else -> 0xFFFF3B30 // Quarantined / unreachable
        }
        return NodeInfo(
            ip = record.address,
            type = record.type,
            latency = record.lastProbe?.latencyMs?.let { "${it}ms" } ?: "—",
            distance = "Unknown", // no geolocation data source exists or is in scope — not fabricated
            country = "Unknown",
            daaScore = record.lastProbe?.virtualDaaScore?.toString() ?: "N/A",
            status = status,
            color = color
        )
    }

    /** Real "Last Sync" source — most recent successful probe across every known node. */
    fun lastSuccessAt(): Long? = registry.lastSuccessAt()

    /**
     * Returns a connection to a currently healthy node, for broadcasting a transaction
     * via gRPC SubmitTransaction — bypasses the REST gateway, which mishandles
     * payload-carrying transactions (see KaspadConnection.submitTransaction). Prefers
     * the best-known Active node; falls back to the first seed directly if the pool
     * hasn't found an Active node yet (e.g. right after app launch, before the first
     * probe cycle completes).
     */
    fun getBroadcastConnection(): KaspadConnection {
        val bestActive = registry.snapshot()
            .filter { registry.statusOf(it) == "Active" }
            .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
        if (bestActive != null) {
            connections[bestActive.address]?.let { return it }
        }
        return connectionFor(seeds.first())
    }

    /** Triggers an immediate out-of-cycle probe pass — "Refresh Pool". */
    fun refreshNow() {
        scope.launch { probeCycle() }
    }

    /** Drops discovered/manual nodes and all connections, resets to just the seed list — "Clear Connection Pool". */
    fun clearPool() {
        manualEndpoints.clear()
        discoveredEndpoints.clear()
        connections.values.forEach { it.close() }
        connections.clear()
        registry.resetTo(seeds, "Seed")
        publish()
        refreshNow()
    }

    /** Drops all persistent connections so the next probe cycle opens fresh ones — "Reconnect". */
    fun reconnect() {
        connections.values.forEach { it.close() }
        connections.clear()
        refreshNow()
    }

    /** Adds and immediately probes a user-supplied "host:port" endpoint — "Add Custom Endpoint". */
    fun addManualEndpoint(address: String) {
        val trimmed = address.trim()
        if (!trimmed.matches(Regex("^[^:\\s]+:\\d+$"))) return
        manualEndpoints.add(trimmed)
        scope.launch {
            registry.update(trimmed, "Manual", probeExisting(trimmed, connectionFor(trimmed)))
            publish()
        }
    }
}
