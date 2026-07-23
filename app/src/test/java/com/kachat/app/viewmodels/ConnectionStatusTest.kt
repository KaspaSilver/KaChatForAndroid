package com.kachat.app.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {

    private fun node(latency: String) = NodeInfo(
        ip = "1.2.3.4:16110", type = "Seed", latency = latency,
        daaScore = "N/A", status = "Active", color = 0xFF4CD964
    )

    @Test
    fun `zero active nodes is disconnected`() {
        assertEquals(ConnectionStatus.DISCONNECTED, deriveConnectionStatus(emptyList()))
    }

    @Test
    fun `latency under 300ms is connected regardless of active node count`() {
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(listOf(node("10ms"))))
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(listOf(node("50ms"), node("80ms"))))
        assertEquals(ConnectionStatus.CONNECTED, deriveConnectionStatus(listOf(node("299ms"))))
    }

    @Test
    fun `latency at 300ms or above is degraded`() {
        assertEquals(ConnectionStatus.DEGRADED, deriveConnectionStatus(listOf(node("300ms"))))
        assertEquals(ConnectionStatus.DEGRADED, deriveConnectionStatus(listOf(node("1500ms"), node("2000ms"))))
    }

    @Test
    fun `status text is only ever degraded when the dot is orange, never when it's green`() {
        // Regression for the exact bug reported: a green dot (low latency, single
        // active node) must never be paired with "Degraded" status text.
        val status = deriveConnectionStatus(listOf(node("50ms")))
        val dotColor = deriveDotColorHex(listOf(node("50ms")))
        assertEquals(ConnectionStatus.CONNECTED, status)
        assertEquals(0xFF4CD964, dotColor)
    }

    @Test
    fun `dot color is green under 300ms`() {
        assertEquals(0xFF4CD964, deriveDotColorHex(listOf(node("299ms"))))
        assertEquals(0xFF4CD964, deriveDotColorHex(listOf(node("1ms"))))
    }

    @Test
    fun `dot color is orange at 300ms or above while still connected`() {
        assertEquals(0xFFF39C12, deriveDotColorHex(listOf(node("300ms"))))
        assertEquals(0xFFF39C12, deriveDotColorHex(listOf(node("5000ms"))))
    }

    @Test
    fun `dot color is red when there are no active nodes`() {
        assertEquals(0xFFFF3B30, deriveDotColorHex(emptyList()))
    }
}
