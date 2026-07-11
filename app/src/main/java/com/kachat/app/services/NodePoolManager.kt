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

    // Same DNS seeders the iOS reference app (KaChat) uses — see NodeModels.swift's
    // mainnetDNSSeeds. Each hostname resolves to multiple A records run by independent
    // Kaspa community operators, so unlike the fixed IP list above this can recover on
    // its own if some/all of those hardcoded IPs go stale or become unreachable.
    private val dnsSeedHostnames = listOf(
        "n.seeder1.kaspad.net",
        "n.seeder2.kaspad.net",
        "n.seeder3.kaspad.net",
        "n.seeder4.kaspad.net",
        "kaspadns.kaspacalc.net",
        "n-mainnet.kaspa.ws",
        "kaspa.aspectron.org"
    )
    private val dnsSeedPort = 16110

    private val manualEndpoints = mutableSetOf<String>()
    private val discoveredEndpoints = mutableSetOf<String>()
    private val dnsResolvedEndpoints = mutableSetOf<String>()
    private var lastDnsResolveAt = 0L

    // Real mainnet nodes' GetPeerAddresses responses can list dozens-to-hundreds of
    // peers, and with only 1/6 seeds typically fully "Active" the discovery trigger
    // below fires on almost every cycle. Without a cap, discoveredEndpoints (and the
    // persistent connection opened per address) grew unbounded and caused a real
    // OutOfMemoryError crash on-device after a few minutes of runtime.
    private val maxDiscoveredEndpoints = 20
    private val maxDnsResolvedEndpoints = 20

    // Roughly matches the floor of iOS's NodeProfiler (minActiveNodes = 8, maxActiveNodes = 12)
    // without importing its full EWMA/network-epoch/replacement machinery — this class is
    // deliberately simpler (see the class doc comment). Both DNS-seed refresh and peer-gossip
    // discovery below keep trying as long as the pool has fewer than this many genuinely
    // Active nodes, rather than stopping the moment a handful of the hardcoded seeds respond.
    private val targetActiveNodes = 8

    // Don't re-resolve DNS on every unhealthy 30s probe cycle — a resolver failure/slowness
    // shouldn't turn into a hot loop of lookups; matches iOS's periodic-not-per-cycle refresh.
    private val dnsResolveCooldownMillis = 60_000L

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

    /**
     * Resolves [dnsSeedHostnames] to IP addresses (mirrors iOS's NodeProfiler.refreshDNSSeeds) —
     * the hardcoded [seeds] IPs above have no way to recover if they go stale/unreachable, so this
     * gives the pool an independent way to find fresh nodes without an app update. Cooldown-gated
     * so a run of unhealthy 30s probe cycles doesn't turn into a DNS-lookup hot loop.
     */
    private suspend fun resolveDnsSeedsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastDnsResolveAt < dnsResolveCooldownMillis) return
        lastDnsResolveAt = now
        for (hostname in dnsSeedHostnames) {
            if (dnsResolvedEndpoints.size >= maxDnsResolvedEndpoints) break
            try {
                // IPv4 only, matching iOS's resolveDNSSeed (hints.ai_family = AF_INET) — an IPv6
                // literal's own colons would collide with the ":port" suffix below, and
                // KaspadConnection's plaintext gRPC target string isn't set up to bracket them.
                java.net.InetAddress.getAllByName(hostname)
                    .filterIsInstance<java.net.Inet4Address>()
                    .forEach { addr ->
                        if (dnsResolvedEndpoints.size < maxDnsResolvedEndpoints) {
                            dnsResolvedEndpoints.add("${addr.hostAddress}:$dnsSeedPort")
                        }
                    }
            } catch (e: Exception) {
                // This seed's DNS lookup failed (resolver down, host unreachable, etc.) — try
                // the rest; the cooldown above means all of them get retried again shortly.
            }
        }
    }

    private suspend fun probeCycle() {
        // Gated on truly *Active* count, not just "not yet Quarantined" — a fresh/unprobed seed
        // starts out "Suspect" rather than Quarantined, so gating on non-Quarantined meant this
        // never fired until the hardcoded seeds above had each racked up 3 full failed cycles
        // (~90s) to formally flip to Quarantined. If those seeds are all actually dead (as they
        // periodically seem to go), that's 90 seconds of zero connectivity on every fresh launch
        // before the DNS fallback ever got a chance to run. Checking Active directly means this
        // fires on the very first cycle whenever there isn't already a healthy pool. Compared
        // against targetActiveNodes (not a small fixed number) so this keeps refreshing DNS
        // seeds until the pool is genuinely healthy, not just "a few seeds happened to respond."
        val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
        if (activeCount < targetActiveNodes) {
            resolveDnsSeedsIfNeeded()
        }

        val addresses = (seeds + manualEndpoints + discoveredEndpoints + dnsResolvedEndpoints).distinct()

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
                dnsResolvedEndpoints.contains(result.address) -> "DNS"
                else -> "Discovered"
            }
            registry.update(result.address, type, result)
        }

        // Prune discovered/DNS-resolved nodes that have gone bad, freeing room for potentially
        // better ones and bounding long-term resource usage — never prune Seed/Manual,
        // those are always intentionally tracked regardless of health.
        registry.snapshot()
            .filter { (it.type == "Discovered" || it.type == "DNS") && registry.statusOf(it) == "Quarantined" }
            .forEach { record ->
                discoveredEndpoints.remove(record.address)
                dnsResolvedEndpoints.remove(record.address)
                connections.remove(record.address)?.close()
                registry.remove(record.address)
            }

        // Peer-gossip discovery: only kick in while the pool is unhealthy, reusing an existing
        // connection rather than opening a new one just for this — combined with DNS-seed
        // resolution above, this is the full v1 discovery mechanism (no aggressive/conservative
        // pacing beyond the cooldown/cap already in place).
        //
        // Bootstraps from the best *reachable* node (Active or Suspect), not just a fully
        // "Active" one — GetPeerAddresses only needs a live gRPC response, not a fully synced
        // node, and requiring Active here meant discovery could never get off the ground at
        // all if every seed was reachable-but-unsynced (Suspect) rather than cleanly Active.
        //
        // Gated on the pool's overall Active count against targetActiveNodes, not just the 6
        // hardcoded seeds specifically — the previous "fewer than 3 of the *seeds*" check meant
        // gossip discovery stopped expanding the moment 3 of those 6 fixed IPs happened to
        // respond, even if the pool's real active total was still small. iOS's equivalent keeps
        // discovering until it reaches a real active-node target (8-12), not until a handful of
        // specific bootstrap addresses look fine.
        val activeCountAfterProbe = registry.snapshot().count { registry.statusOf(it) == "Active" }
        if (activeCountAfterProbe < targetActiveNodes && discoveredEndpoints.size < maxDiscoveredEndpoints) {
            val discoverFrom = registry.snapshot()
                .filter { registry.statusOf(it) != "Quarantined" }
                .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
                ?.address
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
     * the best-known Active node; falls back to a DNS-resolved address if one's already
     * been found (more likely to actually be reachable than the static seed list if
     * those have gone stale), and only as a last resort to the first hardcoded seed.
     */
    fun getBroadcastConnection(): KaspadConnection {
        val bestActive = registry.snapshot()
            .filter { registry.statusOf(it) == "Active" }
            .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
        if (bestActive != null) {
            connections[bestActive.address]?.let { return it }
        }
        val fallbackAddress = dnsResolvedEndpoints.firstOrNull() ?: seeds.first()
        return connectionFor(fallbackAddress)
    }

    /** Triggers an immediate out-of-cycle probe pass — "Refresh Pool". */
    fun refreshNow() {
        scope.launch { probeCycle() }
    }

    /** Drops discovered/DNS-resolved/manual nodes and all connections, resets to just the seed list — "Clear Connection Pool". */
    fun clearPool() {
        manualEndpoints.clear()
        discoveredEndpoints.clear()
        dnsResolvedEndpoints.clear()
        lastDnsResolveAt = 0L
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
