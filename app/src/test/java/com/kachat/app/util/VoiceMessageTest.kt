package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMessageTest {

    @Test
    fun `encode produces a data URI with the given mime type and base64 payload`() {
        val json = VoiceMessage.encode(fileName = "voice.webm", sizeBytes = 42, base64Audio = "QUJD")
        val parsed = VoiceMessage.parseOrNull(json)
        assertNotNull(parsed)
        assertEquals("voice.webm", parsed!!.name)
        assertEquals(42L, parsed.size)
        assertEquals("audio/webm", parsed.mimeType)
        assertEquals("data:audio/webm;base64,QUJD", parsed.content)
    }

    @Test
    fun `parseOrNull round-trips what encode produced`() {
        val json = VoiceMessage.encode(fileName = "voice.webm", sizeBytes = 7500, base64Audio = "ZGVhZGJlZWY=")
        val parsed = VoiceMessage.parseOrNull(json)
        assertEquals("ZGVhZGJlZWY=", VoiceMessage.base64Payload(parsed!!))
    }

    @Test
    fun `a plain text message is never mistaken for a voice message`() {
        assertNull(VoiceMessage.parseOrNull("hello there"))
        assertNull(VoiceMessage.parseOrNull(""))
        assertNull(VoiceMessage.parseOrNull(null))
    }

    @Test
    fun `arbitrary JSON that is not a voice message is rejected`() {
        assertNull(VoiceMessage.parseOrNull("""{"foo":"bar"}"""))
    }

    @Test
    fun `json missing an audio mime type is rejected even if otherwise shaped like a file`() {
        val json = """{"type":"file","name":"doc.pdf","size":100,"mimeType":"application/pdf","content":"data:application/pdf;base64,QQ=="}"""
        assertNull(VoiceMessage.parseOrNull(json))
    }

    @Test
    fun `json missing a data URI content is rejected`() {
        val json = """{"type":"file","name":"voice.webm","size":100,"mimeType":"audio/webm","content":"not-a-data-uri"}"""
        assertNull(VoiceMessage.parseOrNull(json))
    }

    @Test
    fun `malformed json does not throw`() {
        assertNull(VoiceMessage.parseOrNull("{not valid json"))
    }

    @Test
    fun `base64Payload strips the data URI prefix`() {
        val content = VoiceMessageContent(name = "voice.webm", size = 3, content = "data:audio/webm;base64,QUJD")
        assertEquals("QUJD", VoiceMessage.base64Payload(content))
    }

    @Test
    fun `base64Payload is empty when there is no comma to split on`() {
        val content = VoiceMessageContent(name = "voice.webm", size = 3, content = "no-comma-here")
        assertEquals("", VoiceMessage.base64Payload(content))
    }

    @Test
    fun `formatDuration renders mm-ss`() {
        assertEquals("0:00", VoiceMessage.formatDuration(0))
        assertEquals("0:07", VoiceMessage.formatDuration(7_000))
        assertEquals("0:09", VoiceMessage.formatDuration(9_800))
        assertEquals("1:03", VoiceMessage.formatDuration(63_000))
    }

    @Test
    fun `formatDuration never goes negative for a not-yet-ready player`() {
        assertEquals("0:00", VoiceMessage.formatDuration(-1))
    }

    @Test
    fun `estimatedWirePayloadSize grows as recording time elapses`() {
        val at1s = VoiceMessage.estimatedWirePayloadSize(1_000)
        val at5s = VoiceMessage.estimatedWirePayloadSize(5_000)
        val at10s = VoiceMessage.estimatedWirePayloadSize(10_000)

        assertTrue(at1s < at5s)
        assertTrue(at5s < at10s)
    }

    @Test
    fun `estimatedWirePayloadSize is calibrated against a real sent voice message near the 10s cap`() {
        // A real ~10s recording produced a 28,729-byte final wire payload — the estimate
        // should land in the same ballpark (it's a live preview, not required to be exact).
        val estimate = VoiceMessage.estimatedWirePayloadSize(10_000)
        assertTrue(estimate in 20_000..35_000)
    }

    @Test
    fun `estimatedWirePayloadSize is never zero even for a just-started recording`() {
        assertTrue(VoiceMessage.estimatedWirePayloadSize(0) > 0)
    }
}
