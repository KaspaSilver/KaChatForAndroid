package com.kachat.app.util

import java.math.BigInteger
import java.security.MessageDigest
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint

/**
 * BIP-340 Schnorr signatures on secp256k1.
 */
object Schnorr {
    private val CURVE_PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val G = CURVE_PARAMS.g
    private val N = CURVE_PARAMS.n

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
