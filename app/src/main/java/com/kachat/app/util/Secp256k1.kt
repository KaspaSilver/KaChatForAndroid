package com.kachat.app.util

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECCurve
import org.bouncycastle.math.ec.ECPoint

/**
 * Shared secp256k1 curve parameters, reused by both [Schnorr] (BIP-340 signing)
 * and [KasiaCipher] (ECDH) so the curve object is only instantiated once.
 */
object Secp256k1 {
    val PARAMS: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    val CURVE: ECCurve = PARAMS.curve
    val G: ECPoint = PARAMS.g
    val N = PARAMS.n
}
