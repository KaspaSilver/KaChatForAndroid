package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnsInscriptionScriptTest {

    // --- canonicalPush -------------------------------------------------------------

    @Test
    fun `empty data pushes OP_0`() {
        assertArrayEquals(byteArrayOf(0x00), KnsInscriptionScript.canonicalPush(ByteArray(0)))
    }

    @Test
    fun `short data uses a direct length-byte push`() {
        val data = byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(0x03, 1, 2, 3), KnsInscriptionScript.canonicalPush(data))
    }

    @Test
    fun `75-byte data still uses a direct length-byte push`() {
        val data = ByteArray(75) { it.toByte() }
        val pushed = KnsInscriptionScript.canonicalPush(data)
        assertEquals(0x4B, pushed[0].toInt() and 0xff) // 75 decimal == 0x4B
        assertEquals(76, pushed.size) // 1 length byte + 75 data bytes
    }

    @Test
    fun `76-byte data switches to OP_PUSHDATA1`() {
        val data = ByteArray(76) { it.toByte() }
        val pushed = KnsInscriptionScript.canonicalPush(data)
        assertEquals(0x4c, pushed[0].toInt() and 0xff)
        assertEquals(76, pushed[1].toInt() and 0xff)
        assertEquals(2 + 76, pushed.size)
    }

    @Test
    fun `255-byte data is the top of the OP_PUSHDATA1 range`() {
        val data = ByteArray(255) { it.toByte() }
        val pushed = KnsInscriptionScript.canonicalPush(data)
        assertEquals(0x4c, pushed[0].toInt() and 0xff)
        assertEquals(0xff, pushed[1].toInt() and 0xff)
    }

    @Test
    fun `256-byte data switches to OP_PUSHDATA2 with little-endian length`() {
        val data = ByteArray(256) { it.toByte() }
        val pushed = KnsInscriptionScript.canonicalPush(data)
        assertEquals(0x4d, pushed[0].toInt() and 0xff)
        assertEquals(0x00, pushed[1].toInt() and 0xff) // 256 = 0x0100 LE -> [0x00, 0x01]
        assertEquals(0x01, pushed[2].toInt() and 0xff)
        assertEquals(3 + 256, pushed.size)
    }

    @Test
    fun `canonicalPushSize matches the actual encoded length for sizes within the script-element limit`() {
        for (len in listOf(0, 1, 75, 76, 255, 256, 520)) {
            assertEquals("len=$len", KnsInscriptionScript.canonicalPush(ByteArray(len)).size, KnsInscriptionScript.canonicalPushSize(len))
        }
    }

    @Test
    fun `canonicalPushSize formula holds at the larger OP_PUSHDATA2 and OP_PUSHDATA4 boundaries`() {
        // These exceed the 520-byte script-element limit so canonicalPush() itself would reject
        // them — canonicalPushSize() is a pure formula used for fee/mass estimation only, and
        // must still report the right size class at each boundary.
        assertEquals(3 + 65535, KnsInscriptionScript.canonicalPushSize(65535))
        assertEquals(5 + 65536, KnsInscriptionScript.canonicalPushSize(65536))
        assertEquals(5 + 100000, KnsInscriptionScript.canonicalPushSize(100000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `data over 520 bytes is rejected`() {
        KnsInscriptionScript.canonicalPush(ByteArray(521))
    }

    // --- buildRedeemScript -----------------------------------------------------------

    private val pubKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `redeem script has the exact envelope structure`() {
        val payload = """{"op":"create","p":"domain","v":"test"}""".toByteArray()
        val script = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", payload)

        assertEquals(0x20, script[0].toInt() and 0xff)
        assertArrayEquals(pubKey, script.copyOfRange(1, 33))
        assertEquals(0xAC, script[33].toInt() and 0xff) // OP_CHECKSIG
        assertEquals(0x00, script[34].toInt() and 0xff) // OP_FALSE
        assertEquals(0x63, script[35].toInt() and 0xff) // OP_IF

        val titlePush = KnsInscriptionScript.canonicalPush("kns".toByteArray())
        assertArrayEquals(titlePush, script.copyOfRange(36, 36 + titlePush.size))

        val afterTitle = 36 + titlePush.size
        assertEquals(0x00, script[afterTitle].toInt() and 0xff) // OP_0 separator

        val payloadPush = KnsInscriptionScript.canonicalPush(payload)
        val payloadStart = afterTitle + 1
        assertArrayEquals(payloadPush, script.copyOfRange(payloadStart, payloadStart + payloadPush.size))

        assertEquals(0x68, script.last().toInt() and 0xff) // OP_ENDIF
        assertEquals(payloadStart + payloadPush.size + 1, script.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `redeem script rejects a non-32-byte pubkey`() {
        KnsInscriptionScript.buildRedeemScript(ByteArray(31), "kns", "{}".toByteArray())
    }

    // --- commitAddress -----------------------------------------------------------

    @Test
    fun `commit address round-trips as a valid P2SH address`() {
        val script = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", """{"op":"create","p":"domain","v":"test"}""".toByteArray())
        val address = KnsInscriptionScript.commitAddress(script, "kaspa")

        assertTrue(KaspaAddress.isValid(address))
        val (version, payload) = KaspaAddress.decode(address)
        assertEquals(8, version.toInt())
        assertEquals(32, payload.size)
    }

    @Test
    fun `commit address scriptPublicKey uses OP_BLAKE2B, an explicit push length, and OP_EQUAL`() {
        val script = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", """{"op":"create","p":"domain","v":"test"}""".toByteArray())
        val address = KnsInscriptionScript.commitAddress(script, "kaspa")
        val spk = KaspaAddress.getScriptPublicKey(address)

        // OP_BLAKE2B(aa) + push-32(20) + 32-byte hash (hex) + OP_EQUAL(87) — the push-length byte
        // is required since OP_BLAKE2B is a real opcode, not a push, unlike the P2PK cases.
        assertTrue(spk.startsWith("aa20"))
        assertTrue(spk.endsWith("87"))
        assertEquals(2 + 2 + 64 + 2, spk.length)
    }

    @Test
    fun `same redeem script always produces the same commit address`() {
        val script = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", """{"op":"create","p":"domain","v":"test"}""".toByteArray())
        assertEquals(
            KnsInscriptionScript.commitAddress(script, "kaspa"),
            KnsInscriptionScript.commitAddress(script, "kaspa")
        )
    }

    @Test
    fun `a different payload produces a different commit address`() {
        val scriptA = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", """{"op":"create","p":"domain","v":"aaa"}""".toByteArray())
        val scriptB = KnsInscriptionScript.buildRedeemScript(pubKey, "kns", """{"op":"create","p":"domain","v":"bbb"}""".toByteArray())
        assertTrue(KnsInscriptionScript.commitAddress(scriptA, "kaspa") != KnsInscriptionScript.commitAddress(scriptB, "kaspa"))
    }
}
