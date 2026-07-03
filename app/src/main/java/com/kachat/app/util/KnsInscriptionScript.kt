package com.kachat.app.util

import org.bouncycastle.crypto.digests.Blake2bDigest

/**
 * Builds the KNS inscription redeem script and its commit address — the exact commit/reveal
 * envelope format used by KNS domain registration and profile updates (verified byte-for-byte
 * against the iOS reference, `KaChat/Services/KaChatTransactionBuilder.swift:1049-1086`).
 */
object KnsInscriptionScript {

    /**
     * Redeem script layout:
     *   0x20 <32-byte x-only pubkey>   push pubkey
     *   0xAC                           OP_CHECKSIG
     *   0x00                           OP_FALSE
     *   0x63                           OP_IF
     *     <canonical push title>
     *     0x00                         OP_0 separator
     *     <canonical push payloadJson>
     *   0x68                           OP_ENDIF
     */
    fun buildRedeemScript(xOnlyPubKey: ByteArray, title: String, payloadJson: ByteArray): ByteArray {
        require(xOnlyPubKey.size == 32) { "x-only pubkey must be 32 bytes" }
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        require(titleBytes.isNotEmpty()) { "KNS inscription title is empty" }
        require(payloadJson.size <= 520) { "KNS inscription payload exceeds 520-byte script element limit" }

        val out = java.io.ByteArrayOutputStream()
        out.write(0x20)
        out.write(xOnlyPubKey)
        out.write(0xAC) // OP_CHECKSIG
        out.write(0x00) // OP_FALSE
        out.write(0x63) // OP_IF
        out.write(canonicalPush(titleBytes))
        out.write(0x00) // OP_0 separator
        out.write(canonicalPush(payloadJson))
        out.write(0x68) // OP_ENDIF
        return out.toByteArray()
    }

    /** Commit address: the redeem script's Blake2b-256 hash, encoded as a Kaspa P2SH address (version 8). */
    fun commitAddress(redeemScript: ByteArray, hrp: String): String {
        val digest = Blake2bDigest(null, 32, null, null)
        digest.update(redeemScript, 0, redeemScript.size)
        val hash = ByteArray(32)
        digest.doFinal(hash, 0)
        return KaspaAddress.encode(hrp, 8.toByte(), hash)
    }

    /**
     * Standard Bitcoin-Script canonical push-data encoding — matches iOS's
     * `buildCanonicalPushData` exactly (builder:1149-1188).
     */
    internal fun canonicalPush(data: ByteArray): ByteArray {
        require(data.size <= 520) { "Script element exceeds 520-byte limit" }
        val length = data.size
        return when {
            length == 0 -> byteArrayOf(0x00)
            length <= 75 -> byteArrayOf(length.toByte()) + data
            length <= 0xff -> byteArrayOf(0x4c, length.toByte()) + data
            length <= 0xffff -> byteArrayOf(0x4d, (length and 0xff).toByte(), ((length shr 8) and 0xff).toByte()) + data
            else -> byteArrayOf(
                0x4e,
                (length and 0xff).toByte(),
                ((length shr 8) and 0xff).toByte(),
                ((length shr 16) and 0xff).toByte(),
                ((length shr 24) and 0xff).toByte()
            ) + data
        }
    }

    /** Byte length [canonicalPush] would produce for data of this length — for fee/mass estimation before building the actual bytes. */
    internal fun canonicalPushSize(dataLength: Int): Int = when {
        dataLength <= 0 -> 1
        dataLength <= 75 -> 1 + dataLength
        dataLength <= 0xff -> 2 + dataLength
        dataLength <= 0xffff -> 3 + dataLength
        else -> 5 + dataLength
    }
}
