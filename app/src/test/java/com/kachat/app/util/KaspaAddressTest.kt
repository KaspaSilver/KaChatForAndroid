package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class KaspaAddressTest {

    private fun randomAddress(): String {
        val pubKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return KaspaAddress.encode("kaspa", 0x00, pubKeyBytes)
    }

    @Test
    fun `addressFromScriptPublicKey inverts getScriptPublicKey for a schnorr address`() {
        val address = randomAddress()
        val scriptHex = KaspaAddress.getScriptPublicKey(address)
        assertEquals(address, KaspaAddress.addressFromScriptPublicKey(scriptHex))
    }

    @Test
    fun `addressFromScriptPublicKey rejects a non-schnorr or malformed script`() {
        assertNull(KaspaAddress.addressFromScriptPublicKey("aa14deadbeefdeadbeefdeadbeefdeadbeefdead87"))
        assertNull(KaspaAddress.addressFromScriptPublicKey("not hex"))
        assertNull(KaspaAddress.addressFromScriptPublicKey(""))
    }
}
