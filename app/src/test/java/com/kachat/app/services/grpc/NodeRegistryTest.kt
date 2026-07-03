package com.kachat.app.services.grpc

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeRegistryTest {

    private fun reachable(address: String, synced: Boolean = true) = NodeProbeResult(
        address = address, reachable = true, latencyMs = 42, isSynced = synced,
        isUtxoIndexed = true, serverVersion = "1.0.0", networkName = "mainnet", virtualDaaScore = 123L
    )

    private fun unreachable(address: String) = NodeProbeResult(
        address = address, reachable = false, latencyMs = null, isSynced = null,
        isUtxoIndexed = null, serverVersion = null, networkName = null, virtualDaaScore = null, error = "timeout"
    )

    @Test
    fun `a freshly successful node is Active`() {
        val registry = NodeRegistry()
        registry.update("1.2.3.4:16110", "Seed", reachable("1.2.3.4:16110"))

        val record = registry.snapshot().single()
        assertEquals("Active", registry.statusOf(record))
        assertEquals(0, record.consecutiveFailures)
    }

    @Test
    fun `reachable but not synced is Suspect, not Active`() {
        val registry = NodeRegistry()
        registry.update("1.2.3.4:16110", "Seed", reachable("1.2.3.4:16110", synced = false))

        val record = registry.snapshot().single()
        assertEquals("Suspect", registry.statusOf(record))
    }

    @Test
    fun `one or two failures is Suspect, not yet Quarantined`() {
        val registry = NodeRegistry()
        val address = "1.2.3.4:16110"
        registry.update(address, "Seed", unreachable(address))
        assertEquals("Suspect", registry.statusOf(registry.snapshot().single()))

        registry.update(address, "Seed", unreachable(address))
        val record = registry.snapshot().single()
        assertEquals(2, record.consecutiveFailures)
        assertEquals("Suspect", registry.statusOf(record))
    }

    @Test
    fun `three consecutive failures becomes Quarantined`() {
        val registry = NodeRegistry()
        val address = "1.2.3.4:16110"
        repeat(3) { registry.update(address, "Seed", unreachable(address)) }

        val record = registry.snapshot().single()
        assertEquals(3, record.consecutiveFailures)
        assertEquals("Quarantined", registry.statusOf(record))
    }

    @Test
    fun `a success resets the failure counter and recovers to Active`() {
        val registry = NodeRegistry()
        val address = "1.2.3.4:16110"
        repeat(3) { registry.update(address, "Seed", unreachable(address)) }
        registry.update(address, "Seed", reachable(address))

        val record = registry.snapshot().single()
        assertEquals(0, record.consecutiveFailures)
        assertEquals("Active", registry.statusOf(record))
    }

    @Test
    fun `resetTo replaces the entire registry contents`() {
        val registry = NodeRegistry()
        registry.update("old:16110", "Discovered", reachable("old:16110"))

        registry.resetTo(listOf("seed1:16110", "seed2:16110"), "Seed")

        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(setOf("seed1:16110", "seed2:16110"), snapshot.map { it.address }.toSet())
    }

    @Test
    fun `lastSuccessAt reflects the most recent successful probe across all nodes`() {
        val registry = NodeRegistry()
        registry.update("a:16110", "Seed", unreachable("a:16110"))
        assertEquals(null, registry.lastSuccessAt())

        registry.update("b:16110", "Seed", reachable("b:16110"))
        assertEquals(true, registry.lastSuccessAt()!! > 0)
    }
}
