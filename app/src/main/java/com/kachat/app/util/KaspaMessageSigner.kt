package com.kachat.app.util

import org.bouncycastle.crypto.digests.Blake2bDigest
import java.security.MessageDigest

/**
 * Signs an arbitrary short message (not a transaction) with the wallet's key — used only to
 * prove wallet ownership to an off-chain API (KNS's image-upload auth), never for anything
 * that moves funds. Three modes exist because different KNS backend deployments have
 * historically expected different message-hashing conventions; callers try them in order until
 * one is accepted. Verified against the iOS reference's `WalletManager.signArbitraryMessage`
 * (`WalletManager.swift:398-444`).
 */
object KaspaMessageSigner {
    enum class SigningMode { KASPA_PERSONAL_MESSAGE, RAW_UTF8, SHA256_DIGEST }

    private val PERSONAL_MESSAGE_KEY = "PersonalMessageSigningHash".toByteArray(Charsets.US_ASCII)

    /** Returns a lowercase hex-encoded 64-byte Schnorr signature. */
    fun sign(message: String, privateKey: ByteArray, mode: SigningMode): String {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val toSign = when (mode) {
            SigningMode.KASPA_PERSONAL_MESSAGE -> keyedBlake2b(messageBytes)
            SigningMode.RAW_UTF8 -> messageBytes
            SigningMode.SHA256_DIGEST -> MessageDigest.getInstance("SHA-256").digest(messageBytes)
        }
        return Schnorr.sign(toSign, privateKey).joinToString("") { "%02x".format(it) }
    }

    private fun keyedBlake2b(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(PERSONAL_MESSAGE_KEY, 32, null, null)
        digest.update(data, 0, data.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }
}
