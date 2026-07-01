package com.kachat.app.util

import java.util.Base64

/**
 * KaChat/Kasia "ciph_msg" wire protocol — encode/decode of transaction payloads.
 *
 * Verified against the actual iOS KaChat implementation (`KaChatTransactionBuilder.swift`,
 * `ChatService+Decryption.swift`), NOT the stale MESSAGING.md doc: real tags are
 * `comm`/`handshake` (not `msg`/`hs`), colon-delimited (not pipe-delimited), and
 * encryption is ECDH+HKDF+ChaCha20-Poly1305 (see [KasiaCipher]), not AES-GCM.
 */
object MessageProtocol {

    const val PREFIX          = "ciph_msg"
    const val VERSION         = "1"
    const val TYPE_HANDSHAKE  = "handshake"
    const val TYPE_COMM       = "comm"
    const val TYPE_PAY        = "pay"

    private val HANDSHAKE_PREFIX_BYTES = "$PREFIX:$VERSION:$TYPE_HANDSHAKE:".toByteArray(Charsets.US_ASCII)

    /**
     * Builds "ciph_msg:1:comm:<alias>:<base64>" — alias is plaintext, colon-delimited
     * ahead of the base64-encoded [KasiaCipher.EncryptedMessage] bytes.
     */
    fun buildCommPayload(alias: String, encrypted: KasiaCipher.EncryptedMessage): ByteArray {
        val safeAlias = alias.replace(":", "_").take(32)
        val base64 = Base64.getEncoder().encodeToString(encrypted.toBytes())
        return "$PREFIX:$VERSION:$TYPE_COMM:$safeAlias:$base64".toByteArray(Charsets.UTF_8)
    }

    /**
     * Builds "ciph_msg:1:handshake:<raw bytes>" — the encrypted bytes are appended
     * directly (NOT base64-encoded) after the ASCII prefix, matching iOS exactly.
     * The result must be treated as opaque binary end-to-end (hex-encode it directly
     * for the transaction payload field — never round-trip it through a UTF-8 String).
     */
    fun buildHandshakePayload(encrypted: KasiaCipher.EncryptedMessage): ByteArray {
        return HANDSHAKE_PREFIX_BYTES + encrypted.toBytes()
    }

    fun isHandshakePayload(rawBytes: ByteArray): Boolean {
        if (rawBytes.size <= HANDSHAKE_PREFIX_BYTES.size) return false
        return rawBytes.copyOfRange(0, HANDSHAKE_PREFIX_BYTES.size).contentEquals(HANDSHAKE_PREFIX_BYTES)
    }

    /**
     * Returns true if [rawBytes] is a recognized ciph_msg payload of any type.
     */
    fun isKaChatPayload(rawBytes: ByteArray): Boolean {
        if (isHandshakePayload(rawBytes)) return true
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return false }
        return text.startsWith("$PREFIX:$VERSION:")
    }

    /**
     * Parses a "comm" payload, returning the plaintext alias and the still-encrypted message.
     */
    fun parseCommPayload(rawBytes: ByteArray): Pair<String, KasiaCipher.EncryptedMessage>? {
        val text = try { String(rawBytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        // ["ciph_msg", "1", "comm", alias, base64] — Kotlin limit=5 matches Swift's maxSplits:4
        val parts = text.split(":", limit = 5)
        if (parts.size != 5 || parts[0] != PREFIX || parts[1] != VERSION || parts[2] != TYPE_COMM) return null

        val alias = parts[3]
        val encryptedBytes = try {
            Base64.getDecoder().decode(parts[4])
        } catch (e: Exception) {
            return null
        }
        val message = KasiaCipher.EncryptedMessage.fromBytes(encryptedBytes) ?: return null
        return alias to message
    }

    /**
     * Parses a "handshake" payload, returning the still-encrypted message (raw bytes, no base64).
     */
    fun parseHandshakePayload(rawBytes: ByteArray): KasiaCipher.EncryptedMessage? {
        if (!isHandshakePayload(rawBytes)) return null
        val remainder = rawBytes.copyOfRange(HANDSHAKE_PREFIX_BYTES.size, rawBytes.size)
        return KasiaCipher.EncryptedMessage.fromBytes(remainder)
    }

    fun encrypt(plaintext: String, recipientXOnlyPubKey: ByteArray): KasiaCipher.EncryptedMessage =
        KasiaCipher.encrypt(plaintext, recipientXOnlyPubKey)

    fun decrypt(encrypted: KasiaCipher.EncryptedMessage, privateKey: ByteArray): String =
        KasiaCipher.decrypt(encrypted, privateKey)
}
