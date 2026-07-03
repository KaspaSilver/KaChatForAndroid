package com.kachat.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupRetentionTest {

    @Test
    fun `forever has no day cutoff`() {
        assertNull(BackupRetention.FOREVER.days)
    }

    @Test
    fun `30 and 90 day options carry their exact day count`() {
        assertEquals(30, BackupRetention.DAYS_30.days)
        assertEquals(90, BackupRetention.DAYS_90.days)
    }

    @Test
    fun `fromName round-trips every enum value by its own name`() {
        for (value in BackupRetention.entries) {
            assertEquals(value, BackupRetention.fromName(value.name))
        }
    }

    @Test
    fun `fromName falls back to forever for null or unrecognized input`() {
        assertEquals(BackupRetention.FOREVER, BackupRetention.fromName(null))
        assertEquals(BackupRetention.FOREVER, BackupRetention.fromName("bogus"))
    }

    @Test
    fun `forever never has a cutoff, regardless of the current time`() {
        assertNull(BackupRetention.FOREVER.cutoffMillis(System.currentTimeMillis()))
    }

    @Test
    fun `30 days cutoff is exactly 30 times 24 hours before now`() {
        val now = 1_800_000_000_000L
        val expected = now - 30L * 24 * 60 * 60 * 1000
        assertEquals(expected, BackupRetention.DAYS_30.cutoffMillis(now))
    }

    @Test
    fun `90 days cutoff is exactly 90 times 24 hours before now`() {
        val now = 1_800_000_000_000L
        val expected = now - 90L * 24 * 60 * 60 * 1000
        assertEquals(expected, BackupRetention.DAYS_90.cutoffMillis(now))
    }

    @Test
    fun `30 day cutoff is more recent than 90 day cutoff for the same now`() {
        val now = System.currentTimeMillis()
        val cutoff30 = BackupRetention.DAYS_30.cutoffMillis(now)!!
        val cutoff90 = BackupRetention.DAYS_90.cutoffMillis(now)!!
        assertEquals(true, cutoff30 > cutoff90)
    }
}
