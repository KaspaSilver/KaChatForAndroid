package com.kachat.app.util

/**
 * KaChat message protocol constants and payload helpers.
 *
 * The iOS app embeds encrypted payloads in Kaspa transaction payloads
 * using the Kasia protocol format:
 *
 *   ciph_msg:1:hs:<base64_payload>   — handshake
 *   ciph_msg:1:msg:<base64_payload>  — encrypted message
 *   ciph_msg:1:pay:<base64_payload>  — payment with encrypted memo
 *
 * See MESSAGING.md in the iOS repo for full protocol details.
 * Phase 4 will implement full encode/decode + encryption.
 */
object MessageProtocol {

    const val PREFIX   = "ciph_msg"
    const val VERSION  = "1"
    const val TYPE_HS  = "hs"
    const val TYPE_MSG = "msg"
    const val TYPE_PAY = "pay"

    /**
     * Returns true if a transaction payload contains a KaChat message.
     */
    fun isKaChatPayload(hexPayload: String): Boolean {
        return try {
            val decoded = hexPayload.hexToUtf8()
            decoded.startsWith("$PREFIX:$VERSION:")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parses the type field from a raw ciph_msg payload string.
     * Returns null if the payload is not a valid KaChat message.
     */
    fun parseType(payload: String): String? {
        val parts = payload.split(":")
        if (parts.size < 4) return null
        if (parts[0] != PREFIX || parts[1] != VERSION) return null
        return parts[2]
    }

    /**
     * Extracts the base64-encoded encrypted data from a ciph_msg payload.
     */
    fun extractData(payload: String): String? {
        val parts = payload.split(":", limit = 4)
        return if (parts.size == 4) parts[3] else null
    }

    /**
     * Builds a raw ciph_msg payload string ready to embed in a transaction.
     * Phase 4 will call this after encrypting the message content.
     */
    fun buildPayload(type: String, encryptedBase64: String): String {
        return "$PREFIX:$VERSION:$type:$encryptedBase64"
    }

    // -------------------------------------------------------------------------
    // Phase 4 stubs
    // -------------------------------------------------------------------------

    /**
     * Encrypts a plaintext message using the shared ECDH secret.
     * Returns a base64-encoded ciphertext ready for buildPayload().
     */
    fun encrypt(plaintext: String, sharedSecret: ByteArray): String {
        TODO("Phase 4: AES-GCM encryption")
    }

    /**
     * Decrypts a base64-encoded ciphertext using the shared ECDH secret.
     */
    fun decrypt(encryptedBase64: String, sharedSecret: ByteArray): String {
        TODO("Phase 4: AES-GCM decryption")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun String.hexToUtf8(): String {
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }
}
