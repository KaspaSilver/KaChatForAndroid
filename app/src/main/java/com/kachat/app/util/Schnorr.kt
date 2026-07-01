package com.kachat.app.util

import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 Schnorr signatures on secp256k1.
 */
object Schnorr {
    private val G = Secp256k1.G
    private val N = Secp256k1.N

    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        val d0 = BigInteger(1, privateKey)
        if (d0 == BigInteger.ZERO || d0 >= N) throw IllegalArgumentException("Invalid private key")

        val P = G.multiply(d0).normalize()
        val d = if (P.affineYCoord.toBigInteger().mod(BigInteger.valueOf(2)) == BigInteger.ZERO) d0 else N.subtract(d0)

        // nonce = sha256(d | message)
        val digest = MessageDigest.getInstance("SHA-256")
        val t = d.toByteArray().padStart(32, 0).plus(message)
        val k0 = BigInteger(1, digest.digest(t)).mod(N)
        if (k0 == BigInteger.ZERO) throw IllegalStateException("k is zero")

        val R = G.multiply(k0).normalize()
        val k = if (R.affineYCoord.toBigInteger().mod(BigInteger.valueOf(2)) == BigInteger.ZERO) k0 else N.subtract(k0)

        val r = R.affineXCoord.toBigInteger().toByteArray().takeLast(32).toByteArray().padStart(32, 0)
        val p = P.affineXCoord.toBigInteger().toByteArray().takeLast(32).toByteArray().padStart(32, 0)
        val e = calculateE(r, p, message)

        val s = k.add(e.multiply(d)).mod(N)

        return r + s.toByteArray().takeLast(32).toByteArray().padStart(32, 0)
    }

    /** Returns the 32-byte x-only public key (BIP-340 convention: even-Y point) for a private key. */
    fun publicKeyXOnly(privateKey: ByteArray): ByteArray {
        val d0 = BigInteger(1, privateKey)
        val P = G.multiply(d0).normalize()
        return P.affineXCoord.toBigInteger().toByteArray().takeLast(32).toByteArray().padStart(32, 0)
    }

    /**
     * BIP-340 verification. Simplified for internal use (test fixtures, self-consistency
     * checks) — does not enforce r < field-prime, only r/s < curve order, since inputs
     * here are always our own freshly-generated signatures rather than untrusted wire data.
     */
    fun verify(message: ByteArray, signature: ByteArray, pubKeyXOnly: ByteArray): Boolean {
        if (signature.size != 64 || pubKeyXOnly.size != 32) return false
        return try {
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (s >= N) return false

            val point = Secp256k1.CURVE.decodePoint(byteArrayOf(0x02) + pubKeyXOnly)
            val e = calculateE(signature.copyOfRange(0, 32), pubKeyXOnly, message)

            val R = G.multiply(s).subtract(point.multiply(e)).normalize()
            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().mod(BigInteger.valueOf(2)) != BigInteger.ZERO) return false
            R.affineXCoord.toBigInteger() == r
        } catch (e: Exception) {
            false
        }
    }

    private fun calculateE(r: ByteArray, p: ByteArray, m: ByteArray): BigInteger {
        val digest = MessageDigest.getInstance("SHA-256")
        val tag = "BIP0340/challenge".toByteArray()
        val tagHash = digest.digest(tag)
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(r)
        digest.update(p)
        digest.update(m)
        val hash = digest.digest()
        return BigInteger(1, hash).mod(N)
    }

    private fun ByteArray.padStart(length: Int, pad: Byte): ByteArray {
        if (this.size >= length) return this.takeLast(length).toByteArray()
        val result = ByteArray(length)
        result.fill(pad)
        System.arraycopy(this, 0, result, length - this.size, this.size)
        return result
    }
}
