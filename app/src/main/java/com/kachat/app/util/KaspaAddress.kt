package com.kachat.app.util

/**
 * Kaspa Bech32 (CashAddr) implementation.
 */
object KaspaAddress {
    private const val ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /**
     * Encodes a Kaspa address.
     * @param prefix The network prefix (e.g., "kaspa", "kaspatest")
     * @param version The version byte (0x00 for Schnorr, 0x01 for ECDSA, 0x08 for P2SH)
     * @param payload The raw public key or script hash bytes
     */
    fun encode(prefix: String, version: Byte, payload: ByteArray): String {
        val versionAndPayload = byteArrayOf(version) + payload
        val data5Bit = convertBits(versionAndPayload, 8, 5, true)
        val checksum = calculateChecksum(prefix, data5Bit)

        val combined = data5Bit + checksum
        val encodedData = combined.map { ALPHABET[it.toInt()].toString() }.joinToString("")

        return "$prefix:$encodedData"
    }

    /**
     * Basic validation of a Kaspa address string.
     */
    fun isValid(address: String): Boolean {
        return try {
            decode(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decodes a Kaspa address.
     * @return Pair(version, payload)
     */
    fun decode(address: String): Pair<Byte, ByteArray> {
        val parts = address.split(":")
        if (parts.size != 2) throw IllegalArgumentException("Invalid address format")
        
        val prefix = parts[0]
        val encodedData = parts[1]
        
        val data5Bit = encodedData.map { char ->
            val index = ALPHABET.indexOf(char)
            if (index == -1) throw IllegalArgumentException("Invalid character: $char")
            index.toByte()
        }.toByteArray()
        
        if (data5Bit.size < 8) throw IllegalArgumentException("Address too short")
        
        val checksum = data5Bit.takeLast(8).toByteArray()
        val dataWithoutChecksum = data5Bit.dropLast(8).toByteArray()
        
        val calculatedChecksum = calculateChecksum(prefix, dataWithoutChecksum)
        if (!checksum.contentEquals(calculatedChecksum)) {
            throw IllegalArgumentException("Checksum mismatch")
        }
        
        val versionAndPayload = convertBits(dataWithoutChecksum, 5, 8, false)
        if (versionAndPayload.isEmpty()) throw IllegalArgumentException("Empty payload")
        
        return versionAndPayload[0] to versionAndPayload.drop(1).toByteArray()
    }

    /**
     * Returns the scriptPublicKey hex for a given address.
     */
    fun getScriptPublicKey(address: String): String {
        val (version, payload) = decode(address)
        // Kaspa script: <payload_length> <payload> <opcode_checks_sig>
        // For version 0 (Schnorr): 20 <payload_hex> ac
        // For version 1 (ECDSA): 21 <payload_hex> ab
        
        val payloadHex = payload.joinToString("") { "%02x".format(it) }
        
        val prefix = when (version.toInt()) {
            0 -> "20"
            1 -> "21"
            8 -> "aa" // P2SH
            else -> throw IllegalArgumentException("Unsupported address version: $version")
        }
        val suffix = when (version.toInt()) {
            0 -> "ac"
            1 -> "ab"
            8 -> "87"
            else -> ""
        }
        return prefix + payloadHex + suffix
    }

    private fun calculateChecksum(prefix: String, data: ByteArray): ByteArray {
        val expandedPrefix = expandPrefix(prefix)
        val polyInput = expandedPrefix + byteArrayOf(0) + data + ByteArray(8)
        val poly = polyMod(polyInput)

        val checksum = ByteArray(8)
        for (i in 0 until 8) {
            checksum[i] = ((poly shr (5 * (7 - i))) and 0x1F).toByte()
        }
        return checksum
    }

    private fun polyMod(data: ByteArray): Long {
        var c: Long = 1
        for (v in data) {
            val c0 = (c shr 35).toInt()
            c = ((c and 0x07FFFFFFFFL) shl 5) xor v.toLong()

            if (c0 and 0x01 != 0) c = c xor 0x98f2bc8e61L
            if (c0 and 0x02 != 0) c = c xor 0x79b76d99e2L
            if (c0 and 0x04 != 0) c = c xor 0xf33e5fb3c4L
            if (c0 and 0x08 != 0) c = c xor 0xae2eabe2a8L
            if (c0 and 0x10 != 0) c = c xor 0x1e4f43e470L
        }
        return c xor 1
    }

    private fun expandPrefix(prefix: String): ByteArray {
        return ByteArray(prefix.length) { (prefix[it].code and 0x1F).toByte() }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            val b = value.toInt() and 0xff
            acc = (acc shl from) or b
            bits += from
            while (bits >= to) {
                bits -= to
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) result.add(((acc shl (to - bits)) and maxv).toByte())
        }
        return result.toByteArray()
    }
}
