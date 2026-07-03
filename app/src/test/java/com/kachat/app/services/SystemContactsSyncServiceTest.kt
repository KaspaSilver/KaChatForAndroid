package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemContactsSyncServiceTest {

    private val mainnetAddress = "kaspa:qq4ntwy65nuqcdmwdz2y2pdya59k8kryhled2gfcavm0yspg55e0grmjx5e7a"
    private val testnetAddress = "kaspatest:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    @Test
    fun `extracts a bare mainnet address`() {
        assertEquals(listOf(mainnetAddress), SystemContactsSyncService.extractKaspaAddresses(mainnetAddress))
    }

    @Test
    fun `extracts a bare testnet address`() {
        assertEquals(listOf(testnetAddress), SystemContactsSyncService.extractKaspaAddresses(testnetAddress))
    }

    @Test
    fun `is case-insensitive`() {
        val upper = mainnetAddress.uppercase()
        val found = SystemContactsSyncService.extractKaspaAddresses(upper)
        assertEquals(1, found.size)
        assertEquals(upper, found[0])
    }

    @Test
    fun `finds an address embedded inside a longer string`() {
        val text = "My kaspa wallet: $mainnetAddress (don't share!)"
        assertEquals(listOf(mainnetAddress), SystemContactsSyncService.extractKaspaAddresses(text))
    }

    @Test
    fun `finds multiple addresses in one field`() {
        val text = "$mainnetAddress and also $testnetAddress"
        assertEquals(listOf(mainnetAddress, testnetAddress), SystemContactsSyncService.extractKaspaAddresses(text))
    }

    @Test
    fun `no match without the kaspa colon prefix`() {
        val bareLookingString = mainnetAddress.removePrefix("kaspa:")
        assertTrue(SystemContactsSyncService.extractKaspaAddresses(bareLookingString).isEmpty())
    }

    @Test
    fun `no match on unrelated text`() {
        assertTrue(SystemContactsSyncService.extractKaspaAddresses("just a regular phone number 555-1234").isEmpty())
    }

    private fun row(contactId: Long, lookupKey: String, name: String, value: String) =
        ScannedRow(contactId, lookupKey, name, value)

    @Test
    fun `matches a scanned row to a known address`() {
        val rows = listOf(row(1, "lk1", "Alice", mainnetAddress))
        val result = SystemContactsSyncService.matchScannedRows(rows, setOf(mainnetAddress))
        assertEquals(SystemContactLinkTarget("lk1", "Alice"), result[mainnetAddress])
    }

    @Test
    fun `case differences between scanned and known address still match`() {
        val rows = listOf(row(1, "lk1", "Alice", mainnetAddress.uppercase()))
        val result = SystemContactsSyncService.matchScannedRows(rows, setOf(mainnetAddress))
        assertEquals("lk1", result[mainnetAddress]?.lookupKey)
    }

    @Test
    fun `an address matching multiple contacts keeps the longer display name`() {
        val rows = listOf(
            row(1, "lk-short", "Al", mainnetAddress),
            row(2, "lk-long", "Alice Wonderland", mainnetAddress)
        )
        val result = SystemContactsSyncService.matchScannedRows(rows, setOf(mainnetAddress))
        assertEquals("lk-long", result[mainnetAddress]?.lookupKey)
    }

    @Test
    fun `a contact tagged with our own auto-marker is never matched`() {
        val rows = listOf(
            row(1, "lk-shadow", "Shadow", "${SystemContactsSyncService.AUTO_MARKER_PREFIX}$mainnetAddress"),
            row(1, "lk-shadow", "Shadow", mainnetAddress) // the real address row on the SAME shadow contact
        )
        val result = SystemContactsSyncService.matchScannedRows(rows, setOf(mainnetAddress))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an unrelated address in the phone book is not returned`() {
        val rows = listOf(row(1, "lk1", "Bob", mainnetAddress))
        val result = SystemContactsSyncService.matchScannedRows(rows, setOf(testnetAddress))
        assertTrue(result.isEmpty())
    }
}
