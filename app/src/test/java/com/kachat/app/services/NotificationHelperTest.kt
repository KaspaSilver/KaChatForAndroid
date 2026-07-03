package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun `sound and vibration both on selects the default channel`() {
        assertEquals("kachat_messages_sound_vibrate", NotificationHelper.channelFor(soundEnabled = true, vibrationEnabled = true))
    }

    @Test
    fun `sound only selects the sound-only channel`() {
        assertEquals("kachat_messages_sound_only", NotificationHelper.channelFor(soundEnabled = true, vibrationEnabled = false))
    }

    @Test
    fun `vibration only selects the vibrate-only channel`() {
        assertEquals("kachat_messages_vibrate_only", NotificationHelper.channelFor(soundEnabled = false, vibrationEnabled = true))
    }

    @Test
    fun `both off selects the silent channel`() {
        assertEquals("kachat_messages_silent", NotificationHelper.channelFor(soundEnabled = false, vibrationEnabled = false))
    }
}
