package com.kachat.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {

    private fun node(latency: String) = NodeInfo(
        ip = "1.2.3.4:16110", type = "Seed", latency = latency, distance = "Unknown",
        country = "Unknown", daaScore = "N/A", status = "Active", color = 0xFF4CD964
    )

    @Test
    fun `zero active nodes is disconnected`() {
        assertEquals(ConnectionStatus.DISCONNECTED, deriveConnectionStatus(emptyList()))
    }

    @Test
    fun `low latency is connected regardless of active node count`() {
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(listOf(node("10ms"))))
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(listOf(node("50ms"), node("80ms"))))
    }

    @Test
    fun `latency between 150 and 200ms is degraded, not weak`() {
        assertEquals(ConnectionStatus.DEGRADED, deriveConnectionStatus(listOf(node("151ms"))))
        assertEquals(ConnectionStatus.DEGRADED, deriveConnectionStatus(listOf(node("200ms"))))
    }

    @Test
    fun `latency above 200ms is weak`() {
        assertEquals(ConnectionStatus.WEAK, deriveConnectionStatus(listOf(node("1500ms"), node("2000ms"))))
        assertEquals(ConnectionStatus.WEAK, deriveConnectionStatus(listOf(node("201ms"))))
    }

    @Test
    fun `status text is only ever weak when the dot is red, never when it's green`() {
        // Regression for the exact bug reported: a green dot (low latency, single
        // active node) must never be paired with "Weak" status text.
        val status = deriveConnectionStatus(listOf(node("50ms")))
        val dotColor = deriveDotColorHex(listOf(node("50ms")))
        assertEquals(ConnectionStatus.CONNECTED, status)
        assertEquals(0xFF4CD964, dotColor)
    }

    @Test
    fun `dot color is green at 150ms or lower`() {
        assertEquals(0xFF4CD964, deriveDotColorHex(listOf(node("150ms"))))
        assertEquals(0xFF4CD964, deriveDotColorHex(listOf(node("1ms"))))
    }

    @Test
    fun `dot color is orange between 150 and 200ms`() {
        assertEquals(0xFFF39C12, deriveDotColorHex(listOf(node("151ms"))))
        assertEquals(0xFFF39C12, deriveDotColorHex(listOf(node("200ms"))))
    }

    @Test
    fun `dot color is red above 200ms`() {
        assertEquals(0xFFFF3B30, deriveDotColorHex(listOf(node("201ms"))))
        assertEquals(0xFFFF3B30, deriveDotColorHex(listOf(node("5000ms"))))
    }

    @Test
    fun `dot color is red when there are no active nodes`() {
        assertEquals(0xFFFF3B30, deriveDotColorHex(emptyList()))
    }
}
