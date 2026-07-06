package com.kachat.app.util

import com.google.gson.Gson

/**
 * A message that replies to an earlier one — embedded as JSON directly in the same plaintext
 * content used for plain text (no separate wire type), matching the same approach [VoiceMessage]
 * already uses. Shared by both 1:1 chats and broadcast rooms: [replyToSender] is a Kaspa address
 * in both cases (the other party's address in a 1:1 chat, or the original poster's address in a
 * broadcast), and [replyToPreview] is captured at reply-creation time (a short snippet of the
 * original message, or "🎤 Audio message" if that was a voice note) so the quote still renders
 * even if the original message itself has since been deleted/pruned or its sender hidden.
 */
data class MessageReplyContent(
    val type: String = "reply",
    val replyToId: String,
    val replyToSender: String,
    val replyToPreview: String,
    val text: String
)

object MessageReply {
    private val gson = Gson()
    private const val PREVIEW_MAX_LENGTH = 80

    fun encode(replyToId: String, replyToSender: String, replyToPreview: String, text: String): String {
        return gson.toJson(
            MessageReplyContent(
                replyToId = replyToId,
                replyToSender = replyToSender,
                replyToPreview = replyToPreview.take(PREVIEW_MAX_LENGTH),
                text = text
            )
        )
    }

    /**
     * Parses [text] as a reply if it looks like one, else returns null — a plain text message
     * never accidentally renders as a reply just because it happens to start with `{`, since this
     * also requires the explicit "reply" type marker.
     */
    fun parseOrNull(text: String?): MessageReplyContent? {
        if (text.isNullOrBlank() || text.trimStart().firstOrNull() != '{') return null
        return try {
            val parsed = gson.fromJson(text, MessageReplyContent::class.java) ?: return null
            if (parsed.type == "reply") parsed else null
        } catch (e: Exception) {
            // Same broad catch as VoiceMessage.parseOrNull — Gson's reflection-based Kotlin
            // deserialization doesn't honor non-null defaults for absent JSON keys, so a
            // non-nullable field can still come back null at runtime and NPE rather than throw
            // a JsonSyntaxException.
            null
        }
    }
}
