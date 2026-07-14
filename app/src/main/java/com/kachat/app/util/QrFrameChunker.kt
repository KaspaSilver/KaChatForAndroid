package com.kachat.app.util

import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.min

/**
 * Multi-frame (animated) QR chunking, matching KasSigner's own raw-byte scheme exactly —
 * verified against `kassee/src/qr.rs` and the device firmware's `process_multiframe`/
 * `is_multiframe` (`bootloader/src/handlers/camera_loop.rs`). Fully symmetric: the same
 * chunker/dechunker works whether KaChat is sending an unsigned KSPT to the device or receiving
 * the signed one back.
 *
 * ```
 * <=134 bytes total: no wrapper at all — the raw payload is a single QR frame.
 * otherwise: [frameNum(1)] [totalFrames(1)] [fragLen(1)] [fragment(fragLen bytes)], balanced
 *            across frames (not simply 106 bytes each except possibly the last), capped at
 *            64 frames, each frame zero-padded to a minimum of 20 bytes for reliable scanning.
 * ```
 */
object QrFrameChunker {
    const val SINGLE_FRAME_MAX = 134
    const val MAX_FRAME_DATA = 106
    const val MAX_FRAMES = 64
    private const val MIN_FRAME_SIZE = 20
    private const val HEADER_SIZE = 3

    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.size <= SINGLE_FRAME_MAX) return listOf(data)

        val totalFrames = ceil(data.size.toDouble() / MAX_FRAME_DATA).toInt()
        require(totalFrames <= MAX_FRAMES) { "Payload too large to chunk into QR frames (${data.size} bytes)" }
        val perFrame = ceil(data.size.toDouble() / totalFrames).toInt()

        val frames = mutableListOf<ByteArray>()
        var offset = 0
        for (frameNum in 0 until totalFrames) {
            val end = min(offset + perFrame, data.size)
            val fragment = data.copyOfRange(offset, end)
            require(fragment.size <= 255) { "Chunk fragment overflowed a single byte length field" }
            val frame = ByteArray(HEADER_SIZE + fragment.size)
            frame[0] = frameNum.toByte()
            frame[1] = totalFrames.toByte()
            frame[2] = fragment.size.toByte()
            fragment.copyInto(frame, HEADER_SIZE)
            frames.add(if (frame.size < MIN_FRAME_SIZE) frame.copyOf(MIN_FRAME_SIZE) else frame)
            offset = end
        }
        return frames
    }

    /**
     * Reassembles frames scanned in any order (or with duplicates/retries) as the camera happens
     * to catch them mid-animation. [isComplete] identifies a payload that arrived as a single,
     * unwrapped QR frame (no multi-frame header) — pass a magic-byte check like
     * [KsptCodec.looksLikeKspt] for whatever payload type is being scanned.
     */
    class Accumulator(private val isComplete: (ByteArray) -> Boolean) {
        private var totalFrames: Int? = null
        private val received = mutableMapOf<Int, ByteArray>()

        /** Feed one scanned frame's raw bytes. Returns the reassembled payload once every frame has arrived, else null. */
        fun addFrame(bytes: ByteArray): ByteArray? {
            if (isComplete(bytes)) return bytes
            if (bytes.size < HEADER_SIZE) return null

            val frameNum = bytes[0].toInt() and 0xFF
            val total = bytes[1].toInt() and 0xFF
            val fragLen = bytes[2].toInt() and 0xFF
            if (total !in 2..MAX_FRAMES || frameNum >= total || fragLen <= 0) return null
            if (bytes.size < HEADER_SIZE + fragLen) return null

            // A frame from a different scan (mismatched total) — reset and start over rather
            // than silently mixing two different transactions' fragments together.
            if (totalFrames != null && totalFrames != total) reset()
            totalFrames = total
            received[frameNum] = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + fragLen)

            val expected = totalFrames ?: return null
            if (received.size < expected) return null

            val out = ByteArrayOutputStream()
            for (i in 0 until expected) {
                out.write(received[i] ?: return null)
            }
            return out.toByteArray()
        }

        /** (received, total) — null until the first valid frame arrives. */
        val progress: Pair<Int, Int>? get() = totalFrames?.let { received.size to it }

        /** Which frame indices have arrived so far — for a per-slot progress indicator (frames can arrive out of order). */
        val receivedFrameIndices: Set<Int> get() = received.keys

        fun reset() {
            totalFrames = null
            received.clear()
        }
    }
}
