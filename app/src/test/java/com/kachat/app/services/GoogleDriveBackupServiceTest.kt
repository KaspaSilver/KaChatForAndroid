package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GoogleDriveBackupServiceTest {

    @Test
    fun `each account gets its own backup filename, not a shared one`() {
        val addressA = "kaspa:qyp4nvaq3pdq7609z09fvdgwtc9c7rg07fuw5zgeee7xpr085de59eseqfcmynn"
        val addressB = "kaspa:qqgngwf58y864jv5f2azh9wcp2kx7r4a8tkhrgx7wwv40zpmq6e2y6lx380ws"

        assertNotEquals(
            GoogleDriveBackupService.backupFileNameFor(addressA),
            GoogleDriveBackupService.backupFileNameFor(addressB)
        )
    }

    @Test
    fun `the same address always maps to the same filename`() {
        val address = "kaspa:qyp4nvaq3pdq7609z09fvdgwtc9c7rg07fuw5zgeee7xpr085de59eseqfcmynn"
        assertEquals(
            GoogleDriveBackupService.backupFileNameFor(address),
            GoogleDriveBackupService.backupFileNameFor(address)
        )
    }

    @Test
    fun `filename has no colons, which would otherwise complicate the drive query string`() {
        val fileName = GoogleDriveBackupService.backupFileNameFor("kaspa:qqgngwf58y864jv5f2azh9wcp2kx7r4a8tkhrgx7wwv40zpmq6e2y6lx380ws")
        assertEquals(false, fileName.contains(":"))
    }

    @Test
    fun `filename ends with json`() {
        val fileName = GoogleDriveBackupService.backupFileNameFor("kaspa:qqgngwf58y864jv5f2azh9wcp2kx7r4a8tkhrgx7wwv40zpmq6e2y6lx380ws")
        assertEquals(true, fileName.endsWith(".json"))
    }
}
