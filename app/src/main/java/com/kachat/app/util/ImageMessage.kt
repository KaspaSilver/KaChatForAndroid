package com.kachat.app.util

import com.google.gson.Gson

/**
 * A chat photo's on-chain representation — reuses [VoiceMessageContent] as-is rather than a
 * duplicate data class, since its shape (`type`, `name`, `size`, `mimeType`, `content` data-URI)
 * is already a generic media envelope with nothing audio-specific except the default `mimeType`.
 * Mirrors [VoiceMessage] exactly, just filtering on an "image" mimeType prefix instead of "audio" —
 * same embed-directly-in-the-encrypted-comm-payload approach, no upload endpoint, no new wire type.
 */
object ImageMessage {
    private val gson = Gson()

    fun encode(fileName: String, sizeBytes: Long, base64Image: String, mimeType: String = "image/jpeg"): String {
        return gson.toJson(
            VoiceMessageContent(
                name = fileName,
                size = sizeBytes,
                mimeType = mimeType,
                content = "data:$mimeType;base64,$base64Image"
            )
        )
    }

    /** Parses [text] as an image message if it looks like one, else null — same broad-catch shape as [VoiceMessage.parseOrNull]. */
    fun parseOrNull(text: String?): VoiceMessageContent? {
        if (text.isNullOrBlank() || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, VoiceMessageContent::class.java) ?: return null
            if (parsed.mimeType.startsWith("image/") && parsed.content.startsWith("data:")) parsed else null
        } catch (e: Exception) {
            // Same reflection/non-null-default caveat as VoiceMessage.parseOrNull. Logs the real
            // exception + a shape summary (never the full payload — could be tens of KB) so a
            // real-world parse failure (e.g. a truncated/corrupted message from another client)
            // is actually diagnosable instead of just falling back to a raw-text bubble with no
            // trace of why. In particular, whether the tail looks like valid JSON (ends `"}`) is
            // the fastest way to tell a truncated payload from a genuine structural mismatch.
            // try/catch around the log call itself: android.util.Log isn't mocked in plain JUnit
            // tests (throws instead of no-op'ing), and this same catch block above is legitimately
            // hit for perfectly ordinary non-file JSON text (e.g. a user just typing `{"a":"b"}`),
            // so it has to stay harmless there too, not just in tests.
            try {
                android.util.Log.w(
                    "ImageMessage",
                    "parseOrNull failed: ${e.javaClass.simpleName}: ${e.message} | len=${text.length} tail=${text.takeLast(20)}"
                )
            } catch (loggingFailure: Throwable) {
                // Ignored — see comment above.
            }
            null
        }
    }

    /** The raw base64 image payload, stripped of its "data:<mime>;base64," prefix. */
    fun base64Payload(imageContent: VoiceMessageContent): String = VoiceMessage.base64Payload(imageContent)
}
