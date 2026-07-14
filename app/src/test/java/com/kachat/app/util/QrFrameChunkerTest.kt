package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QrFrameChunkerTest {

    @Test
    fun `payload at or under the single-frame limit is not chunked`() {
        val data = ByteArray(QrFrameChunker.SINGLE_FRAME_MAX) { it.toByte() }
        val frames = QrFrameChunker.chunk(data)
        assertEquals(1, frames.size)
        assertArrayEquals(data, frames[0])
    }

    @Test
    fun `payload over the single-frame limit is split into a multi-frame header format`() {
        val data = ByteArray(300) { it.toByte() }
        val frames = QrFrameChunker.chunk(data)
        assertTrue(frames.size > 1)
        frames.forEachIndexed { index, frame ->
            assertEquals(index, frame[0].toInt() and 0xFF)
            assertEquals(frames.size, frame[1].toInt() and 0xFF)
        }
    }

    @Test
    fun `chunk then reassemble round trips arbitrary payload sizes`() {
        // All these sizes exceed SINGLE_FRAME_MAX, so every one gets chunked into a real
        // multi-frame stream — isComplete must never match an individual fragment (any real
        // caller's magic-byte check, e.g. KsptCodec.looksLikeKspt, already guarantees this; a
        // fragment's raw bytes essentially never happen to start with a real payload's magic).
        for (size in listOf(135, 300, 1000, 5000)) {
            val data = Random(size).nextBytes(size)
            val frames = QrFrameChunker.chunk(data)
            val acc = QrFrameChunker.Accumulator(isComplete = { false })
            var result: ByteArray? = null
            for (frame in frames) {
                result = acc.addFrame(frame)
            }
            assertArrayEquals("size=$size", data, result)
        }
    }

    @Test
    fun `reassembles frames scanned out of order`() {
        val data = Random(42).nextBytes(500)
        val frames = QrFrameChunker.chunk(data).shuffled(Random(7))
        val acc = QrFrameChunker.Accumulator(isComplete = { false })
        var result: ByteArray? = null
        for (frame in frames) {
            result = acc.addFrame(frame)
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `duplicate frames don't break reassembly`() {
        val data = Random(1).nextBytes(400)
        val frames = QrFrameChunker.chunk(data)
        val acc = QrFrameChunker.Accumulator(isComplete = { false })
        var result: ByteArray? = null
        for (frame in frames) {
            acc.addFrame(frame) // scan once
            result = acc.addFrame(frame) // "rescan" the same frame mid-animation
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `single complete payload short-circuits via isComplete`() {
        val payload = "KSPT-complete-payload".toByteArray()
        val acc = QrFrameChunker.Accumulator(isComplete = { it.size >= 4 && it.copyOfRange(0, 4).contentEquals("KSPT".toByteArray()) })
        assertArrayEquals(payload, acc.addFrame(payload))
    }

    @Test
    fun `incomplete accumulation returns null and reports progress`() {
        val data = Random(3).nextBytes(500)
        val frames = QrFrameChunker.chunk(data)
        assertTrue(frames.size > 2)
        val acc = QrFrameChunker.Accumulator(isComplete = { false })
        assertNull(acc.addFrame(frames[0]))
        assertEquals(1 to frames.size, acc.progress)
    }

    @Test
    fun `frames from a different total-count stream reset instead of mixing`() {
        val dataA = Random(10).nextBytes(500)
        val dataB = Random(11).nextBytes(300)
        val framesA = QrFrameChunker.chunk(dataA)
        val framesB = QrFrameChunker.chunk(dataB)

        val acc = QrFrameChunker.Accumulator(isComplete = { false })
        acc.addFrame(framesA[0]) // start stream A
        // Now a fresh scan picks up stream B instead (different totalFrames) — should reset, not corrupt.
        for (frame in framesB) {
            val result = acc.addFrame(frame)
            if (frame === framesB.last()) {
                assertArrayEquals(dataB, result)
            }
        }
    }
}
